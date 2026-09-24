package com.pockettravel.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.isHiddenOnMap
import com.pockettravel.core.data.poiCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// Holder minimo per far arrivare OfflineTileSource (fornito da RouteEngineModule) a MapScreen
// tramite hiltViewModel(), come ogni altra dipendenza di questo progetto raggiunge una
// composable — MapScreen stessa resta stateless, nessuna logica in piu' qui.
@HiltViewModel
class MapRouteViewModel @Inject constructor(
    val tileSource: OfflineTileSource,
    private val poiRepository: PoiRepository,
) : ViewModel() {
    private val _pins = MutableStateFlow<List<MapPin>>(emptyList())
    val pins: StateFlow<List<MapPin>> = _pins.asStateFlow()

    fun loadPins(regionId: String) {
        viewModelScope.launch {
            _pins.value = poiRepository.forRegion(regionId).filterNot { it.isHiddenOnMap() }.map { poi ->
                MapPin(poi.id.toString(), poi.name, poi.latitude, poi.longitude, poi.poiCategory(), poi.phone)
            }
        }
    }
}
