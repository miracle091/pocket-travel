package com.pockettravel.feature.ai

import android.content.Context
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.feature.ai.llamacpp.CpuBackendInfo
import com.pockettravel.feature.ai.llamacpp.InferenceEngine
import com.pockettravel.feature.ai.llamacpp.internal.InferenceEngineImpl
import com.pockettravel.feature.ai.llamacpp.isModelLoaded
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper attorno al bridge JNI di llama.cpp (com.pockettravel.feature.ai.llamacpp; llama.cpp e' il
 * submodule third-party/llama-cpp). [InferenceEngineImpl] e' un singleton di processo (un solo backend
 * GGML/NDK caricato una volta): qui non lo si ricrea mai, si carica/scarica solo il MODELLO al
 * suo interno.
 */
@Singleton
class LlamaOnDeviceLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: LlmModelManager,
    private val coordinator: AiModelCoordinator,
    private val aiSettingsStore: AiSettingsStore,
    private val deviceAiCapability: DeviceAiCapability,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : OnDeviceLlmEngine {
    private val mutex = Mutex()
    private val engine: InferenceEngine by lazy { InferenceEngineImpl.getInstance(context) }

    // File del modello oggi in memoria (null se nessuno): senza, dopo un cambio di modello scelto
    // il motore resterebbe sul precedente, perche' isModelLoaded non dice quale sia.
    private var loadedModelPath: String? = null

    private val _stats = MutableStateFlow(LlmRuntimeStats())

    override val stats: StateFlow<LlmRuntimeStats> = _stats.asStateFlow()

    private val _isLoadingModel = MutableStateFlow(false)

    override val isLoadingModel: StateFlow<Boolean> = _isLoadingModel.asStateFlow()

    override val cpuBackend: StateFlow<CpuBackendInfo?> get() = engine.cpuBackend

    override suspend fun ensureLoaded(): Long = withContext(ioDispatcher) {
        coordinator.withModelLock {
            mutex.withLock {
                if (isSelectedModelLoaded()) {
                    0L
                } else {
                    val start = System.currentTimeMillis()
                    loadModelIfNeeded()
                    System.currentTimeMillis() - start
                }
            }
        }
    }

    override suspend fun generateAll(prompts: List<String>): OnDeviceLlmEngine.GenerationBatch = withContext(ioDispatcher) {
        coordinator.withModelLock {
            mutex.withLock {
                val loadStart = System.currentTimeMillis()
                val wasLoaded = isSelectedModelLoaded()
                loadModelIfNeeded()
                val loadTimeMs = if (wasLoaded) 0L else System.currentTimeMillis() - loadStart
                val start = System.currentTimeMillis()
                val answers = prompts.map {
                    engine.resetConversation()
                    _stats.collectTimed(engine.sendUserPrompt(it), { engine.contextUsage.value }) { }
                }
                OnDeviceLlmEngine.GenerationBatch(loadTimeMs, System.currentTimeMillis() - start, answers)
            }
        }
    }

    override suspend fun generate(prompt: String): String = generateStream(prompt).toList().joinToString(separator = "")

    override fun generateStream(prompt: String): Flow<String> = flow {
        coordinator.withModelLock {
            mutex.withLock {
                loadModelIfNeeded()
                // Reset esplicito: i dati di training (generate_sft_dataset.py) non hanno mai
                // un turno "system", solo user+assistant — senza reset, sendUserPrompt
                // accumulerebbe la history tra una domanda e l'altra invece di restare un turno
                // singolo.
                engine.resetConversation()
                _stats.collectTimed(engine.sendUserPrompt(prompt), { engine.contextUsage.value }) { emit(it) }
            }
        }
    }.flowOn(ioDispatcher)

    override suspend fun generateWithGrammar(prompt: String, grammar: String, maxTokens: Int): String = withContext(ioDispatcher) {
        coordinator.withModelLock {
            mutex.withLock {
                loadModelIfNeeded()
                engine.resetConversation()
                engine.setGrammar(grammar)
                try {
                    _stats.collectTimed(engine.sendUserPrompt(prompt, maxTokens), { engine.contextUsage.value }) { }
                } finally {
                    // NonCancellable: dopo una cancellazione il sampler con la grammatica resterebbe per le domande normali.
                    withContext(NonCancellable) { if (engine.state.value is InferenceEngine.State.ModelReady) engine.setGrammar("") }
                }
            }
        }
    }

    // Su IO: liberare un modello da qualche GB blocca il thread per il tempo della free (chiamate dal main).
    // cleanUp() e' suspend e passa da solo al dispatcher nativo: nessun withContext(IO) qui.
    override suspend fun release() = coordinator.withModelLock { releaseWithoutLock() }

    override suspend fun releaseAndDelete(): Boolean = coordinator.withModelLock {
        releaseWithoutLock()
        modelManager.deleteWithoutLock(aiSettingsStore.selectedModelDefinition())
    }

    suspend fun releaseWithoutLock() {
        loadedModelPath = null
        _stats.update { it.copy(loadedModelId = null) }
        val state = engine.state.value
        // cleanUp() lancia IllegalStateException fuori da ModelReady/Error (es. nessun modello
        // ancora caricato): va chiamata solo se c'e' davvero qualcosa da scaricare.
        if (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) {
            engine.cleanUp()
        }
    }

    private fun selectedModelPath(): String =
        modelManager.modelFile(aiSettingsStore.selectedModelDefinition()).absolutePath

    private fun isSelectedModelLoaded(): Boolean =
        engine.state.value.isModelLoaded && loadedModelPath == selectedModelPath()

    private suspend fun loadModelIfNeeded() {
        if (isSelectedModelLoaded()) return
        // Un altro modello in memoria (scelta cambiata): va scaricato prima di caricare quello nuovo.
        if (engine.state.value is InferenceEngine.State.ModelReady) engine.cleanUp()
        loadedModelPath = null
        // Un errore precedente (caricamento o generazione) lascia il motore in Error, da cui
        // loadModel() rifiuta di partire: senza questo reset ogni richiesta successiva fallirebbe
        // fino al riavvio dell'app. cleanUp() libera anche l'eventuale modello rimasto in memoria.
        if (engine.state.value is InferenceEngine.State.Error) engine.cleanUp()
        val definition = aiSettingsStore.selectedModelDefinition()
        check(modelManager.isDownloaded(definition)) { "Modello IA non scaricato" }
        val path = modelManager.modelFile(definition).absolutePath
        val loadStart = System.nanoTime()
        _isLoadingModel.value = true
        try {
            engine.loadModel(
                path,
                topK = TOP_K,
                topP = TOP_P,
                nThreads = deviceAiCapability.inferenceThreadCount(),
            )
        } finally {
            _isLoadingModel.value = false
        }
        loadedModelPath = path
        _stats.update { it.copy(loadedModelId = definition.id, loadTimeMs = (System.nanoTime() - loadStart) / NANOS_PER_MS) }
    }

    private companion object {
        // topK/topP non ancora verificati su device reale. La temperature (0.3, bassa per ridurre
        // le allucinazioni su temi normativi/sanitari) e' fissata lato nativo
        // (ai_chat.cpp: DEFAULT_SAMPLER_TEMP), non e' un parametro di loadModel().
        const val TOP_K = 10
        const val TOP_P = 0.95f
    }
}
