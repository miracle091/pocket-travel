package com.pockettravel.feature.guide

import com.pockettravel.core.data.LastKnownPosition
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.CityRepository
import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.DiplomaticMission
import com.pockettravel.core.data.DiplomaticMissionRepository
import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.EmergencyNumbersRepository
import com.pockettravel.core.data.GuideRepository
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.MainCity
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.Poi
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.poi.PoiCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GuideUiState(
    val sections: List<GuideSection> = emptyList(),
    val emergencyNumbers: EmergencyNumbers? = null,
    // Per i fatti rapidi: valuta e lingua dal paese, mezzi di trasporto dai POI della regione.
    val countryCode: String? = null,
    val transportCounts: Map<PoiCategory, Int> = emptyMap(),
    // Paese di chi viaggia se diverso da quello della regione (null altrimenti), con le sue
    // ambasciate e i suoi consolati nella regione.
    val embassiesCountry: String? = null,
    val embassies: List<Poi> = emptyList(),
    // Le rappresentanze Wikidata dello stesso paese nel paese della regione: la scheda le fonde con i POI OSM.
    val missions: List<DiplomaticMission> = emptyList(),
    // Ultima posizione nota del telefono (null senza permesso): le rappresentanze piu' vicine in cima.
    val position: Pair<Double, Double>? = null,
    // La regione non ha un numero di emergenza centralizzato: la scheda lo dice al posto dei numeri.
    val noCentralEmergencyNumber: Boolean = false,
    // Nomi delle citta' della regione (CityRepository.citiesFor): entry point "Citta'" nascosto se vuoto.
    val cities: List<String> = emptyList(),
    // Le citta' principali per abitanti (CityRepository.mainCitiesFor), in evidenza nella scheda Citta'.
    val mainCities: List<MainCity> = emptyList(),
    // Meteo della posizione (se dentro la regione) o della capitale; null finche' non c'e' o senza rete e cache.
    val weather: PlaceWeather? = null,
    val isLoading: Boolean = true,
    @StringRes val loadError: Int? = null,
)

data class CityGuideUiState(
    val sections: List<CitySection> = emptyList(),
    val weather: PlaceWeather? = null,
    val isLoading: Boolean = true,
    @StringRes val loadError: Int? = null,
)

/** Meteo di un luogo: [place] e' il nome della citta', null per la posizione del telefono. */
data class PlaceWeather(val place: String?, val result: WeatherResult)

