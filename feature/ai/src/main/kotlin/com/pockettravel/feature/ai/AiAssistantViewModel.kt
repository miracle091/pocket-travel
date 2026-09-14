package com.pockettravel.feature.ai

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
    val isModelDownloaded: Boolean,
    val downloadProgress: Float? = null,
    val isApiKeyConfigured: Boolean,
    val apiKeyInput: String = "",
    val hasHfToken: Boolean,
    val hfTokenInput: String = "",
    val question: String = "",
    val isThinking: Boolean = false,
    val answer: AssistantAnswer? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val deviceAiCapability: DeviceAiCapability,
    private val modelManager: LlmModelManager,
    private val travelAssistant: TravelAssistant,
    private val engine: OnDeviceLlmEngine,
    private val aiSettingsStore: AiSettingsStore,
    private val modelDownloadScheduler: LlmModelDownloadScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AiUiState(
            mode = aiSettingsStore.engineMode(),
            isDeviceCapable = deviceAiCapability.isOnDeviceAiSupported(),
            isModelDownloaded = modelManager.isDownloaded(),
            isApiKeyConfigured = aiSettingsStore.hasApiKey(),
            hasHfToken = aiSettingsStore.hasHuggingFaceToken(),
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
                            it.copy(downloadProgress = null, isModelDownloaded = modelManager.isDownloaded())
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val message = when (workInfo.outputData.getString(LlmModelDownloadWorker.KEY_FAILURE_REASON)) {
                            LlmModelDownloadWorker.FAILURE_REASON_AUTH ->
                                "Token HuggingFace mancante o non valido: verifica il token e riprova."
                            LlmModelDownloadWorker.FAILURE_REASON_INTEGRITY ->
                                "Il file scaricato non ha superato la verifica di integrità. Riprova."
                            else -> "Download del modello fallito. Riprova più tardi."
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
        _uiState.update { it.copy(mode = mode, answer = null, errorMessage = null) }
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

    fun onHfTokenInputChanged(value: String) {
        _uiState.update { it.copy(hfTokenInput = value) }
    }

    fun saveHfToken() {
        val token = _uiState.value.hfTokenInput.trim()
        if (token.isBlank()) return
        aiSettingsStore.setHuggingFaceToken(token)
        _uiState.update { it.copy(hasHfToken = true, hfTokenInput = "", errorMessage = null) }
    }

    fun clearHfToken() {
        aiSettingsStore.clearHuggingFaceToken()
        _uiState.update { it.copy(hasHfToken = false) }
    }

    fun downloadModel() {
        if (!deviceAiCapability.isOnDeviceAiSupported()) {
            _uiState.update { it.copy(isDeviceCapable = false, errorMessage = "Il dispositivo non ha RAM sufficiente per il modello IA.") }
            return
        }
        if (aiSettingsStore.huggingFaceToken().isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "Inserisci il tuo token HuggingFace prima di scaricare il modello.") }
            return
        }
        if (modelManager.availableStorageBytes() < AiModelConfig.MODEL_SIZE_BYTES) {
            _uiState.update { it.copy(errorMessage = "Spazio insufficiente per scaricare il modello IA.") }
            return
        }
        _uiState.update { it.copy(downloadProgress = 0f, errorMessage = null) }
        modelDownloadScheduler.enqueueDownload()
    }

    fun deleteModel() {
        viewModelScope.launch {
            engine.releaseAndDelete()
            _uiState.update { it.copy(isModelDownloaded = false, answer = null) }
        }
    }

    fun ask(regionId: String) {
        val state = _uiState.value
        val question = state.question.trim()
        if (question.isBlank() || state.isThinking) return

        viewModelScope.launch {
            _uiState.update { it.copy(isThinking = true, errorMessage = null) }
            try {
                val answer = travelAssistant.ask(regionId, question, state.mode)
                _uiState.update { it.copy(isThinking = false, answer = answer) }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isThinking = false, errorMessage = "Non sono riuscito a generare una risposta.")
                }
            }
        }
    }
}
