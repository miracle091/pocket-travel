package com.pockettravel.feature.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.core.data.currentGuidesLanguage
import com.pockettravel.core.sync.AppStatusClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiUiState(
    val mode: AiEngineMode,
    val isDeviceCapable: Boolean,
    val availableModels: List<LlmModelDefinition> = emptyList(),
    val selectedModelId: String = "",
    val isModelDownloaded: Boolean,
    // Modelli gia' sul telefono: se quello scelto non c'e', se ne propone uno di questi.
    val downloadedModelIds: Set<String> = emptySet(),
    val downloadProgress: Float? = null,
    val isApiKeyConfigured: Boolean,
    val onlineProvider: OnlineProvider = OnlineProvider.CHATGPT,
    // Scelta per il servizio corrente: "" = Automatico, altrimenti il nome del modello.
    val onlineModelSetting: String = "",
    // Modello in uso per ogni servizio, per la riga "Modello: ..." sotto il suo nome.
    val onlineModels: Map<OnlineProvider, String> = emptyMap(),
    val apiKeyInput: String = "",
    val benchmarkResult: BenchmarkResult? = null,
    val isBenchmarking: Boolean = false,
    val allBenchmarkResults: List<BenchmarkResult> = emptyList(),
    val showBenchmarkComparison: Boolean = false,
    val question: String = "",
    val isThinking: Boolean = false,
    // Testo della risposta scritto finora dal modello (con isThinking): vuoto prima del primo token.
    val streamingText: String = "",
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
    private val appStatusClient: AppStatusClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AiUiState(
            // Sotto i 4 GB il motore on-device non va nemmeno offerta come opzione (vedi
            // messaggio "meno di 4 GB di RAM" in AiAssistantScreen): forzare Online qui evita che
            // un mode salvato in precedenza (o il default ON_DEVICE) resti selezionato su un
            // device che non puo' comunque usarlo.
            mode = if (deviceAiCapability.isOnDeviceAiSupported()) aiSettingsStore.engineMode() else AiEngineMode.ONLINE,
            isDeviceCapable = deviceAiCapability.isOnDeviceAiSupported(),
            // L'ordine di RamTier (INSUFFICIENTE < MINIMO < CONFORTEVOLE < AMPIA) e' significativo
            // qui: un device di fascia superiore vede anche i modelli delle fasce inferiori.
            availableModels = LlmModelCatalog.visibleFor(deviceAiCapability.ramTier(), currentGuidesLanguage()),
            selectedModelId = aiSettingsStore.selectedModelId(),
            isModelDownloaded = modelManager.isDownloaded(aiSettingsStore.selectedModelDefinition()),
            downloadedModelIds = modelManager.downloadedModelIds.value,
            isApiKeyConfigured = aiSettingsStore.hasApiKey(),
            onlineProvider = aiSettingsStore.onlineProvider(),
            onlineModelSetting = aiSettingsStore.onlineModelSetting(),
            onlineModels = OnlineProvider.entries.associateWith { aiSettingsStore.onlineModel(it) },
            benchmarkResult = aiSettingsStore.benchmarkResult(aiSettingsStore.selectedModelId()),
            allBenchmarkResults = aiSettingsStore.allBenchmarkResults(),
        ),
    )
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    init {
        // Il modello scaricato o eliminato da fuori (worker di download, pulizia dei file) aggiorna anche
        // isModelDownloaded, non solo la lista: letto una volta sola in costruzione resterebbe vecchio.
        viewModelScope.launch {
            modelManager.downloadedModelIds.collect { ids ->
                _uiState.update { it.copy(downloadedModelIds = ids, isModelDownloaded = aiSettingsStore.selectedModelId() in ids) }
            }
        }
        // La chiave puo' cambiare anche fuori da questa schermata (es. Impostazioni).
        viewModelScope.launch {
            aiSettingsStore.hasApiKeyFlow.collect { configured -> _uiState.update { it.copy(isApiKeyConfigured = configured) } }
        }
        observeModelDownload()
        // Dimensione mostrata e spazio richiesto come quelli che il download verifichera' (LlmModelDownloadWorker):
        // un modello ricaricato con lo stesso nome puo' pesare diversamente da quanto scritto nell'APK.
        viewModelScope.launch {
            val published = try {
                appStatusClient.fetchAppStatus().aiModels
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            _uiState.update { state -> state.copy(availableModels = state.availableModels.map { it.withPublishedFingerprint(published) }) }
        }
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
        cancelAsk()
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

    /** Cambiare servizio cancella la chiave salvata (vedi AiSettingsStore.setOnlineProvider). */
    fun onProviderSelected(provider: OnlineProvider) {
        cancelAsk()
        aiSettingsStore.setOnlineProvider(provider)
        _uiState.update {
            it.copy(onlineProvider = provider, onlineModelSetting = aiSettingsStore.onlineModelSetting(provider), isApiKeyConfigured = aiSettingsStore.hasApiKey(), answer = null)
        }
    }

    /** Modello del servizio online: "" = Automatico, altrimenti il nome (il piu' capace o uno scritto a mano). */
    fun onOnlineModelChanged(setting: String) {
        val provider = _uiState.value.onlineProvider
        aiSettingsStore.setOnlineModelSetting(provider, setting)
        _uiState.update { it.copy(onlineModelSetting = setting, onlineModels = it.onlineModels + (provider to aiSettingsStore.onlineModel(provider))) }
    }

    fun clearApiKey() {
        cancelAsk()
        aiSettingsStore.clearApiKey()
        _uiState.update { it.copy(isApiKeyConfigured = false, answer = null) }
    }

    /** Cambia il modello selezionato senza scaricarlo: la UI mostra poi il pulsante di download
     *  per quello scelto. */
    fun onModelSelected(modelId: String) {
        cancelAsk()
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

    /** Solo per il modello on-device attualmente scaricato: non ha senso misurare un modello
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
            } catch (error: CancellationException) {
                throw error
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
        // Dimensione pubblicata in app-status.json, se gia' letta (vedi init).
        val sizeBytes = _uiState.value.availableModels.firstOrNull { it.id == definition.id }?.sizeBytes ?: definition.sizeBytes
        if (modelManager.availableStorageBytes() < sizeBytes) {
            _uiState.update { it.copy(errorMessage = R.string.ai_error_space) }
            return
        }
        _uiState.update { it.copy(downloadProgress = 0f, errorMessage = null) }
        modelDownloadScheduler.enqueueDownload()
    }

    fun deleteModel() {
        // Prima di tutto: la generazione in corso tiene il lock del modello che releaseAndDelete aspetta.
        cancelAsk()
        viewModelScope.launch {
            engine.releaseAndDelete()
            _uiState.update { it.copy(isModelDownloaded = false, answer = null, askedQuestion = null) }
        }
    }

    // Richieste "portami a ..." per il Navigatore: un evento solo, consumato da chi apre la scheda.
    private val _navigationRequests = Channel<NavigationRequest>(Channel.BUFFERED)
    val navigationRequests: Flow<NavigationRequest> = _navigationRequests.receiveAsFlow()

    fun ask(regionId: String) {
        val state = _uiState.value
        val question = state.question.trim()
        if (question.isBlank() || state.isThinking) return
        // Riconosciuta sul telefono con regole, anche con il motore online: meta e posizione non escono mai.
        parseNavigationRequest(question)?.let { request ->
            _uiState.update { it.copy(question = "", errorMessage = null) }
            _navigationRequests.trySend(request)
            return
        }

        askJob = viewModelScope.launch {
            _uiState.update { it.copy(isThinking = true, errorMessage = null, askedQuestion = question, question = "", answer = null, streamingText = "") }
            try {
                travelAssistant.ask(regionId, question, state.mode).collect { progress ->
                    when (progress) {
                        is AssistantProgress.Partial -> _uiState.update { it.copy(streamingText = progress.text) }
                        is AssistantProgress.Done -> _uiState.update { it.copy(isThinking = false, streamingText = "", answer = progress.answer) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Errore a meta': il parziale si scarta, come l'errore sostituisce la risposta nella UI.
                _uiState.update { it.copy(isThinking = false, streamingText = "", errorMessage = askErrorMessage(error)) }
            }
        }
    }

    // La domanda in corso (generazione o richiesta online).
    private var askJob: Job? = null

    /**
     * Interrompe la domanda in corso, se c'e': chi la chiama sta per azzerare o cambiare la risposta, e
     * senza questo il testo parziale (o la risposta finale) ricomparirebbe dopo.
     */
    private fun cancelAsk() {
        askJob?.cancel()
        askJob = null
        _uiState.update { it.copy(isThinking = false, streamingText = "") }
    }
}

/** Messaggio per un errore di [AiAssistantViewModel.ask]: il modello online sconosciuto ha il suo, il resto quello generico. */
@StringRes
internal fun askErrorMessage(error: Exception): Int = when (error) {
    is OnlineModelNotFoundException -> R.string.ai_error_model
    is OnlineApiKeyRejectedException -> R.string.ai_error_key_rejected
    else -> R.string.ai_error_answer
}
