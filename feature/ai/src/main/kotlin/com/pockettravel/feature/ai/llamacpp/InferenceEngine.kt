// Adattato da examples/llama.android (com.arm.aichat) di ggml-org/llama.cpp, git tag v0.4.1 —
// vedi third-party/llama-cpp/LICENSE-LLAMA-CPP.txt. Interfaccia invariata, solo il package.
package com.pockettravel.feature.ai.llamacpp

import com.pockettravel.feature.ai.llamacpp.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the core LLM inference operations.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(
        pathToModel: String,
        topK: Int = DEFAULT_TOP_K,
        topP: Float = DEFAULT_TOP_P,
        nThreads: Int = DEFAULT_N_THREADS,
    )

    /**
     * Svuota KV-cache e history della chat: da usare prima di un prompt nuovo e senza stato, perche' non
     * erediti il contesto di un [sendUserPrompt] precedente. Non c'e' system prompt: i dati di training
     * non hanno mai un turno "system".
     */
    suspend fun resetConversation()

    /**
     * Vincola le risposte successive a una grammatica GBNF (regola "root"); "" torna al campionamento
     * libero. Resta attiva finche' non la si cambia.
     *
     * @throws IllegalArgumentException se la grammatica non si compila (il sampler precedente resta)
     */
    suspend fun setGrammar(grammar: String)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Unloads the currently loaded model.
     */
    suspend fun cleanUp()

    /**
     * Libera il backend GGML nativo (llama_backend_free), oltre a quanto libera gia' [cleanUp].
     * Il codice dell'app non la chiama: l'implementazione e' un singleton di processo e non puo'
     * rieseguire l'`init()` nativo di [loadModel], quindi chiamarla romperebbe il motore per il resto
     * del processo. Lo spegnimento vero e' la fine del processo; resta per completezza e per i test
     * strumentati che creano una propria istanza.
     */
    suspend fun destroy()

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
        // Default di common_params_sampling (llama.cpp): usati solo se il chiamante non passa i
        // propri valori a loadModel().
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_TOP_P = 0.95f
        // Fallback se il chiamante non passa il proprio valore (vedi DeviceAiCapability.inferenceThreadCount).
        const val DEFAULT_N_THREADS = 4
    }
}

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

class UnsupportedArchitectureException : Exception()
