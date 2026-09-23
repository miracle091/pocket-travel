package com.pockettravel.app.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GuidesDownloadUiState(
    val isInstalled: Boolean = false,
    val isDownloading: Boolean = false,
    // Dal manifest, null finche' non e' stato letto (o se non e' raggiungibile).
    val downloadBytes: Long? = null,
)

/** Passo "Guide di tutte le nazioni" dell'onboarding: download facoltativo del pacchetto guide. */
@HiltViewModel
class GuidesDownloadViewModel @Inject constructor(
    private val manifestClient: ManifestClient,
    regionRepository: RegionRepository,
    private val regionSyncScheduler: RegionSyncScheduler,
) : ViewModel() {

    private val downloadBytes = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<GuidesDownloadUiState> = combine(
        regionRepository.observeInstalledGuides(),
        regionSyncScheduler.observeGuidesSync(),
        downloadBytes,
    ) { installed, work, bytes ->
        GuidesDownloadUiState(
            isInstalled = installed != null,
            // Solo RUNNING: una richiesta automatica in coda puo' restare ENQUEUED in attesa del Wi-Fi.
            isDownloading = installed == null && work?.state == WorkInfo.State.RUNNING,
            downloadBytes = bytes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuidesDownloadUiState())

    init {
        viewModelScope.launch {
            downloadBytes.value = runCatching { manifestClient.fetchManifest().guides.file.sizeBytes }.getOrNull()
        }
    }

    fun download() = regionSyncScheduler.enqueueGuidesSync(onlyOnWifi = false)
}
