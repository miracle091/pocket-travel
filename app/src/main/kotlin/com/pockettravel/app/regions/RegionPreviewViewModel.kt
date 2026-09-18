package com.pockettravel.app.regions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionGuidePreviewDownloader
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RegionPreviewState {
    data object Loading : RegionPreviewState
    data object Ready : RegionPreviewState
    data class Error(val message: String) : RegionPreviewState
}

@HiltViewModel
class RegionPreviewViewModel @Inject constructor(
    private val manifestClient: ManifestClient,
    private val guidePreviewDownloader: RegionGuidePreviewDownloader,
    private val regionSyncScheduler: RegionSyncScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow<RegionPreviewState>(RegionPreviewState.Loading)
    val state: StateFlow<RegionPreviewState> = _state.asStateFlow()

    private var manifestEntry: RegionManifestEntry? = null

    fun load(regionId: String) {
        viewModelScope.launch {
            _state.value = RegionPreviewState.Loading
            try {
                val entry = manifestClient.fetchManifest().regions.first { it.regionId == regionId }
                manifestEntry = entry
                guidePreviewDownloader.preview(entry)
                _state.value = RegionPreviewState.Ready
            } catch (_: Exception) {
                _state.value = RegionPreviewState.Error("Impossibile caricare l'anteprima. Riprova più tardi.")
            }
        }
    }

    /** Avvia il download completo (mappa + routing inclusi) dal pacchetto gia' individuato da load(). */
    fun downloadFull() {
        manifestEntry?.let(regionSyncScheduler::enqueueDownload)
    }
}
