package com.pockettravel.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.hasName
import com.pockettravel.core.data.isHiddenOnMap
import com.pockettravel.core.data.poiCategory
import com.pockettravel.core.poi.PoiCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// Holder minimo per far arrivare OfflineTileSource (fornito da RouteEngineModule), i segnalini e i
// filtri salvati a MapScreen tramite hiltViewModel(), come ogni altra dipendenza di questo progetto
// raggiunge una composable — MapScreen stessa resta stateless.
@HiltViewModel
class MapRouteViewModel @Inject constructor(
    val tileSource: OfflineTileSource,
    private val poiRepository: PoiRepository,
    private val filterPreferences: MapFilterPreferences,
    usageModePreferences: UsageModePreferences,
) : ViewModel() {
    val usageMode: StateFlow<UsageMode?> = usageModePreferences.mode
    val hiddenCategories: StateFlow<Set<PoiCategory>> = filterPreferences.hiddenCategories

    fun setHiddenCategories(categories: Set<PoiCategory>) = filterPreferences.setHidden(categories)

    private val _pins = MutableStateFlow<List<MapPin>>(emptyList())
    val pins: StateFlow<List<MapPin>> = _pins.asStateFlow()

    fun loadPins(regionId: String) {
        viewModelScope.launch {
            // I POI extra li ha scaricati l'utente apposta: si mostrano anche se di solito nascosti.
            _pins.value = poiRepository.forRegion(regionId).filter { it.extra || !it.isHiddenOnMap() }.map { poi ->
                MapPin(poi.id.toString(), poi.name.takeIf { poi.hasName() }, poi.latitude, poi.longitude, poi.poiCategory(), poi.phone, poi.wheelchair)
            }
        }
    }
}
