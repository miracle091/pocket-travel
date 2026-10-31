package com.pockettravel.feature.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.core.data.ConversationRepository
import com.pockettravel.core.data.GuideSourceTarget
import com.pockettravel.core.data.SavedConversation
import com.pockettravel.core.data.WEATHER_MIN_REFRESH_MILLIS
import com.pockettravel.core.data.crypto.HistoryKeys
import com.pockettravel.core.data.crypto.HistoryReader
import com.pockettravel.core.data.crypto.HistoryUnreadableException
import com.pockettravel.core.data.currentGuidesLanguage
import com.pockettravel.core.sync.AppStatusClient
import com.pockettravel.core.sync.OnlineModelEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.crypto.Cipher
import javax.inject.Inject

data class AiUiState(
    val mode: AiEngineMode,
    val isDeviceCapable: Boolean,
    val availableModels: List<LlmModelDefinition> = emptyList(),
    // Modelli della lista della fascia sopra quella del dispositivo: si scelgono con l'avviso "puo' essere lento".
    val modelsNeedingMoreRam: Set<String> = emptySet(),
    val selectedModelId: String = "",
    val isModelDownloaded: Boolean,
    // Modelli gia' sul dispositivo: se quello scelto non c'e', se ne propone uno di questi.
    val downloadedModelIds: Set<String> = emptySet(),
    val downloadProgress: Float? = null,
    val isApiKeyConfigured: Boolean,
    val onlineProvider: OnlineProvider = OnlineProvider.CHATGPT,
    // Scelta per il servizio corrente: "" = Automatico, altrimenti il nome del modello.
    val onlineModelSetting: String = "",
    // Modello in uso per ogni servizio, per la riga "Modello: ..." sotto il suo nome.
    val onlineModels: Map<OnlineProvider, String> = emptyMap(),
    // Modelli proposti per il servizio corrente (OnlineModelCatalog), Automatico per primo, senza quelli che la
    // chiave salvata non vede (OnlineModelAvailability).
    val onlineModelOptions: List<OnlineModelEntry> = emptyList(),
    val apiKeyInput: String = "",
    // Consenso a mandare ai servizi online i dati personali (AiSettingsStore.sharesPersonalData).
    val sharesPersonalData: Boolean = false,
    val benchmarkResult: BenchmarkResult? = null,
    val isBenchmarking: Boolean = false,
    val allBenchmarkResults: List<BenchmarkResult> = emptyList(),
    val showBenchmarkComparison: Boolean = false,
    val question: String = "",
    val isThinking: Boolean = false,
    // Il modello sul dispositivo si sta caricando in memoria (prima domanda dopo l'avvio o un cambio di modello).
    val isModelLoading: Boolean = false,
    // Testo della risposta scritto finora dal modello (con isThinking): vuoto prima del primo token.
    val streamingText: String = "",
    val answer: AssistantAnswer? = null,
    // Il modello che scrive la risposta in fondo (anche mentre arriva), per l'etichetta sotto il testo.
    val answerCredit: ModelCredit? = null,
    val askedQuestion: String? = null,
    // Riquadro di emergenza della domanda in fondo (askedQuestion): mostrato prima della risposta, null se non serve.
    val emergencyCard: EmergencyCardState? = null,
    // Le previsioni della risposta in fondo hanno piu' di WEATHER_MIN_REFRESH_MILLIS: si possono riscaricare con un tocco.
    val canRefreshForecast: Boolean = false,
    @StringRes val errorMessage: Int? = null,
    // Turni della conversazione aperta prima di quello in fondo (askedQuestion e answer).
    val earlierTurns: List<ShownTurn> = emptyList(),
    // La cronologia aperta sopra la schermata; null quando e' chiusa.
    val history: HistoryUiState? = null,
    // L'ultima cancellazione dalla cronologia non e' riuscita: la cronologia mostra l'errore.
    val historyDeleteFailed: Boolean = false,
    // Regione (nome) della conversazione ripresa in sola lettura perche' non piu' installata: niente domande nuove.
    val readOnlyRegion: String? = null,
    // La schermata mostra una conversazione ripresa dalla cronologia cifrata: la finestra e' protetta (FLAG_SECURE).
    val showsSavedContent: Boolean = false,
    // Conversazioni nella cronologia, per "Elimina la cronologia" nelle Impostazioni.
    val savedConversations: Int = 0,
    // "Importa da file" in corso, e il suo ultimo errore (mostrato sotto il pulsante).
    val isImportingModel: Boolean = false,
    @StringRes val importError: Int? = null,
)

