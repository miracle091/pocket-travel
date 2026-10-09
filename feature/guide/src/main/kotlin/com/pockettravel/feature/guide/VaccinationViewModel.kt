package com.pockettravel.feature.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.vaccination.PolioCategory
import com.pockettravel.core.data.vaccination.Trip
import com.pockettravel.core.data.vaccination.TripLeg
import com.pockettravel.core.data.vaccination.TripPurpose
import com.pockettravel.core.data.vaccination.VaccinationData
import com.pockettravel.core.data.vaccination.VaccinationPreferences
import com.pockettravel.core.data.vaccination.VaccinationRepository
import com.pockettravel.core.data.vaccination.VaccinationResult
import com.pockettravel.core.data.vaccination.evaluateVaccinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Uno scalo inserito dall'utente: [hours] null = durata non indicata (il motore lo conta per prudenza).
 * [overTwelveHours] ("più di 12 ore") arriva al motore come durata non indicata: un numero fisso (es. 13)
 * nasconderebbe le regole sopra le 24 ore.
 */
data class StopoverInput(
    val id: Int,
    val country: String? = null,
    val hours: Int? = null,
    val overTwelveHours: Boolean = false,
    val leftAirport: Boolean = false,
)

/**
 * Stato del riquadro e della schermata delle vaccinazioni. I paesi sono ISO alpha-2 maiuscoli (come li da' il
 * selettore); [result] e' null quando il percorso non e' valido (partenza mancante, o uguale alla destinazione senza scopo:
 * con lo scopo Hajj il motore vale anche per chi parte dal paese stesso).
 */
data class VaccinationUiState(
    // false: pacchetto guide senza dati vaccinali o paese della regione ignoto, il riquadro non compare.
    val available: Boolean = false,
    val destination: String? = null,
    // Id della regione: alcune sono tutte fuori dall'area della febbre gialla del loro paese (YF_RISK_FREE_REGIONS).
    val destinationRegion: String? = null,
    val departure: String? = null,
    val nationality: String? = null,
    val recentCountries: List<String> = emptyList(),
    val stopovers: List<StopoverInput> = emptyList(),
    val childUnderOne: Boolean = false,
    val childMonths: Int? = null,
    val stayOverFourWeeks: Boolean = false,
    val hajj: Boolean = false,
    // La domanda sulle 4 settimane serve solo se dalla partenza vale l'obbligo polio in uscita.
    val polioRelevant: Boolean = false,
    val result: VaccinationResult? = null,
)

// Se non viaggia un bimbo sotto 1 anno si passa un'eta' sopra qualunque soglia dei dati: la voce resta "richiesta".
private const val ADULT_AGE_MONTHS = 216
private const val MAX_STOPOVERS = 4
private const val MAX_RECENT = 8

