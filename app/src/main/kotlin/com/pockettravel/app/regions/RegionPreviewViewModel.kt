package com.pockettravel.app.regions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.GuidesInstaller
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.guidesChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RegionPreviewState {
    data object Loading : RegionPreviewState
    data object Ready : RegionPreviewState
    data object Error : RegionPreviewState
}

// Il download completo non e' qui: parte dall'elenco regioni (RegionListViewModel), che prepara la voce del manifest
// con civici, zona e reti dei mezzi pubblici e controlla lo spazio libero.
@HiltViewModel
class RegionPreviewViewModel @Inject constructor(
    private val manifestClient: ManifestClient,
    private val guidesInstaller: GuidesInstaller,
    private val regionRepository: RegionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<RegionPreviewState>(RegionPreviewState.Loading)
    val state: StateFlow<RegionPreviewState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = RegionPreviewState.Loading
            try {
                // Le guide di tutte le nazioni sono un solo pacchetto: se gia' installato non serve scaricare nulla.
                if (regionRepository.installedGuidesVersion() == null) {
                    manifestClient.fetchManifest().guidesChoice().let { guidesInstaller.install(it.entry, it.installedVersion) }
                }
                _state.value = RegionPreviewState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = RegionPreviewState.Error
            }
        }
    }
}