/** Un turno gia' concluso della conversazione mostrata; [credit] e' il modello che ha scritto la risposta. */
data class ShownTurn(val askedQuestion: String, val answer: AssistantAnswer, val credit: ModelCredit? = null)

/** Il modello di una risposta: [name] nell'etichetta "Generata da un modello IA", [url] la sua pagina. */
data class ModelCredit(val name: String, val url: String)

/**
 * Il modello che scrive le risposte con lo stato attuale: sul dispositivo quello scelto, online quello in uso per il
 * servizio. Null per un modello che il catalogo non conosce piu'.
 */
internal fun AiUiState.currentModelCredit(): ModelCredit? = when (mode) {
    AiEngineMode.ON_DEVICE -> onDeviceModelCredit(selectedModelId)
    AiEngineMode.ONLINE -> onlineModels[onlineProvider]?.let { ModelCredit(it, onlineProvider.modelsPageUrl) }
}

// La pagina del repository HuggingFace da cui si scarica il file: per i modelli addestrati sulle guide quella di pockettravel.
internal fun onDeviceModelCredit(modelId: String): ModelCredit? =
    LlmModelCatalog.ALL.firstOrNull { it.id == modelId }?.let { ModelCredit(it.displayName, it.url.substringBefore("/resolve/")) }

/** La cronologia delle conversazioni, cifrata: si apre solo dopo la biometria. */
sealed interface HistoryUiState {
    data object Loading : HistoryUiState

    /** Biometria forte non configurata: le conversazioni non si salvano. */
    data object Unavailable : HistoryUiState

    /** Da sbloccare: [cipher] va nel CryptoObject del BiometricPrompt; null dopo un errore del Keystore (solo "Sblocca"). */
    class Locked(val cipher: Cipher?) : HistoryUiState

    /** Biometria cambiata o dati rovinati: le conversazioni salvate non si leggono piu', resta la cancellazione. */
    data object Invalidated : HistoryUiState

