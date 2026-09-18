package com.pockettravel.app.regions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.AppUpdateCheckScheduler
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.feature.ai.LlmModelUpdateCheckScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class RegionStatus { NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE }

data class RegionUiItem(
    val regionId: String,
    val displayName: String,
    val sizeBytes: Long,
    val status: RegionStatus,
)

data class RegionListUiState(
    val items: List<RegionUiItem> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

@HiltViewModel
class RegionListViewModel @Inject constructor(
    private val manifestClient: ManifestClient,
    private val regionRepository: RegionRepository,
    private val regionSyncScheduler: RegionSyncScheduler,
    private val recentRegionPreferences: RecentRegionPreferences,
    private val appUpdateCheckScheduler: AppUpdateCheckScheduler,
    private val llmModelUpdateCheckScheduler: LlmModelUpdateCheckScheduler,
) : ViewModel() {

    private val manifestRegions = MutableStateFlow<List<RegionManifestEntry>>(emptyList())
    private val isLoading = MutableStateFlow(true)
    private val errorMessage = MutableStateFlow<String?>(null)

    val uiState = combine(
        manifestRegions,
        regionRepository.observeInstalled(),
        isLoading,
        errorMessage,
    ) { remoteRegions, installed, loading, error ->
        val installedByRegion = installed.associateBy { it.regionId }
        val items = remoteRegions.map { remote ->
            val local = installedByRegion[remote.regionId]
            val status = when {
                local == null -> RegionStatus.NOT_INSTALLED
                local.version != remote.version -> RegionStatus.UPDATE_AVAILABLE
                else -> RegionStatus.INSTALLED
            }
            RegionUiItem(remote.regionId, remote.displayName, remote.sizeBytes, status)
        }
        RegionListUiState(items = items, isLoading = loading, errorMessage = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RegionListUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            isLoading.value = true
            try {
                manifestRegions.value = manifestClient.fetchManifest().regions
                errorMessage.value = null
            } catch (_: Exception) {
                errorMessage.value = "Impossibile contattare il catalogo regioni. Riprova più tardi."
            } finally {
                isLoading.value = false
            }
        }
    }

    /** Controllo manuale immediato, in aggiunta a quello periodico: manifest regioni (qui, sincrono)
     *  + versione app e modello IA (in background, notificano se c'e' un aggiornamento). */
    fun checkForUpdatesNow() {
        refresh()
        appUpdateCheckScheduler.checkNow()
        llmModelUpdateCheckScheduler.checkNow()
    }

    fun download(regionId: String) {
        val entry = manifestRegions.value.firstOrNull { it.regionId == regionId } ?: return
        if (regionRepository.availableStorageBytes() < entry.sizeBytes) {
            errorMessage.value = "Spazio insufficiente per scaricare questa regione."
            return
        }
        regionSyncScheduler.enqueueDownload(entry)
    }

    fun delete(regionId: String) {
        viewModelScope.launch { regionRepository.remove(regionId) }
    }

    fun observeDownloadProgress(regionId: String): Flow<WorkInfo?> =
        regionSyncScheduler.observeDownload(regionId)

    fun lastRegionId(): String? = recentRegionPreferences.lastRegionId()
}