@HiltViewModel
class GuideViewModel @Inject constructor(
    private val guideRepository: GuideRepository,
    private val emergencyNumbersRepository: EmergencyNumbersRepository,
    private val cityRepository: CityRepository,
    private val regionRepository: RegionRepository,
    private val poiRepository: PoiRepository,
    private val diplomaticMissionRepository: DiplomaticMissionRepository,
    private val nationalityPreferences: NationalityPreferences,
    private val lastKnownPosition: LastKnownPosition,
    private val weatherRepository: WeatherRepository,
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
    private var loadJob: Job? = null
    private var embassiesJob: Job? = null
    private var weatherJob: Job? = null
    private var cityWeatherJob: Job? = null
    private var loadedCityKey: Pair<String, String>? = null

    fun load(regionId: String) {
        if (loadedForRegionId == regionId) return
        loadedForRegionId = regionId
        citiesJob?.cancel()
        citiesJob = viewModelScope.launch {
            combine(cityRepository.citiesFor(regionId), cityRepository.mainCitiesFor(regionId, MAIN_CITIES)) { cities, main ->
                cities to main
            }.collect { (cities, main) ->
                _uiState.update { it.copy(cities = cities, mainCities = main) }
            }
        }
        embassiesJob?.cancel()
        embassiesJob = viewModelScope.launch {
            val regionCountry = regionRepository.installed(regionId)?.countryCode
            nationalityPreferences.nationality.collect { nationality ->
                // Il paese della regione e' minuscolo (manifest), la nazionalita' maiuscola.
                val country = nationality?.takeIf { !it.equals(regionCountry, ignoreCase = true) }
                val embassies = country?.let { poiRepository.embassiesOf(regionId, it) }.orEmpty()
                // Senza paese per la regione (manifest non ancora letto) niente rappresentanze Wikidata.
                val missions = if (country != null && regionCountry != null) {
                    diplomaticMissionRepository.missions(country, regionCountry)
                } else {
                    emptyList()
                }
                val position = if (country != null) lastKnownPosition.get() else null
                _uiState.update { it.copy(embassiesCountry = country, embassies = embassies, missions = missions, position = position) }
            }
        }
        weatherJob?.cancel()
        _uiState.update { it.copy(weather = null) }
        weatherJob = viewModelScope.launch {
            val weather = regionWeather(regionId)
            _uiState.update { it.copy(weather = weather) }
        }
        // Su tablet il ViewModel e' condiviso fra le regioni scelte: il caricamento lento della precedente
        // non deve arrivare dopo e sovrascrivere quella nuova.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null) }
            // Di nuovo a ogni cambio del pacchetto guide: dopo un cambio di lingua le guide giuste arrivano
            // pochi secondi dopo, e la schermata gia' aperta resterebbe nella lingua precedente.
            regionRepository.observeInstalledGuides().map { it?.version }.distinctUntilChanged().collectLatest {
                loadSections(regionId)
            }
        }
    }

    private suspend fun loadSections(regionId: String) {
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

    fun loadCity(regionId: String, city: String) {
        val key = regionId to city
        if (loadedCityKey == key) return
        loadedCityKey = key
        _cityUiState.value = CityGuideUiState()
        cityWeatherJob?.cancel()
        cityWeatherJob = viewModelScope.launch {
            val country = regionRepository.installed(regionId)?.countryCode ?: return@launch
            val result = weatherRepository.weather("city|$country|$city") { weatherRepository.coordinatesOf(city, country) }
            _cityUiState.update { it.copy(weather = result?.let { PlaceWeather(city, it) }) }
        }
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
        cityWeatherJob?.cancel()
        _cityUiState.value = CityGuideUiState()
    }

    // Il meteo della posizione se il telefono e' nella regione (POI del pacchetto attorno a una posizione recente),
    // altrimenti della capitale o, senza, della citta' principale.
    private suspend fun regionWeather(regionId: String): PlaceWeather? = positionWeather(regionId) ?: capitalWeather(regionId)

    private suspend fun positionWeather(regionId: String): PlaceWeather? {
        val position = lastKnownPosition.get(POSITION_MAX_AGE_MILLIS)?.takeIf { (lat, lon) ->
            poiRepository.inBounds(regionId, lat - NEAR_DEGREES, lat + NEAR_DEGREES, lon - NEAR_DEGREES, lon + NEAR_DEGREES, 1).pois.isNotEmpty()
        } ?: return null
        // A Open-Meteo va solo la zona (0,1 gradi, circa 10 km), non il punto preciso: per il meteo basta.
        val area = position.let { (lat, lon) -> lat.roundToTenth() to lon.roundToTenth() }
        // Una voce per zona: senza rete, "Vicino a te" non mostra le previsioni di dove si era ieri nella stessa regione.
        return weatherRepository.weather("position|$regionId|${area.first}|${area.second}") { area }?.let { PlaceWeather(null, it) }
    }

    private suspend fun capitalWeather(regionId: String): PlaceWeather? {
        val country = regionRepository.installed(regionId)?.countryCode
        val city = cityRepository.mainCitiesFor(regionId, MAIN_CITIES).first()
            .let { cities -> cities.firstOrNull { it.capital } ?: cities.firstOrNull() }?.name
        if (country == null || city == null) return null
        return weatherRepository.weather("city|$country|$city") { weatherRepository.coordinatesOf(city, country) }
            ?.let { PlaceWeather(city, it) }
    }
}

// Scorciatoie della scheda Citta' (CitiesEntryCard)
internal const val MAIN_CITIES = 5

// Posizione per il meteo "vicino a te": recente (3 ore) e con POI della regione entro ~5 km.
private const val POSITION_MAX_AGE_MILLIS = 3 * 60 * 60 * 1000L
private const val NEAR_DEGREES = 0.05
private const val TENTHS = 10.0

private fun Double.roundToTenth(): Double = Math.round(this * TENTHS) / TENTHS