    data class Unlocked(val conversations: List<SavedConversation>) : HistoryUiState
}

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
    private val conversation: AssistantConversation,
    private val conversationRepository: ConversationRepository,
    private val historyAvailability: HistoryAvailability,
    private val onlineModelCatalog: OnlineModelCatalog,
    private val onlineModelAvailability: OnlineModelAvailability,
    private val guideCitationResolver: GuideCitationResolver,
    private val emergencyCards: EmergencyCardProvider,
    private val answerKeepAlive: AnswerKeepAlive,
) : ViewModel() {

    // Nomi dei modelli che la chiave salvata vede, per servizio; assente = non letti (si mostrano tutti).
    private val availableOnlineModels = MutableStateFlow<Map<OnlineProvider, Set<String>>>(emptyMap())

    private val _uiState = MutableStateFlow(
        AiUiState(
            // Sotto i 3 GB il motore sul dispositivo non va nemmeno offerto come opzione
            // (DeviceAiCapability.isOnDeviceAiSupported): forzare Online qui evita che
            // un motore salvato in precedenza (o il default ON_DEVICE) resti selezionato su un
            // dispositivo che non puo' comunque usarlo.
            mode = if (deviceAiCapability.isOnDeviceAiSupported()) aiSettingsStore.engineMode() else AiEngineMode.ONLINE,
            isDeviceCapable = deviceAiCapability.isOnDeviceAiSupported(),
            // L'ordine di RamTier (INSUFFICIENTE < RIDOTTA < MINIMO < CONFORTEVOLE < AMPIA < PUNTA) e' significativo
            // qui: un dispositivo vede i modelli delle fasce inferiori e quelli della fascia subito sopra.
            availableModels = LlmModelCatalog.visibleFor(deviceAiCapability.ramTier(), currentGuidesLanguage()),
            modelsNeedingMoreRam = deviceAiCapability.ramTier().let { tier ->
                LlmModelCatalog.ALL.filter { LlmModelCatalog.needsMoreRam(it, tier) }.map { it.id }.toSet()
            },
            selectedModelId = aiSettingsStore.selectedModelId(),
            isModelDownloaded = modelManager.isDownloaded(aiSettingsStore.selectedModelDefinition()),
            downloadedModelIds = modelManager.downloadedModelIds.value,
            isApiKeyConfigured = aiSettingsStore.hasApiKey(),
            onlineProvider = aiSettingsStore.onlineProvider(),
            onlineModelSetting = aiSettingsStore.onlineModelSetting(),
            sharesPersonalData = aiSettingsStore.sharesPersonalData(),
            benchmarkResult = aiSettingsStore.benchmarkResult(aiSettingsStore.selectedModelId()),
            allBenchmarkResults = aiSettingsStore.allBenchmarkResults(),
        ),
    )
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    init {
        // "Ferma" della notifica della risposta in corso (AnswerService).
        viewModelScope.launch {
            answerKeepAlive.stopRequests.collect { cancelAsk() }
        }
        // Elenco dei modelli online: quello pubblicato sostituisce quello dell'APK appena scaricato.
        viewModelScope.launch {
            onlineModelCatalog.list.collect { updateOnlineModels() }
        }
        refreshOnlineModels()
        viewModelScope.launch {
            availableOnlineModels.collect { updateOnlineModels() }
        }
        checkOnlineModelAvailability()
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
        viewModelScope.launch {
            engine.isLoadingModel.collect { loading -> _uiState.update { it.copy(isModelLoading = loading) } }
        }
        viewModelScope.launch {
            conversationRepository.count.collect { count -> _uiState.update { it.copy(savedConversations = count) } }
        }
        // Cronologia svuotata dalle Impostazioni (un altro ViewModel) con una conversazione salvata sulla schermata:
        // i turni dopo non si salverebbero piu', quindi se ne comincia una nuova.
        viewModelScope.launch {
            historyEmptied(conversationRepository.count) { conversation.shownId != null }.collect { newConversation() }
        }
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
            val failureGate = DownloadFailureGate()
            modelDownloadScheduler.observeDownload().collect { workInfo ->
                if (workInfo == null) return@collect
                val reportsFailure = failureGate.reportsFailure(workInfo.state)
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
                    WorkInfo.State.FAILED -> if (reportsFailure) {
                        val message = when (workInfo.outputData.getString(LlmModelDownloadWorker.KEY_FAILURE_REASON)) {
                            LlmModelDownloadWorker.FAILURE_REASON_INTEGRITY ->
                                R.string.ai_error_integrity
                            LlmModelDownloadWorker.FAILURE_REASON_SPACE -> R.string.ai_error_space
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
        _uiState.update { it.copy(mode = mode, answer = null, askedQuestion = null, emergencyCard = null, errorMessage = null) }
        refreshOnlineModels()
        checkOnlineModelAvailability()
    }

    fun onQuestionChanged(value: String) {
        _uiState.update { it.copy(question = limitedQuestion(value)) }
    }

    fun onApiKeyInputChanged(value: String) {
        _uiState.update { it.copy(apiKeyInput = value) }
    }

    fun saveApiKey() {
        val key = _uiState.value.apiKeyInput.trim()
        if (key.isBlank()) return
        aiSettingsStore.setApiKey(key)
        availableOnlineModels.update { it - aiSettingsStore.onlineProvider() }
        _uiState.update { it.copy(isApiKeyConfigured = true, apiKeyInput = "") }
        checkOnlineModelAvailability()
    }

    /** Cambiare servizio cancella la chiave salvata (vedi AiSettingsStore.setOnlineProvider). */
    fun onProviderSelected(provider: OnlineProvider) {
        cancelAsk()
        aiSettingsStore.setOnlineProvider(provider)
        // La chiave e' stata cancellata: i modelli visti con quella non valgono piu'.
        if (!aiSettingsStore.hasApiKey()) availableOnlineModels.value = emptyMap()
        _uiState.update {
            it.copy(onlineProvider = provider, onlineModelSetting = aiSettingsStore.onlineModelSetting(provider), isApiKeyConfigured = aiSettingsStore.hasApiKey(), answer = null)
        }
        updateOnlineModels()
    }

    /** Modello del servizio online: "" = Automatico, altrimenti il nome (uno dell'elenco o uno scritto a mano). */
    fun onOnlineModelChanged(setting: String) {
        val provider = _uiState.value.onlineProvider
        aiSettingsStore.setOnlineModelSetting(provider, setting)
        _uiState.update { it.copy(onlineModelSetting = setting) }
        updateOnlineModels()
    }

    fun onSharesPersonalDataChanged(enabled: Boolean) {
        aiSettingsStore.setSharesPersonalData(enabled)
        _uiState.update { it.copy(sharesPersonalData = enabled) }
    }

    // Solo con il motore Online, l'unico che usa l'elenco.
    private fun refreshOnlineModels() {
        if (_uiState.value.mode != AiEngineMode.ONLINE) return
        viewModelScope.launch {
            try {
                onlineModelCatalog.refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Senza rete o con un file non valido resta l'elenco attuale.
            }
        }
    }

    // Con il motore Online e una chiave salvata: quali modelli proposti il suo account vede davvero. Con il motore
    // sul dispositivo la chiave non si manda, nemmeno per l'elenco.
    private fun checkOnlineModelAvailability() {
        if (_uiState.value.mode != AiEngineMode.ONLINE) return
        val provider = aiSettingsStore.onlineProvider()
        val apiKey = aiSettingsStore.apiKey()?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val available = try {
                onlineModelAvailability.availableModels(provider, apiKey)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            // Chiave tolta o cambiata nel frattempo: quei modelli valgono per un altro account.
            if (aiSettingsStore.onlineProvider() != provider || aiSettingsStore.apiKey() != apiKey) return@launch
            availableOnlineModels.update { it + (provider to available) }
        }
    }

    private fun updateOnlineModels() {
        _uiState.update { state ->
            state.copy(
                onlineModels = OnlineProvider.entries.associateWith { onlineModelCatalog.modelFor(it, aiSettingsStore.onlineModelSetting(it)) },
                onlineModelOptions = visibleModels(onlineModelCatalog.models(state.onlineProvider), availableOnlineModels.value[state.onlineProvider]),
            )
        }
    }

    fun clearApiKey() {
        cancelAsk()
        aiSettingsStore.clearApiKey()
        availableOnlineModels.value = emptyMap()
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
            // Il download in corso è del modello precedente: la percentuale non va mostrata su quello nuovo.
            if (_uiState.value.downloadProgress != null) cancelDownload()
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

    /** Solo per il modello sul dispositivo attualmente scaricato: non ha senso misurare un modello
     *  non ancora presente sul dispositivo. */
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
        modelDownloadScheduler.enqueueDownload(definition.id)
    }

    /** "Annulla" durante il download: il lavoro si ferma, il file parziale resta per riprendere. */
    fun cancelDownload() {
        modelDownloadScheduler.cancelDownload()
        _uiState.update { it.copy(downloadProgress = null) }
    }

    fun deleteModel() {
        // Prima di tutto: la generazione in corso tiene il lock del modello che releaseAndDelete aspetta.
        cancelAsk()
        viewModelScope.launch {
            engine.releaseAndDelete()
            _uiState.update { it.copy(isModelDownloaded = false, answer = null, askedQuestion = null, emergencyCard = null) }
        }
    }

    /** File del modello installato, da condividere con "Invia a un altro telefono"; null se non è scaricato. */
    fun sendableModelFile(definition: LlmModelDefinition): File? =
        modelManager.modelFile(definition).takeIf { modelManager.isDownloaded(definition) }

    /**
     * "Importa da file": [open] apre il file scelto, [displayName] e [sizeBytes] (0 se ignota) vengono dal selettore.
     * Un file riconosciuto si installa come dopo un download e diventa il modello scelto.
     */
    fun importModel(displayName: String?, sizeBytes: Long, open: () -> InputStream?) {
        if (_uiState.value.isImportingModel) return
        cancelAsk()
        _uiState.update { it.copy(isImportingModel = true, importError = null) }
        viewModelScope.launch {
            try {
                engine.release()
                val input = open() ?: throw IOException("File non leggibile")
                val candidates = LlmModelCatalog.ALL + _uiState.value.availableModels
                val definition = modelManager.importModel(input, displayName, sizeBytes, candidates)
                onModelSelected(definition.id)
                _uiState.update { it.copy(isImportingModel = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = when (error) {
                    is ModelNoSpaceException -> R.string.ai_import_no_space
                    is ModelIntegrityException -> R.string.ai_import_damaged
                    is ModelUnknownFileException -> R.string.ai_import_unknown
                    else -> R.string.ai_import_failed
                }
                _uiState.update { it.copy(isImportingModel = false, importError = message) }
            }
        }
    }

    /**
     * La sezione della guida di [regionId] che [citation] cita sotto la risposta [answer]. Null se la fonte è di altro
     * tipo, se la regione della conversazione ripresa non è più installata o se la sezione non c'è più.
     */
    suspend fun resolveGuideSource(regionId: String, citation: String, answer: String): GuideSourceTarget? {
        if (_uiState.value.readOnlyRegion != null) return null
        return try {
            guideCitationResolver.resolve(regionId, citation, answer)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    // Richieste "portami a ..." per il Navigatore: un evento solo, consumato da chi apre la scheda.
    private val _navigationRequests = Channel<NavigationRequest>(Channel.BUFFERED)
    val navigationRequests: Flow<NavigationRequest> = _navigationRequests.receiveAsFlow()

    fun ask(regionId: String) {
        val state = _uiState.value
        val question = state.question.trim()
        if (state.startingTurn(question) == null) return
        // Riconosciuta sul dispositivo con regole, anche con il motore online: destinazione e posizione non escono mai.
        parseNavigationRequest(question)?.let { request ->
            _uiState.update { it.copy(question = "", errorMessage = null) }
            _navigationRequests.trySend(request)
            return
        }

        resumeJob?.cancel()
        forecastJob?.cancel()
        val previous = conversation.previousQuestion
        // Una conversazione ripresa resta nella sua regione, anche aperta dalla scheda IA di un'altra.
        val region = conversation.regionId ?: regionId
        // Subito, prima di ogni attesa: un secondo tocco su Invia trova la domanda gia' in corso.
        _uiState.update { it.startingTurn(question) ?: it }
        askJob = viewModelScope.launch {
            // Pericolo per la vita: solo i numeri da chiamare. Il modello non aiuta e puo' inventare consigli medici.
            if (isLifeThreatening(question)) {
                val emergencyCard = emergencyCards.forQuestion(region, question)
                _uiState.update { it.copy(emergencyCard = emergencyCard, isThinking = false) }
                return@launch
            }
            // Accanto alla ricerca e al modello, non prima: le allerte di una domanda su una catastrofe arrivano da
            // piu' fonti in rete, e il numero da chiamare non aspetta la risposta.
            launch {
                val emergencyCard = emergencyCards.forQuestion(region, question)
                _uiState.update { it.copy(emergencyCard = emergencyCard) }
            }
            collectAnswer(region, question, state.mode, previous) { answer ->
                conversation.onAnswered(state.mode, region, state.selectedModelId, question, answer)
            }
        }
    }

    /**
     * "Aggiorna le previsioni": rifa la domanda in fondo con le previsioni scaricate di nuovo, e la risposta nuova prende
     * il posto di quella vecchia, anche nella cronologia. Solo dopo WEATHER_MIN_REFRESH_MILLIS ([AiUiState.canRefreshForecast]).
     */
    fun refreshForecast(regionId: String) {
        val state = _uiState.value
        val asked = state.askedQuestion ?: return
        val answer = state.answer?.takeIf { state.canRefreshForecast && !state.isThinking } ?: return
        val region = conversation.regionId ?: regionId
        askJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isThinking = true, errorMessage = null, canRefreshForecast = false, answer = null, answerCredit = it.currentModelCredit(), streamingText = "")
            }
            // La domanda gia' unita a quella prima se era un seguito (followUpQuestion): non c'e' un seguito da risolvere.
            collectAnswer(region, answer.question ?: asked, state.mode, previous = null) { refreshed ->
                conversation.onReplaced(region, state.selectedModelId, asked, refreshed)
            }
        }
    }

    // La risposta in arrivo nella schermata; [onDone] la registra nella conversazione.
    private suspend fun collectAnswer(
        region: String,
        question: String,
        mode: AiEngineMode,
        previous: String?,
        onDone: (AssistantAnswer) -> Unit,
    ) {
        try {
            answerKeepAlive.during {
                travelAssistant.ask(region, question, mode, previous).collect { progress ->
                    when (progress) {
                        is AssistantProgress.Partial -> _uiState.update { it.copy(streamingText = progress.text) }
                        is AssistantProgress.Done -> {
                            _uiState.update { it.copy(isThinking = false, streamingText = "", answer = progress.answer) }
                            onDone(progress.answer)
                            watchForecast(progress.answer)
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Errore a meta': il parziale si scarta, come l'errore sostituisce la risposta nella UI. Senza risposta
            // visibile nemmeno la base dei seguiti.
            conversation.onFailed()
            _uiState.update { it.copy(isThinking = false, streamingText = "", errorMessage = askErrorMessage(error)) }
        }
    }

    // Accende "Aggiorna le previsioni" quando le previsioni della risposta superano il limite per luogo.
    private var forecastJob: Job? = null

    private fun watchForecast(answer: AssistantAnswer) {
        forecastJob?.cancel()
        val updatedAt = answer.forecast?.result?.updatedAtMillis ?: return
        forecastJob = viewModelScope.launch {
            delay(forecastRefreshDelayMillis(updatedAt, System.currentTimeMillis()))
            _uiState.update { it.copy(canRefreshForecast = true) }
        }
    }

    // La domanda in corso (generazione o richiesta online).
    private var askJob: Job? = null

    /** Chiude la conversazione aperta (si archivia) e svuota la schermata per una nuova. */
    fun newConversation() {
        cancelAsk()
        _uiState.update { it.copy(askedQuestion = null, emergencyCard = null, answer = null, errorMessage = null) }
    }

    /**
     * Interrompe la domanda in corso, se c'e': chi la chiama sta per azzerare o cambiare la risposta, e
     * senza questo il testo parziale (o la risposta finale) ricomparirebbe dopo. Con la risposta finisce anche
     * la conversazione: un seguito non si riferisce a una risposta che non si vede piu'.
     */
    private fun cancelAsk() {
        resumeJob?.cancel()
        askJob?.cancel()
        askJob = null
        forecastJob?.cancel()
        conversation.end()
        _uiState.update {
            it.copy(
                isThinking = false,
                streamingText = "",
                earlierTurns = emptyList(),
                readOnlyRegion = null,
                showsSavedContent = false,
                canRefreshForecast = false,
            )
        }
    }

    // La cronologia sbloccata: vive solo mentre la cronologia e' aperta.
    private var historyReader: HistoryReader? = null

    // Ogni apertura e chiusura della cronologia ne fa una nuova: un lavoro di quella prima non ne cambia piu' lo stato.
    private var historySession = 0

    // La ripresa in corso: una domanda o la fine della conversazione la annullano.
    private var resumeJob: Job? = null

    /** Apre la cronologia: senza biometria spiega perche' non c'e', altrimenti prepara lo sblocco. */
    fun openHistory() {
        historyReader = null
        val session = ++historySession
        if (!historyAvailability.isAvailable()) {
            _uiState.update { it.copy(history = HistoryUiState.Unavailable) }
            return
        }
        _uiState.update { it.copy(history = HistoryUiState.Loading, historyDeleteFailed = false) }
        viewModelScope.launch {
            val unlock = try {
                conversationRepository.prepareUnlock()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Errore del Keystore (ProviderException): niente prompt, resta "Sblocca" per riprovare.
                null
            }
            val history = when (unlock) {
                null -> HistoryUiState.Locked(cipher = null)
                HistoryKeys.Unlock.Empty -> HistoryUiState.Unlocked(emptyList())
                HistoryKeys.Unlock.Invalidated -> HistoryUiState.Invalidated
                is HistoryKeys.Unlock.Ready -> HistoryUiState.Locked(unlock.cipher)
            }
            updateHistory(session, history)
        }
    }

    /** Biometria riuscita: [authenticatedCipher] e' il Cipher del CryptoObject restituito dal BiometricPrompt. */
    fun onHistoryUnlocked(authenticatedCipher: Cipher) {
        val session = historySession
        updateHistory(session, HistoryUiState.Loading)
        viewModelScope.launch {
            val reader = try {
                conversationRepository.completeUnlock(authenticatedCipher)
            } catch (error: CancellationException) {
                throw error
            } catch (_: HistoryUnreadableException) {
                // Chiave del Keystore cambiata o dati rovinati: riprovare darebbe lo stesso errore.
                updateHistory(session, HistoryUiState.Invalidated)
                return@launch
            } catch (_: Exception) {
                // Errore del Keystore: si puo' riprovare.
                updateHistory(session, HistoryUiState.Locked(cipher = null))
                return@launch
            }
            // Cronologia chiusa (o riaperta) durante lo sblocco: la chiave non resta in memoria.
            if (session != historySession || _uiState.value.history == null) return@launch
            historyReader = reader
            showHistoryList(session, reader)
        }
    }

    fun closeHistory() {
        historyReader = null
        historySession++
        _uiState.update { it.copy(history = null, historyDeleteFailed = false) }
    }

    /**
     * Riprende una conversazione della cronologia al posto di quella aperta: i suoi turni tornano sulla schermata e la
     * domanda dopo la continua. Se non c'e' piu', la conversazione aperta resta com'e'.
     */
    fun resumeConversation(id: String) {
        val reader = historyReader ?: return
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            val resumed = conversation.resume(reader, id)
            closeHistory()
            if (resumed == null) return@launch
            askJob?.cancel()
            askJob = null
            val shown = resumed.turns.map { turn ->
                ShownTurn(
                    turn.askedQuestion,
                    AssistantAnswer(turn.answer, turn.citations, turn.showOfficialSourceBanner, turn.officialSourceUrl, turn.question),
                    onDeviceModelCredit(turn.modelId),
                )
            }
            _uiState.update {
                it.copy(
                    isThinking = false,
                    streamingText = "",
                    earlierTurns = shown.dropLast(1),
                    askedQuestion = shown.lastOrNull()?.askedQuestion,
                    emergencyCard = null,
                    answer = shown.lastOrNull()?.answer,
                    answerCredit = shown.lastOrNull()?.credit,
                    errorMessage = null,
                    readOnlyRegion = conversation.readOnlyRegionName,
                    showsSavedContent = conversation.showsSavedContent,
                )
            }
        }
    }

    /** Cancella una conversazione dalla cronologia; se e' quella aperta, la schermata si svuota. */
    fun deleteConversation(id: String) {
        val reader = historyReader ?: return
        val session = historySession
        if (conversation.shownId == id) newConversation()
        viewModelScope.launch {
            if (deleteOrReport { conversationRepository.delete(id) }) showHistoryList(session, reader)
        }
    }

    /** Cancella tutta la cronologia, senza sblocco (Impostazioni, o cronologia non piu' leggibile). */
    fun deleteHistory() {
        val session = historySession
        newConversation()
        viewModelScope.launch {
            if (deleteOrReport { conversationRepository.deleteAll() }) updateHistory(session, HistoryUiState.Unlocked(emptyList()))
        }
    }

    // Esegue una cancellazione; se non riesce lo segna nello stato (la cronologia resta com'e') e restituisce false.
    private suspend fun deleteOrReport(delete: suspend () -> Unit): Boolean {
        _uiState.update { it.copy(historyDeleteFailed = false) }
        return try {
            delete()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _uiState.update { it.copy(historyDeleteFailed = true) }
            false
        }
    }

    private suspend fun showHistoryList(session: Int, reader: HistoryReader) {
        updateHistory(session, HistoryUiState.Unlocked(conversationRepository.list(reader)))
    }

    private fun updateHistory(session: Int, history: HistoryUiState) {
        _uiState.update { if (session != historySession || it.history == null) it else it.copy(history = history) }
    }

    override fun onCleared() {
        conversation.end()
    }
}

/**
 * Emette quando le conversazioni salvate passano da qualcuna a zero mentre [shown] dice che la schermata ne mostra una
 * salvata. Il primo valore e' solo il punto di partenza: all'avvio e per una conversazione appena cominciata (ancora
 * non scritta) un conteggio a zero non e' uno svuotamento.
 */
internal fun historyEmptied(counts: Flow<Int>, shown: () -> Boolean): Flow<Unit> = flow {
    var previous: Int? = null
    counts.collect { count ->
        val before = previous
        previous = count
        if ((before ?: 0) > 0 && count == 0 && shown()) emit(Unit)
    }
}

/** Quanto manca perche' le previsioni scaricate a [updatedAtMillis] si possano riscaricare; 0 se si puo' gia'. */
internal fun forecastRefreshDelayMillis(updatedAtMillis: Long, nowMillis: Long): Long =
    (updatedAtMillis + WEATHER_MIN_REFRESH_MILLIS - nowMillis).coerceAtLeast(0)

/**
 * Lo stato all'invio di [question], o null se la domanda non puo' partire: vuota, con un'altra in corso o in una
 * conversazione aperta in sola lettura. Il turno concluso sale sopra la domanda nuova.
 */
internal fun AiUiState.startingTurn(question: String): AiUiState? {
    if (question.isBlank() || isThinking || readOnlyRegion != null) return null
    val done = askedQuestion?.let { asked -> answer?.let { ShownTurn(asked, it, answerCredit) } }
    return copy(
        answerCredit = currentModelCredit(),
        isThinking = true,
        errorMessage = null,
        earlierTurns = earlierTurns + listOfNotNull(done),
        askedQuestion = question,
        emergencyCard = null,
        canRefreshForecast = false,
        question = "",
        answer = null,
        streamingText = "",
    )
}

/** Messaggio per un errore di [AiAssistantViewModel.ask]: il modello online sconosciuto ha il suo, il resto quello generico. */
@StringRes
internal fun askErrorMessage(error: Exception): Int = when (error) {
    is OnlineModelNotFoundException -> R.string.ai_error_model
    is OnlineApiKeyRejectedException -> R.string.ai_error_key_rejected
    else -> R.string.ai_error_answer
}

/**
 * Lunghezza massima della domanda: circa il doppio della domanda piu' lunga dei dataset di addestramento (165 caratteri),
 * abbastanza per una domanda dettata con i dettagli. Un testo incollato piu' lungo rallenta la risposta e la peggiora:
 * i modelli non hanno mai visto domande cosi', e una domanda di seguito si unisce alla precedente.
 */
internal const val MAX_QUESTION_CHARS = 300

/** Da quanti caratteri il campo della domanda mostra il contatore. */
internal const val QUESTION_COUNTER_FROM = 250

/** [value] entro [MAX_QUESTION_CHARS], senza spezzare un carattere fatto di due unita' (un'emoji). */
internal fun limitedQuestion(value: String): String {
    if (value.length <= MAX_QUESTION_CHARS) return value
    val end = if (Character.isHighSurrogate(value[MAX_QUESTION_CHARS - 1])) MAX_QUESTION_CHARS - 1 else MAX_QUESTION_CHARS
    return value.take(end)
}
