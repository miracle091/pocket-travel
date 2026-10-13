package com.pockettravel.app.updates

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.app.InstalledVersionCode
import com.pockettravel.app.R
import com.pockettravel.app.runCatchingCancellable
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.AiModelManifestEntry
import com.pockettravel.core.sync.AppStatusClient
import com.pockettravel.core.sync.AppVersionEntry
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.core.sync.guidesChoice
import com.pockettravel.core.sync.isAppUpdateAvailable
import com.pockettravel.feature.ai.AiModelUpdates
import com.pockettravel.feature.ai.LlmModelDownloadWorker
import com.pockettravel.feature.map.ConnectivityChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Guide da aggiornare: il peso del pacchetto compresso. */
data class GuidesUpdate(val sizeBytes: Long)

data class UpdatesUiState(
    val appUpdate: AppVersionEntry? = null,
    val guidesUpdate: GuidesUpdate? = null,
    val guidesSyncing: Boolean = false,
    val modelUpdate: AiModelManifestEntry? = null,
    val modelDownloading: Boolean = false,
    // 0..1; null finché il download non ha comunicato il totale.
    val modelProgress: Float? = null,
    // Controllo in corso (all'apertura o con "Controlla ora").
    val isChecking: Boolean = true,
    // L'ultimo controllo non ha raggiunto la rete (updateCheckOffline): senza rete non si puo' dire "Tutto aggiornato".
    val offline: Boolean = false,
    // Messaggio una tantum (Snackbar), poi onMessageShown().
    @StringRes val message: Int? = null,
)

/**
 * Gli aggiornamenti che non sono nazioni: app, guide e modello IA (le nazioni stanno in RegionListViewModel). Vive
 * nella voce RegionsRoute del back stack, come l'elenco: Altro ne mostra il numero sul badge senza aprire la schermata.
 */
@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val installedVersionCode: InstalledVersionCode,
    private val appStatusClient: AppStatusClient,
    private val manifestClient: ManifestClient,
    private val regionRepository: RegionRepository,
    private val regionSyncScheduler: RegionSyncScheduler,
    private val aiModelUpdates: AiModelUpdates,
    private val connectivityChecker: ConnectivityChecker,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpdatesUiState())
    val uiState: StateFlow<UpdatesUiState> = _uiState.asStateFlow()

    // Versione che le guide installate devono avere (dal catalogo) e modelli pubblicati: servono a ricontrollare a fine lavoro.
    private var guidesTargetVersion: String? = null
    private var publishedModels: List<AiModelManifestEntry> = emptyList()

    init {
        refresh()
        viewModelScope.launch {
            regionSyncScheduler.observeGuidesSync().collect { info ->
                _uiState.update { it.copy(guidesSyncing = info.isActive()) }
                if (info?.state == WorkInfo.State.SUCCEEDED) recheckGuides()
            }
        }
        viewModelScope.launch {
            aiModelUpdates.observeDownload().collect { info ->
                val data = info?.progress
                val total = data?.getLong(LlmModelDownloadWorker.KEY_TOTAL_BYTES, 0L) ?: 0L
                val progress = if (total > 0) data?.getLong(LlmModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)?.div(total.toFloat()) else null
                _uiState.update { it.copy(modelDownloading = info.isActive(), modelProgress = progress) }
                if (info?.state == WorkInfo.State.SUCCEEDED) recheckModel()
            }
        }
    }

    /**
     * Rilegge app-status.json (app e modello IA) e `manifest.json` (guide). `manifest.json` è quello scaricato da poco: "Controlla ora"
     * chiama prima RegionListViewModel.checkForUpdatesNow, che prende il lock di ManifestClient prima di questa chiamata, quindi
     * qui arriva quello nuovo. Senza rete restano i valori di prima.
     */
    fun refresh(userRequested: Boolean = false) {
        _uiState.update { it.copy(isChecking = true) }
        viewModelScope.launch {
            try {
                val status = async { runCatchingCancellable { appStatusClient.fetchAppStatus() }.getOrNull() }
                val manifest = async { runCatchingCancellable { manifestClient.recentManifest() }.getOrNull() }
                status.await()?.let { current ->
                    publishedModels = current.aiModels
                    val app = current.appVersion?.takeIf { isAppUpdateAvailable(installedVersionCode.get(), it) }
                    val model = runCatchingCancellable { aiModelUpdates.availableUpdate(current.aiModels) }.getOrNull()
                    _uiState.update { it.copy(appUpdate = app, modelUpdate = model) }
                }
                val offline = updateCheckOffline(appStatusReached = status.await() != null, deviceOnline = connectivityChecker.isOnline())
                // Chiesto con il pulsante: lo si dice anche se la schermata mostra gli aggiornamenti trovati prima.
                _uiState.update { it.copy(offline = offline, message = if (offline && userRequested) R.string.updates_offline else it.message) }
                manifest.await()?.let { current ->
                    val choice = current.guidesChoice()
                    guidesTargetVersion = choice.installedVersion
                    val outdated = regionRepository.installedGuidesVersion() != choice.installedVersion
                    _uiState.update { it.copy(guidesUpdate = if (outdated) GuidesUpdate(choice.entry.downloadFile.sizeBytes) else null) }
                }
            } finally {
                _uiState.update { it.copy(isChecking = false) }
            }
        }
    }

    /** Scarica le guide ora, anche su rete mobile: il pulsante è la scelta dell'utente. */
    fun updateGuides() {
        regionSyncScheduler.enqueueGuidesSync(onlyOnWifi = false)
    }

    fun downloadModel() {
        val update = _uiState.value.modelUpdate ?: return
        if (!aiModelUpdates.download(update)) {
            _uiState.update { it.copy(message = R.string.updates_model_not_enough_space) }
        }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private fun recheckGuides() {
        val target = guidesTargetVersion ?: return
        viewModelScope.launch {
            if (regionRepository.installedGuidesVersion() == target) _uiState.update { it.copy(guidesUpdate = null) }
        }
    }

    private fun recheckModel() {
        if (_uiState.value.modelUpdate == null) return
        viewModelScope.launch {
            val model = runCatchingCancellable { aiModelUpdates.availableUpdate(publishedModels) }.getOrNull()
            _uiState.update { it.copy(modelUpdate = model) }
        }
    }
}

private fun WorkInfo?.isActive() = this?.state == WorkInfo.State.RUNNING || this?.state == WorkInfo.State.ENQUEUED
