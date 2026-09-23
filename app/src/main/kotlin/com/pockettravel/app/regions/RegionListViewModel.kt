package com.pockettravel.app.regions

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.app.R
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.AppUpdateCheckScheduler
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.feature.ai.LlmModelUpdateCheckScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.Normalizer
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RegionStatus { NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE }

data class RegionUiItem(
    val regionId: String,
    val displayName: String,
    val sizeBytes: Long,
    val status: RegionStatus,
    val continent: String? = null,
)

data class RegionListUiState(
    val items: List<RegionUiItem> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    // Catalogo irraggiungibile: sostituisce l'elenco (non c'e' nulla da mostrare).
    @StringRes val loadError: Int? = null,
    // Messaggio una tantum (es. spazio insufficiente): Snackbar, poi onMessageShown().
    @StringRes val message: Int? = null,
)

private data class LoadStatus(
    val isLoading: Boolean = true,
    @StringRes val loadError: Int? = null,
    @StringRes val message: Int? = null,
)

@HiltViewModel
class RegionListViewModel @Inject constructor(
    private val manifestClient: ManifestClient,
    private val regionRepository: RegionRepository,
    private val regionSyncScheduler: RegionSyncScheduler,
    private val appUpdateCheckScheduler: AppUpdateCheckScheduler,
    private val llmModelUpdateCheckScheduler: LlmModelUpdateCheckScheduler,
) : ViewModel() {

    private val manifestRegions = MutableStateFlow<List<RegionManifestEntry>>(emptyList())
    private val status = MutableStateFlow(LoadStatus())
    private val query = MutableStateFlow("")

    val uiState = combine(
        manifestRegions,
        regionRepository.observeInstalled(),
        status,
        query,
    ) { remoteRegions, installed, currentStatus, currentQuery ->
        val installedByRegion = installed.associateBy { it.regionId }
        val items = remoteRegions
            .filter { matchesQuery(it.displayName, currentQuery) }
            .map { remote ->
                val local = installedByRegion[remote.regionId]
                val regionStatus = when {
                    local == null -> RegionStatus.NOT_INSTALLED
                    local.version != remote.version -> RegionStatus.UPDATE_AVAILABLE
                    else -> RegionStatus.INSTALLED
                }
                RegionUiItem(remote.regionId, remote.displayName, remote.sizeBytes, regionStatus, remote.continent)
            }
        RegionListUiState(
            items = items,
            query = currentQuery,
            isLoading = currentStatus.isLoading,
            loadError = currentStatus.loadError,
            message = currentStatus.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RegionListUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            status.update { it.copy(isLoading = true) }
            try {
                manifestRegions.value = manifestClient.fetchManifest().regions
                status.update { it.copy(loadError = null) }
            } catch (_: Exception) {
                status.update { it.copy(loadError = R.string.regions_load_error) }
            } finally {
                status.update { it.copy(isLoading = false) }
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

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    fun download(regionId: String) {
        val entry = manifestRegions.value.firstOrNull { it.regionId == regionId } ?: return
        if (regionRepository.availableStorageBytes() < entry.sizeBytes) {
            status.update { it.copy(message = R.string.regions_not_enough_space) }
            return
        }
        regionSyncScheduler.enqueueDownload(entry)
    }

    fun onMessageShown() {
        status.update { it.copy(message = null) }
    }

    fun delete(regionId: String) {
        viewModelScope.launch { regionRepository.remove(regionId) }
    }

    fun observeDownloadProgress(regionId: String): Flow<WorkInfo?> =
        regionSyncScheduler.observeDownload(regionId)
}

// Ricerca senza distinzione di maiuscole e accenti ("cina" trova "Cina", "sao" trova "São Tomé").
internal fun matchesQuery(displayName: String, query: String): Boolean {
    if (query.isBlank()) return true
    return displayName.foldForSearch().contains(query.trim().foldForSearch())
}

private fun String.foldForSearch(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
