// Adattato da examples/llama.android (com.arm.aichat) di ggml-org/llama.cpp, git tag v0.5.0 —
// vedi third-party/llama-cpp/LICENSE-LLAMA-CPP.txt. Solo package e nome della libreria nativa
// (System.loadLibrary) sono cambiati, per restare allineati ai simboli JNI di ai_chat.cpp.
package com.pockettravel.feature.ai.llamacpp.internal

import android.content.Context
import android.util.Log
import com.pockettravel.feature.ai.llamacpp.InferenceEngine
import com.pockettravel.feature.ai.llamacpp.UnsupportedArchitectureException
import com.pockettravel.feature.ai.llamacpp.internal.InferenceEngineImpl.Companion.getInstance
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * JNI wrapper for the llama.cpp library providing Android-friendly access to large language models.
 *
 * This class implements a singleton pattern for managing the lifecycle of a single LLM instance.
 * All operations are executed on a dedicated single-threaded dispatcher to ensure thread safety
 * with the underlying C++ native code.
 *
 * The typical usage flow is:
 * 1. Get instance via [getInstance]
 * 2. Load a model with [loadModel]
 * 3. Send prompts with [sendUserPrompt]
 * 4. Generate responses as token streams
 * 5. Perform [cleanUp] when done with a model
 *
 * Singleton di processo (vedi [getInstance]): il codice dell'app non chiama mai [destroy], che
 * libererebbe il backend nativo senza modo di ricaricarlo; vedi il suo KDoc in
 * [com.pockettravel.feature.ai.llamacpp.InferenceEngine].
 *
 * State transitions are managed automatically and validated at each operation.
 *
 * @see ai_chat.cpp for the native implementation details
 */
