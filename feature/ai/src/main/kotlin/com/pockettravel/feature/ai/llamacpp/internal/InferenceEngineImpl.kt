// Adattato da examples/llama.android (com.arm.aichat) di ggml-org/llama.cpp, git tag v0.6.0 —
// vedi third-party/LICENSE-LLAMA-CPP.txt. Package e nome della libreria nativa sono allineati ai simboli
// JNI di ai_chat.cpp; la logica e' cambiata: variante CPU forzata (CpuBackendOverride), errore di init definitivo
// (nativeInitError), uso del contesto (contextUsage), grammatica, azzeramento della conversazione, chiusura del turno
// interrotto e scaricamento del modello anche dopo un errore.
package com.pockettravel.feature.ai.llamacpp.internal

import android.content.Context
import android.os.Process
import android.util.Log
import com.pockettravel.feature.ai.llamacpp.ContextUsage
import com.pockettravel.feature.ai.llamacpp.CpuBackendInfo
import com.pockettravel.feature.ai.llamacpp.CpuBackendOverride
import com.pockettravel.feature.ai.llamacpp.HistoryTurn
import com.pockettravel.feature.ai.llamacpp.InferenceEngine
import com.pockettravel.feature.ai.llamacpp.UnsupportedArchitectureException
import com.pockettravel.feature.ai.llamacpp.internal.InferenceEngineImpl.Companion.getInstance
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
 * Singleton di processo (vedi [getInstance]): il backend nativo si inizializza una volta e vive quanto il processo.
 *
 * State transitions are managed automatically and validated at each operation.
 *
 * @see ai_chat.cpp for the native implementation details
 */
