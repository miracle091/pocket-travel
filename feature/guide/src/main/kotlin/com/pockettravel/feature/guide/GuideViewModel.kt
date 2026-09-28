package com.pockettravel.feature.guide

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.CityRepository
import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.EmergencyNumbersRepository
import com.pockettravel.core.data.GuideRepository
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.poi.PoiCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GuideUiState(
    val sections: List<GuideSection> = emptyList(),
    val emergencyNumbers: EmergencyNumbers? = null,
    // Per i fatti rapidi: valuta e lingua dal paese, mezzi di trasporto dai POI della regione.
    val countryCode: String? = null,
    val transportCounts: Map<PoiCategory, Int> = emptyMap(),
    // La regione non ha un numero di emergenza centralizzato: la scheda lo dice al posto dei numeri.
    val noCentralEmergencyNumber: Boolean = false,
    // Nomi delle citta' della regione (CityRepository.citiesFor): entry point "Città" nascosto se vuoto.
    val cities: List<String> = emptyList(),
    val isLoading: Boolean = true,
    @StringRes val loadError: Int? = null,
)

data class CityGuideUiState(
    val sections: List<CitySection> = emptyList(),
    val isLoading: Boolean = true,
    @StringRes val loadError: Int? = null,
)

@HiltViewModel
class GuideViewModel @Inject constructor(
    private val guideRepository: GuideRepository,
    private val emergencyNumbersRepository: EmergencyNumbersRepository,
    private val cityRepository: CityRepository,
    private val regionRepository: RegionRepository,
    private val poiRepository: PoiRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GuideUiState())
    val uiState: StateFlow<GuideUiState> = _uiState.asStateFlow()

    private val _cityUiState = MutableStateFlow(CityGuideUiState())
    val cityUiState: StateFlow<CityGuideUiState> = _cityUiState.asStateFlow()

    // regionId non e' costruito nella ViewModel (nessun uso di SavedStateHandle in questo
    // progetto): arriva a runtime dalla composable, come AiAssistantViewModel.ask(regionId) —
    // stesso pattern, per coerenza di stile.
    private var loadedForRegionId: String? = null
    private var citiesJob: Job? = null
    private var loadedCityKey: Pair<String, String>? = null

    fun load(regionId: String) {
        if (loadedForRegionId == regionId) return
        loadedForRegionId = regionId
        citiesJob?.cancel()
        citiesJob = viewModelScope.launch {
            cityRepository.citiesFor(regionId).collect { cities ->
                _uiState.update { it.copy(cities = cities) }
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null) }
            try {
                val sections = guideRepository.sectionsFor(regionId)
                val emergencyNumbers = emergencyNumbersRepository.forRegion(regionId)
                val noCentralEmergencyNumber = emergencyNumbers == null && emergencyNumbersRepository.hasNoCentralNumber(regionId)
                val countryCode = regionRepository.installed(regionId)?.countryCode
                val transportCounts = poiRepository.transportCounts(regionId)
                _uiState.update {
                    it.copy(
                        sections = sections,
                        emergencyNumbers = emergencyNumbers,
                        countryCode = countryCode,
                        transportCounts = transportCounts,
                        noCentralEmergencyNumber = noCentralEmergencyNumber,
                        isLoading = false,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                loadedForRegionId = null
                _uiState.update { it.copy(isLoading = false, loadError = R.string.guide_load_error) }
            }
        }
    }

    fun loadCity(regionId: String, city: String) {
        val key = regionId to city
        if (loadedCityKey == key) return
        loadedCityKey = key
        _cityUiState.value = CityGuideUiState()
        viewModelScope.launch {
            try {
                val sections = cityRepository.sectionsFor(regionId, city)
                _cityUiState.update { it.copy(sections = sections, isLoading = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                loadedCityKey = null
                _cityUiState.update { it.copy(isLoading = false, loadError = R.string.guide_city_load_error) }
            }
        }
    }

    // Chiamata quando si torna alla lista citta': la prossima loadCity() ricarica sempre, invece
    // di mostrare per un istante le sezioni della citta' lasciata.
    fun clearCity() {
        loadedCityKey = null
        _cityUiState.value = CityGuideUiState()
    }
}