internal class InferenceEngineImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        @Volatile
        private var instance: InferenceEngine? = null

        /**
         * Create or obtain [InferenceEngineImpl]'s single instance.
         *
         * @param Context for obtaining native library directory
         * @throws IllegalArgumentException if native library path is invalid
         *
         * Il caricamento della libreria nativa e' asincrono (vedi `init` della classe): un suo
         * fallimento non arriva qui ma porta lo stato a [InferenceEngine.State.Error].
         */
        internal fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                Log.i(TAG, "Instantiating InferenceEngineImpl,,,")
                InferenceEngineImpl(nativeLibDir).also { instance = it }
            }
    }

    /**
     * JNI methods
     * @see ai_chat.cpp
     *
     * @FastNative solo sulle chiamate istantanee: un thread dentro una @FastNative non si puo'
     * sospendere per il GC, e caricamento, prompt e generazione durano secondi (jank o ANR).
     */
    private external fun init(nativeLibDir: String)

    private external fun load(modelPath: String): Int

    private external fun prepare(topK: Int, topP: Float, nThreads: Int): Int

    @FastNative
    private external fun systemInfo(): String

    private external fun setGrammarNative(grammar: String): Int

    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    private external fun generateNextToken(): String?

    @FastNative
    private external fun resetConversationNative()

    @FastNative
    private external fun cancelGeneration()

    private external fun unload()

    private external fun shutdown()

    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    @Volatile
    private var _cancelGeneration = false

    // Valorizzato se System.loadLibrary/init() falliscono: e' un errore definitivo (ABI non
    // supportata, backend .so mancante), a differenza di un Error dopo un loadModel/generazione, da
    // cui cleanUp() recupera. Senza libreria nemmeno le chiamate native di unload() sono possibili.
    @Volatile
    private var nativeInitError: Exception? = null

    /**
     * Single-threaded coroutine dispatcher & scope for LLama asynchronous operations
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = InferenceEngine.State.Initializing
                Log.i(TAG, "Loading native library...")
                System.loadLibrary("llm-engine")
                init(nativeLibDir)
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "Native library loaded! System info: \n${systemInfo()}")

            } catch (e: Throwable) {
                // Dentro il launch: fuori da qui l'eccezione (anche UnsatisfiedLinkError, che e' un
                // Error) uscirebbe dallo scope e farebbe terminare il processo, oppure lo stato
                // resterebbe Initializing per sempre.
                Log.e(TAG, "Failed to load native library", e)
                val error = e as? Exception ?: IllegalStateException("Native library failed to load", e)
                nativeInitError = error
                _state.value = InferenceEngine.State.Error(error)
            }
        }
    }

    /**
     * Load the LLM
     */
    override suspend fun loadModel(pathToModel: String, topK: Int, topP: Float, nThreads: Int) {
        // L'init della libreria nativa parte alla creazione e finisce in modo asincrono: se loadModel
        // arriva prima, aspetta invece di fallire il check sullo stato Initializing.
        _state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing }
        nativeInitError?.let {
            throw IllegalStateException("Motore IA non disponibile: libreria nativa non caricata (${it.message})", it)
        }
        loadModelOnDispatcher(pathToModel, topK, topP, nThreads)
    }

    private suspend fun loadModelOnDispatcher(pathToModel: String, topK: Int, topP: Float, nThreads: Int) =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }

            try {
                Log.i(TAG, "Checking access to model file... \n$pathToModel")
                File(pathToModel).let {
                    require(it.exists()) { "File not found" }
                    require(it.isFile) { "Not a valid file" }
                    require(it.canRead()) { "Cannot read file" }
                }

                Log.i(TAG, "Loading model... \n$pathToModel")
                _state.value = InferenceEngine.State.LoadingModel
                load(pathToModel).let {
                    if (it != 0) throw UnsupportedArchitectureException()
                }
                prepare(topK, topP, nThreads).let {
                    if (it != 0) throw IOException("Failed to prepare resources")
                }
                Log.i(TAG, "Model loaded!")

                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, (e.message ?: "Error loading model") + "\n" + pathToModel, e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }

    override suspend fun resetConversation() =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot reset conversation in ${_state.value.javaClass.simpleName}!"
            }
            resetConversationNative()
        }

    override suspend fun setGrammar(grammar: String) =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot set grammar in ${_state.value.javaClass.simpleName}!"
            }
            val result = setGrammarNative(grammar)
            require(result == 0) { "Invalid grammar: $result" }
        }

    /**
     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]
     */
    override fun sendUserPrompt(
        message: String,
        predictLength: Int,
    ): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        Log.i(TAG, "Sending user prompt...")
        _state.value = InferenceEngine.State.ProcessingUserPrompt

        // Fuori dal try (a differenza del riferimento Arm, che faceva solo return@flow): lo stato
        // resterebbe ProcessingUserPrompt per sempre, bloccando ogni prompt successivo fino al
        // ricaricamento del modello, e il chiamante riceverebbe una risposta vuota senza errore.
        // Il modello resta valido (resetConversation() pulisce la KV-cache al prossimo prompt),
        // quindi si torna a ModelReady e non a Error.
        processUserPrompt(message, predictLength).let { result ->
            if (result != 0) {
                Log.e(TAG, "Failed to process user prompt: $result")
                _state.value = InferenceEngine.State.ModelReady
                throw IOException("Failed to process user prompt: $result")
            }
        }

        try {
            Log.i(TAG, "User prompt processed. Generating assistant prompt...")
            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { utf8token ->
                    if (utf8token.isNotEmpty()) emit(utf8token)
                } ?: break
            }
            if (_cancelGeneration) {
                Log.i(TAG, "Assistant generation aborted per requested.")
                // Riallinea la KV cache nativa alla posizione precedente al turno interrotto: senza
                // questo, i token gia' campionati (mai aggiunti a chat_msgs, che si aggiorna solo
                // su EOG o a n_predict) resterebbero nella cache e disallineerebbero il prossimo prompt.
                cancelGeneration()
            } else {
                Log.i(TAG, "Assistant generation complete. Awaiting user prompt...")
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Assistant generation's flow collection cancelled.")
            cancelGeneration()
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    /**
     * Unloads the model and frees resources, or reset error states
     */
    override suspend fun cleanUp() {
        _cancelGeneration = true
        withContext(llamaDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    Log.i(TAG, "Unloading model and free resources...")
                    _state.value = InferenceEngine.State.UnloadingModel

                    unload()

                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Model unloaded!")
                    Unit
                }

                is InferenceEngine.State.Error -> {
                    // Errore di init della libreria: niente da scaricare ne' da resettare, le
                    // chiamate native non sono possibili e lo stato Error e' definitivo.
                    if (nativeInitError != null) return@withContext
                    // Il riferimento Arm qui resettava solo lo stato: se l'errore arrivava dopo il
                    // caricamento (prepare() o generazione), il modello restava in memoria nativa e
                    // il loadModel() successivo lo sovrascriveva, perdendo GB di RAM. unload() e'
                    // sicura anche se il modello non e' mai stato caricato (vedi ai_chat.cpp).
                    Log.i(TAG, "Resetting error states...")
                    unload()
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "States reset!")
                    Unit
                }

                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    /**
     * Cancel all ongoing coroutines and free GGML backends
     */
    override suspend fun destroy() {
        _cancelGeneration = true
        withContext(llamaDispatcher) {
            when(_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                // Dopo un errore di init non ci sono chiamate native possibili.
                is InferenceEngine.State.Error -> if (nativeInitError == null) { unload(); shutdown() } else Unit
                is InferenceEngine.State.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }
}