internal class InferenceEngineImpl private constructor(
    private val nativeLibDir: String,
    // Variante CPU scelta dal task manager di debug (CpuBackendOverride); null: scelta automatica (init in ai_chat.cpp).
    private val forcedCpuBackend: String?,
) : InferenceEngine {

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName
        private const val CONTEXT_SIZE_INDEX = 3
        private const val HISTORY_TOKENS_INDEX = 4
        private const val HISTORY_TURNS_INDEX = 5
        private const val INFERENCE_NICE = 4

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
                // Ricontrollato dentro il lock: due thread arrivati insieme creerebbero due motori sulle stesse globali native.
                instance?.let { return@synchronized it }
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                Log.i(TAG, "Instantiating InferenceEngineImpl,,,")
                InferenceEngineImpl(nativeLibDir, CpuBackendOverride.forcedPath(context)).also { instance = it }
            }
    }

    /**
     * JNI methods
     * @see ai_chat.cpp
     *
     * @FastNative solo sulle chiamate istantanee: un thread dentro una @FastNative non si puo'
     * sospendere per il GC, e caricamento, prompt e generazione durano secondi (jank o ANR).
     */
    private external fun init(nativeLibDir: String, forcedCpuBackend: String?)

    private external fun load(modelPath: String): Int

    private external fun prepare(topK: Int, topP: Float, nThreads: Int, nThreadsBatch: Int, speculativeNgram: Boolean): Int

    @FastNative
    private external fun systemInfo(): String

    private external fun setGrammarNative(grammar: String): Int

    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    private external fun generateNextToken(): String?

    // Immagini (mmproj): 0 ok, 1 file non valido, 2 nessun modello, 3 niente encoder di immagini.
    private external fun loadMmprojNative(mmprojPath: String): Int

    // Come processUserPrompt, con i byte di un JPEG o PNG nel turno; in piu' 5 mmproj non caricato, 6 foto non leggibile,
    // 7 tokenizzazione rifiutata.
    private external fun processImagePrompt(userPrompt: String, image: ByteArray, predictLength: Int): Int

    @FastNative
    private external fun resetConversationNative()

    // 0 ok, 3 messaggi non validi o modello senza template, 4 modello non pronto. Domanda, risposta, domanda...
    private external fun setHistoryNative(messages: Array<String>): Int

    // Non @FastNative: chiude il turno interrotto decodificando il token di fine turno.
    private external fun cancelGeneration()

    // Ferma la lettura del prompt in corso (processUserPrompt risponde PROMPT_INTERRUPTED). Va chiamata da un thread
    // diverso da quello del motore, che e' occupato nella lettura; istantanea.
    @FastNative
    private external fun requestAbort()

    // Azzera la richiesta di stop prima di ogni lettura del prompt; istantanea.
    @FastNative
    private external fun clearAbort()

    // {token del prompt, token generati, posizione nella KV cache, dimensione del contesto}
    private external fun contextUsageNative(): IntArray?

    // Nome del file della variante CPU caricata (libggml-cpu-*.so), null se nessuna. Non @FastNative:
    // dl_iterate_phdr aspetta il lock del linker se un altro thread sta caricando una libreria.
    private external fun loadedCpuBackendNative(): String?

    // {SVE2 1/0, SME 1/0, lunghezza dei vettori SVE in byte}; tutti 0 fuori da arm64
    @FastNative
    private external fun cpuFeaturesNative(): IntArray?

    // Solo per le prove sul dispositivo (ChatTemplateDeviceTest): formato dei prompt scelto al caricamento del modello
    // e testo che il modello riceverebbe per un prompt come primo turno.
    private external fun chatFormatNative(): String

    private external fun formatPromptNative(userPrompt: String): String?

    private external fun formatPromptWithHistoryNative(userPrompt: String, messages: Array<String>): String?

    // Solo per le prove sul dispositivo (Utf8DeviceTest): il testo tokenizzato come un prompt e ritrasformato in testo
    // come una risposta, fermandosi dopo maxTokens token.
    private external fun streamTokensNative(text: String, maxTokens: Int): String?

    // Solo per le prove sul dispositivo (HistoryDeviceTest): token del testo, con i token del template letti come tali o no.
    private external fun countTokensNative(text: String, parseSpecial: Boolean): Int

    /** {ns di pp, ns di tg} di un'esecuzione del benchmark nativo, null se fallisce (ai_chat.cpp, bench_impl). */
    private external fun benchNative(nPrompt: Int, nGen: Int, nThreads: Int, seed: Int): LongArray?

    private external fun llamaVersionNative(): String?

    private external fun unload()

    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private val _contextUsage = MutableStateFlow<ContextUsage?>(null)
    override val contextUsage: StateFlow<ContextUsage?> = _contextUsage.asStateFlow()

    private val _cpuBackend = MutableStateFlow<CpuBackendInfo?>(null)
    override val cpuBackend: StateFlow<CpuBackendInfo?> = _cpuBackend.asStateFlow()

    @Volatile
    private var _cancelGeneration = false

    // mmproj in memoria (null se nessuno): lo toglie unload() insieme al modello.
    @Volatile
    private var loadedMmproj: String? = null

    // Valorizzato se System.loadLibrary/init() falliscono: e' un errore definitivo (ABI non
    // supportata, backend .so mancante), a differenza di un Error dopo un loadModel/generazione, da
    // cui cleanUp() recupera. Senza libreria nemmeno le chiamate native di unload() sono possibili.
    @Volatile
    private var nativeInitError: Exception? = null

    /**
     * Single-threaded coroutine dispatcher & scope for LLama asynchronous operations.
     *
     * Thread proprio ("llama") a nice [INFERENCE_NICE]: sotto UI, RenderThread e tastiera, ma sopra il gruppo
     * di background (nice >= 10), che lo sposterebbe sui core piccoli. La priorita' si imposta una volta qui, prima
     * di qualsiasi lavoro nativo: i worker di OpenMP e del threadpool di ggml nascono da questo thread e la ereditano.
     * Caricamento, benchmark e generazione girano tutti su di lui.
     */
    private val llamaDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(INFERENCE_NICE)
            task.run()
        }, "llama").apply { isDaemon = true }
    }.asCoroutineDispatcher()
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
                init(nativeLibDir, forcedCpuBackend)
                val features = cpuFeaturesNative()
                _cpuBackend.value = CpuBackendInfo(
                    loadedFile = loadedCpuBackendNative(),
                    forcedFile = forcedCpuBackend?.let { File(it).name },
                    sve2 = features?.getOrNull(0) == 1,
                    sme = features?.getOrNull(1) == 1,
                    sveVectorBytes = features?.getOrNull(2) ?: 0,
                )
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
    override suspend fun loadModel(pathToModel: String, topK: Int, topP: Float, nThreads: Int, speculativeNgram: Boolean, nThreadsBatch: Int) {
        // L'init della libreria nativa parte alla creazione e finisce in modo asincrono: se loadModel
        // arriva prima, aspetta invece di fallire il check sullo stato Initializing.
        _state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing }
        nativeInitError?.let {
            throw IllegalStateException("Motore IA non disponibile: libreria nativa non caricata (${it.message})", it)
        }
        loadModelOnDispatcher(pathToModel, topK, topP, nThreads, nThreadsBatch, speculativeNgram)
    }

    private suspend fun loadModelOnDispatcher(
        pathToModel: String,
        topK: Int,
        topP: Float,
        nThreads: Int,
        nThreadsBatch: Int,
        speculativeNgram: Boolean,
    ) =
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
                prepare(topK, topP, nThreads, nThreadsBatch, speculativeNgram).let {
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

    override suspend fun resetConversation() = resetConversation(emptyList())

    override suspend fun resetConversation(history: List<HistoryTurn>) =
        withContext(llamaDispatcher) {
            requireReady("reset conversation")
            resetConversationNative()
            if (history.isNotEmpty()) {
                val result = setHistoryNative(history.flatMap { listOf(it.question, it.answer) }.toTypedArray())
                require(result == 0) { "Invalid history: $result" }
            }
        }

    // Lancia IllegalStateException se il modello non e' pronto; con [action] il messaggio dice cosa non si puo' fare.
    private fun requireReady(action: String? = null) {
        check(_state.value is InferenceEngine.State.ModelReady) {
            val state = _state.value.javaClass.simpleName
            if (action == null) "No model ready: $state" else "Cannot $action in $state!"
        }
    }

    /** "none", "jinja", "chatml" o "builtin", gli ultimi due anche con "-think" (chatFormatNative in ai_chat.cpp). */
    internal suspend fun chatFormat(): String =
        withContext(llamaDispatcher) {
            requireReady()
            chatFormatNative()
        }

    internal suspend fun formatPrompt(message: String): String =
        withContext(llamaDispatcher) {
            requireReady()
            checkNotNull(formatPromptNative(message)) { "Chat template failed" }
        }

    /** Il testo che il modello legge per [message] dopo i turni di [history] (come [formatPrompt]), senza toccare la conversazione. */
    internal suspend fun formatPrompt(message: String, history: List<HistoryTurn>): String =
        withContext(llamaDispatcher) {
            requireReady()
            val messages = history.flatMap { listOf(it.question, it.answer) }.toTypedArray()
            checkNotNull(formatPromptWithHistoryNative(message, messages)) { "Chat template failed" }
        }

    internal suspend fun countTokens(text: String, parseSpecial: Boolean): Int =
        withContext(llamaDispatcher) {
            requireReady()
            countTokensNative(text, parseSpecial).also { check(it >= 0) { "Tokenization failed" } }
        }

    internal suspend fun streamTokens(text: String, maxTokens: Int): String =
        withContext(llamaDispatcher) {
            requireReady()
            checkNotNull(streamTokensNative(text, maxTokens)) { "Tokenization failed" }
        }

    override suspend fun bench(nPrompt: Int, nGen: Int, nThreads: Int, seed: Int): LongArray =
        withContext(llamaDispatcher) {
            requireReady()
            checkNotNull(benchNative(nPrompt, nGen, nThreads, seed)) { "Benchmark failed" }
        }

    override suspend fun llamaVersion(): String = withContext(llamaDispatcher) { checkNotNull(llamaVersionNative()) { "Versione di llama.cpp non disponibile" } }

    override suspend fun setGrammar(grammar: String) =
        withContext(llamaDispatcher) {
            requireReady("set grammar")
            val result = setGrammarNative(grammar)
            require(result == 0) { "Invalid grammar: $result" }
        }

    /**
     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]
     */
    override fun sendUserPrompt(
        message: String,
        predictLength: Int,
    ): Flow<String> = promptFlow({ require(message.isNotEmpty()) { "User prompt discarded due to being empty!" } }) {
        processUserPrompt(message, predictLength)
    }

    override suspend fun loadMmproj(pathToMmproj: String) =
        withContext(llamaDispatcher) {
            requireReady("load mmproj")
            if (loadedMmproj == pathToMmproj) return@withContext
            require(File(pathToMmproj).isFile) { "mmproj not found" }
            Log.i(TAG, "Loading mmproj... " + pathToMmproj)
            loadedMmproj = null
            val result = loadMmprojNative(pathToMmproj)
            if (result != 0) throw IOException("Failed to load mmproj: $result")
            loadedMmproj = pathToMmproj
        }

    override fun sendUserPrompt(
        message: String,
        image: ByteArray,
        predictLength: Int,
    ): Flow<String> = promptFlow({
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        require(image.isNotEmpty()) { "Image discarded due to being empty!" }
        check(loadedMmproj != null) { "No mmproj loaded" }
    }) {
        processImagePrompt(message, image, predictLength)
    }

    /**
     * Flusso di una generazione: [validate] controlla gli argomenti, [process] decodifica il prompt (0 se riuscito) e poi
     * si campionano i token. Comune ai prompt di solo testo e a quelli con la foto.
     */
    private fun promptFlow(validate: () -> Unit, process: () -> Int): Flow<String> = flow {
        validate()
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        Log.i(TAG, "Sending user prompt...")
        _state.value = InferenceEngine.State.ProcessingUserPrompt

        // Fuori dal try (a differenza del riferimento Arm, che faceva solo return@flow): lo stato
        // resterebbe ProcessingUserPrompt per sempre, bloccando ogni prompt successivo fino al
        // ricaricamento del modello, e il chiamante riceverebbe una risposta vuota senza errore.
        // Il modello resta valido (il prompt rifiutato azzera la conversazione nativa),
        // quindi si torna a ModelReady e non a Error.
        // Anche se la chiamata nativa lancia (es. OutOfMemoryError da GetStringChars) lo stato torna a ModelReady.
        var result = -1
        try {
            readPrompt { process().also { result = it } }
        } catch (e: CancellationException) {
            // Stop durante la lettura (codice PROMPT_INTERRUPTED di ai_chat.cpp): la conversazione nativa e' gia' azzerata.
            // Stop arrivato a lettura finita, o durante una foto, che non si interrompe (codice 0): il turno e' aperto in
            // cache e si chiude come per uno Stop durante la risposta.
            if (result == 0) {
                publishContextUsage()
                cancelGeneration()
            }
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } finally {
            if (result != 0) _state.value = InferenceEngine.State.ModelReady
        }
        if (result != 0) {
            Log.e(TAG, "Failed to process user prompt: $result")
            throw IOException("Failed to process user prompt: $result")
        }
        publishContextUsage()

        try {
            Log.i(TAG, "User prompt processed. Generating assistant prompt...")
            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { utf8token ->
                    if (utf8token.isNotEmpty()) emit(utf8token)
                } ?: break
            }
            // Prima di cancelGeneration, che aggiunge alla KV cache il token di fine turno.
            publishContextUsage()
            if (_cancelGeneration) {
                Log.i(TAG, "Assistant generation aborted per requested.")
                // Chiude il turno interrotto con la risposta parziale: senza questo, i token gia' campionati
                // (mai aggiunti a chat_msgs, che si aggiorna solo su EOG o a n_predict) resterebbero nella
                // cache in un turno aperto e disallineerebbero il prossimo prompt.
                cancelGeneration()
            } else {
                Log.i(TAG, "Assistant generation complete. Awaiting user prompt...")
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Assistant generation's flow collection cancelled.")
            publishContextUsage()
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
     * Esegue [process], la lettura del prompt: una sola chiamata nativa bloccante sul thread del motore, che la
     * cancellazione della coroutine non interrompe. Un osservatore su un altro thread chiama [requestAbort] appena la
     * coroutine viene cancellata (Stop), senza passare da [llamaDispatcher], dove finirebbe dietro la lettura stessa.
     * Cancellata la coroutine, lancia [CancellationException] anche se la lettura e' finita.
     */
    private suspend fun readPrompt(process: () -> Int) = coroutineScope {
        // Qui e non nel nativo: uno Stop arrivato tra l'avvio dell'osservatore e l'inizio della lettura non si perde.
        clearAbort()
        val finished = AtomicBoolean(false)
        // UNDISPATCHED: l'osservatore e' in ascolto prima della lettura, anche con i thread di Default occupati.
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) requestAbort()
            }
        }
        try {
            ensureActive()
            process()
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

    private fun publishContextUsage() {
        val values = contextUsageNative() ?: return
        _contextUsage.value = ContextUsage(
            promptTokens = values[0],
            generatedTokens = values[1],
            usedTokens = values[2],
            contextSize = values[CONTEXT_SIZE_INDEX],
            historyTokens = values.getOrElse(HISTORY_TOKENS_INDEX) { 0 },
            historyTurns = values.getOrElse(HISTORY_TURNS_INDEX) { 0 },
        )
    }

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
                    loadedMmproj = null

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
                    loadedMmproj = null
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "States reset!")
                    Unit
                }

                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }
}
