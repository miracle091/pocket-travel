package com.pockettravel.app.regions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.RegionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Prima di questo, RegionHubScreen mostrava il regionId grezzo (es. "italia") nella TopAppBar
// invece del nome regione reale.
@HiltViewModel
class RegionHubViewModel @Inject constructor(
    private val regionRepository: RegionRepository,
    private val recentRegionPreferences: RecentRegionPreferences,
) : ViewModel() {

    private val _displayName = MutableStateFlow<String?>(null)
    val displayName: StateFlow<String?> = _displayName.asStateFlow()

    // true solo dopo che il caricamento ha escluso la regione dal database (mai installata, o
    // installata in una sessione precedente e poi eliminata) — RegionHubScreen ci naviga via in
    // automatico invece di mostrare guida/mappa vuote per un regionId ormai inesistente. Serve
    // soprattutto quando questa schermata e' la rotta di avvio dell'app (vedi StartDestinationViewModel,
    // basata sull'ultima regione aperta secondo RecentRegionPreferences, non su cio' che e' ancora
    // davvero installato).
    private val _regionMissing = MutableStateFlow(false)
    val regionMissing: StateFlow<Boolean> = _regionMissing.asStateFlow()

    fun load(regionId: String) {
        recentRegionPreferences.setLastRegionId(regionId)
        viewModelScope.launch {
            val name = regionRepository.displayName(regionId)
            _displayName.value = name
            _regionMissing.value = name == null
        }
    }
}
