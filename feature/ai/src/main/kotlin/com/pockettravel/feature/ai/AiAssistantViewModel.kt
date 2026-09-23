package com.pockettravel.feature.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AiUiState(
    val mode: AiEngineMode,
    val isDeviceCapable: Boolean,
    val availableModels: List<LlmModelDefinition> = emptyList(),
    val selectedModelId: String = "",
    val isModelDownloaded: Boolean,
    val downloadProgress: Float? = null,
    val isApiKeyConfigured: Boolean,
    val apiKeyInput: String = "",
    val benchmarkResult: BenchmarkResult? = null,
    val isBenchmarking: Boolean = false,
    val allBenchmarkResults: List<BenchmarkResult> = emptyList(),
    val showBenchmarkComparison: Boolean = false,
    val question: String = "",
    val isThinking: Boolean = false,
    val answer: AssistantAnswer? = null,
    val askedQuestion: String? = null,
    @StringRes val errorMessage: Int? = null,
)

@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val deviceAiCapability: DeviceAiCapability,
    private val modelManager: LlmModelManager,
    private val travelAssistant: TravelAssistant,
    private val engine: OnDeviceLlmEngine,
    private val aiSettingsStore: AiSettingsStore,
    private val modelDownloadScheduler: LlmModelDownloadScheduler,
    private val llmBenchmark: LlmBenchmark,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AiUiState(
            // Sotto i 4 GB la modalita' on-device non va nemmeno offerta come opzione (vedi
            // messaggio "meno di 4 GB di RAM" in AiAssistantScreen): forzare Online qui evita che
            // un mode salvato in precedenza (o il default ON_DEVICE) resti selezionato su un
            // device che non puo' comunque usarlo.
            mode = if (deviceAiCapability.isOnDeviceAiSupported()) aiSettingsStore.engineMode() else AiEngineMode.ONLINE,
            isDeviceCapable = deviceAiCapability.isOnDeviceAiSupported(),
            // L'ordine di RamTier (INSUFFICIENTE < MINIMO < CONFORTEVOLE < AMPIA) e' significativo
            // qui: un device di fascia superiore vede anche i modelli delle fasce inferiori.
            availableModels = LlmModelCatalog.ALL.filter { deviceAiCapability.ramTier().ordinal >= it.minRamTier.ordinal },
            selectedModelId = aiSettingsStore.selectedModelId(),
            isModelDownloaded = modelManager.isDownloaded(aiSettingsStore.selectedModelDefinition()),
            isApiKeyConfigured = aiSettingsStore.hasApiKey(),
            benchmarkResult = aiSettingsStore.benchmarkResult(aiSettingsStore.selectedModelId()),
            allBenchmarkResults = aiSettingsStore.allBenchmarkResults(),
        ),
    )
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    init {
        observeModelDownload()
    }

    // Osservato dall'init, non solo dopo aver premuto "Scarica": cosi' un download in corso
    // sopravvive a un kill di processo (WorkManager persiste il lavoro) e la UI ne mostra lo
    // stato reale alla riapertura dello schermo, invece di perdere il progresso perche' viveva
    // solo in viewModelScope.
    private fun observeModelDownload() {
        viewModelScope.launch {
            modelDownloadScheduler.observeDownload().collect { workInfo ->
                if (workInfo == null) return@collect
                when (workInfo.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        val downloaded = workInfo.progress.getLong(LlmModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
                        val total = workInfo.progress.getLong(LlmModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                        val progress = if (total > 0) downloaded / total.toFloat() else 0f
                        _uiState.update { it.copy(downloadProgress = progress) }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _uiState.update {
                            it.copy(downloadProgress = null, isModelDownloaded = modelManager.isDownloaded(aiSettingsStore.selectedModelDefinition()))
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val message = when (workInfo.outputData.getString(LlmModelDownloadWorker.KEY_FAILURE_REASON)) {
                            LlmModelDownloadWorker.FAILURE_REASON_INTEGRITY ->
                                R.string.ai_error_integrity
                            else -> R.string.ai_error_download
                        }
                        _uiState.update { it.copy(downloadProgress = null, errorMessage = message) }
                    }
                    WorkInfo.State.CANCELLED -> _uiState.update { it.copy(downloadProgress = null) }
                    WorkInfo.State.BLOCKED -> Unit
                }
            }
        }
    }

    fun onModeChanged(mode: AiEngineMode) {
        aiSettingsStore.setEngineMode(mode)
        _uiState.update { it.copy(mode = mode, answer = null, askedQuestion = null, errorMessage = null) }
    }

    fun onQuestionChanged(value: String) {
        _uiState.update { it.copy(question = value) }
    }

    fun onApiKeyInputChanged(value: String) {
        _uiState.update { it.copy(apiKeyInput = value) }
    }

    fun saveApiKey() {
        val key = _uiState.value.apiKeyInput.trim()
        if (key.isBlank()) return
        aiSettingsStore.setApiKey(key)
        _uiState.update { it.copy(isApiKeyConfigured = true, apiKeyInput = "") }
    }

    fun clearApiKey() {
        aiSettingsStore.clearApiKey()
        _uiState.update { it.copy(isApiKeyConfigured = false, answer = null) }
    }

    /** Cambia il modello selezionato senza scaricarlo: la UI mostra poi il pulsante di download
     *  per quello scelto. */
    fun onModelSelected(modelId: String) {
        val previousId = _uiState.value.selectedModelId
        aiSettingsStore.setSelectedModelId(modelId)
        if (previousId != modelId) {
            // Il motore in memoria (se creato) serve ancora il modello precedente: va rilasciato
            // subito, non solo quando il nuovo download finisce, altrimenti una domanda posta
            // durante il cambio riceverebbe una risposta dal modello sbagliato.
            viewModelScope.launch { engine.release() }
        }
        val definition = aiSettingsStore.selectedModelDefinition()
        _uiState.update {
            it.copy(
                selectedModelId = modelId,
                isModelDownloaded = modelManager.isDownloaded(definition),
                benchmarkResult = aiSettingsStore.benchmarkResult(modelId),
                answer = null,
                errorMessage = null,
            )
        }
    }

    /** Solo per il modello on-device attualmente scaricato — nessun senso di misurare un modello
     *  non ancora presente sul device. */
    fun runBenchmark() {
        if (_uiState.value.isBenchmarking) return
        val modelId = _uiState.value.selectedModelId
        _uiState.update { it.copy(isBenchmarking = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val result = llmBenchmark.run(modelId)
                aiSettingsStore.saveBenchmarkResult(result)
                _uiState.update {
                    it.copy(isBenchmarking = false, benchmarkResult = result, allBenchmarkResults = aiSettingsStore.allBenchmarkResults())
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isBenchmarking = false, errorMessage = R.string.ai_error_benchmark) }
            }
        }
    }

    fun showBenchmarkComparison() {
        _uiState.update { it.copy(showBenchmarkComparison = true, allBenchmarkResults = aiSettingsStore.allBenchmarkResults()) }
    }

    fun hideBenchmarkComparison() {
        _uiState.update { it.copy(showBenchmarkComparison = false) }
    }

    fun downloadModel() {
        if (!deviceAiCapability.isOnDeviceAiSupported()) {
            _uiState.update { it.copy(isDeviceCapable = false, errorMessage = R.string.ai_error_ram) }
            return
        }
        val definition = aiSettingsStore.selectedModelDefinition()
        if (definition.sha256 == null) {
            _uiState.update { it.copy(errorMessage = R.string.ai_error_not_available) }
            return
        }
        if (modelManager.availableStorageBytes() < definition.sizeBytes) {
            _uiState.update { it.copy(errorMessage = R.string.ai_error_space) }
            return
        }
        _uiState.update { it.copy(downloadProgress = 0f, errorMessage = null) }
        modelDownloadScheduler.enqueueDownload()
    }

    fun deleteModel() {
        viewModelScope.launch {
            engine.releaseAndDelete()
            _uiState.update { it.copy(isModelDownloaded = false, answer = null, askedQuestion = null) }
        }
    }

    fun ask(regionId: String) {
        val state = _uiState.value
        val question = state.question.trim()
        if (question.isBlank() || state.isThinking) return

        viewModelScope.launch {
            _uiState.update { it.copy(isThinking = true, errorMessage = null, askedQuestion = question, question = "", answer = null) }
            try {
                val answer = travelAssistant.ask(regionId, question, state.mode)
                _uiState.update { it.copy(isThinking = false, answer = answer) }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isThinking = false, errorMessage = R.string.ai_error_answer)
                }
            }
        }
    }
}
