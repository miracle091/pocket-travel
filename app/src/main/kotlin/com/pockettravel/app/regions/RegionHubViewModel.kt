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

    fun load(regionId: String) {
        recentRegionPreferences.setLastRegionId(regionId)
        viewModelScope.launch {
            _displayName.value = regionRepository.displayName(regionId)
        }
    }
}