@HiltViewModel
class VaccinationViewModel @Inject constructor(
    private val vaccinationRepository: VaccinationRepository,
    private val regionRepository: RegionRepository,
    private val nationalityPreferences: NationalityPreferences,
    private val vaccinationPreferences: VaccinationPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaccinationUiState())
    val uiState: StateFlow<VaccinationUiState> = _uiState.asStateFlow()

    private var data: VaccinationData? = null
    private var loadedForRegionId: String? = null
    private var dataJob: Job? = null
    private var nationalityJob: Job? = null
    private var nextStopoverId = 0

    // Come GuideViewModel.load: la regione arriva a runtime dalla composable.
    fun load(regionId: String) {
        if (loadedForRegionId == regionId) return
        loadedForRegionId = regionId
        data = null
        _uiState.value = VaccinationUiState()
        dataJob?.cancel()
        dataJob = viewModelScope.launch {
            // Di nuovo a ogni cambio del pacchetto guide: i dati vaccinali arrivano con quello.
            regionRepository.observeInstalledGuides().map { it?.version }.distinctUntilChanged().collectLatest {
                loadData(regionId)
            }
        }
        nationalityJob?.cancel()
        nationalityJob = viewModelScope.launch {
            nationalityPreferences.nationality.collect { nationality ->
                _uiState.update { state ->
                    val departure = vaccinationPreferences.departure ?: nationality?.uppercase()
                    recompute(state.copy(nationality = nationality?.uppercase(), departure = departure))
                }
            }
        }
    }

    private suspend fun loadData(regionId: String) {
        val loaded = try {
            vaccinationRepository.load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            null
        }
        data = loaded
        val destination = regionRepository.installed(regionId)?.countryCode?.uppercase()
        _uiState.update { state ->
            recompute(state.copy(available = loaded != null && destination != null, destination = destination, destinationRegion = regionId))
        }
    }

    fun setDeparture(country: String) {
        vaccinationPreferences.departure = country
        edit { it.copy(departure = country) }
    }

    fun addRecentCountry(country: String) = edit { state ->
        if (country in state.recentCountries || state.recentCountries.size >= MAX_RECENT) state else state.copy(recentCountries = state.recentCountries + country)
    }

    fun removeRecentCountry(country: String) = edit { it.copy(recentCountries = it.recentCountries - country) }

    fun addStopover() = edit { state ->
        if (state.stopovers.size >= MAX_STOPOVERS) state else state.copy(stopovers = state.stopovers + StopoverInput(nextStopoverId++))
    }

    fun updateStopover(id: Int, change: (StopoverInput) -> StopoverInput) =
        edit { state -> state.copy(stopovers = state.stopovers.map { if (it.id == id) change(it) else it }) }

    fun removeStopover(id: Int) = edit { state -> state.copy(stopovers = state.stopovers.filterNot { it.id == id }) }

    fun setChildUnderOne(value: Boolean) = edit { it.copy(childUnderOne = value) }

    fun setChildMonths(months: Int?) = edit { it.copy(childMonths = months) }

    fun setStayOverFourWeeks(value: Boolean) = edit { it.copy(stayOverFourWeeks = value) }

    fun setHajj(value: Boolean) = edit { it.copy(hajj = value) }

    private fun edit(change: (VaccinationUiState) -> VaccinationUiState) = _uiState.update { recompute(change(it)) }

    private fun recompute(state: VaccinationUiState): VaccinationUiState = recomputeVaccinations(state, data)
}

// Logica pura di recompute, fuori dal ViewModel per poterla provare senza Android ne' repository.
internal fun recomputeVaccinations(state: VaccinationUiState, data: VaccinationData?): VaccinationUiState {
    if (data == null) return state.copy(polioRelevant = false, result = null)
    val departure = state.departure
    val destination = state.destination
    val polioRelevant = departure != null && data.polioStatus.any {
        it.iso2.equals(departure, ignoreCase = true) && it.category == PolioCategory.WPV1_CVDPV1_CVDPV3
    }
    val trip = if (departure != null && destination != null) tripOf(state, departure, destination, polioRelevant) else null
    return state.copy(polioRelevant = polioRelevant, result = trip?.let { evaluateVaccinations(it, data) })
}

// Il viaggio da calcolare, null se partenza = destinazione senza scopo: non c'e' nulla da calcolare. Con l'Hajj il
// MenACWY vale anche per chi vive nel paese.
private fun tripOf(state: VaccinationUiState, departure: String, destination: String, polioRelevant: Boolean): Trip? {
    val purpose = if (state.hajj && destination.equals("SA", ignoreCase = true)) TripPurpose.HAJJ_UMRAH else null
    if (departure.equals(destination, ignoreCase = true) && purpose == null) return null
    return Trip(
        departure = departure,
        recentCountries = state.recentCountries.toSet(),
        transits = state.stopovers.mapNotNull { leg -> leg.country?.let { TripLeg(it, leg.hours.takeUnless { leg.overTwelveHours }, leg.leftAirport) } },
        destination = destination,
        destinationRegion = state.destinationRegion,
        travellerAgeMonths = if (state.childUnderOne) state.childMonths else ADULT_AGE_MONTHS,
        stayOverFourWeeksInDeparture = polioRelevant && state.stayOverFourWeeks,
        purpose = purpose,
    )
}
