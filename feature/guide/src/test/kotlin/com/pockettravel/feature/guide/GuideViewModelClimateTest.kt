package com.pockettravel.feature.guide

import com.pockettravel.core.data.CityRepository
import com.pockettravel.core.data.DiplomaticMissionRepository
import com.pockettravel.core.data.EmergencyNumbersRepository
import com.pockettravel.core.data.GuideRepository
import com.pockettravel.core.data.LastOpenedCityGuide
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.WeatherRepository
import com.pockettravel.core.data.WeatherResult
import com.pockettravel.core.data.advice.TravelAdviceUpdates
import com.pockettravel.core.data.alerts.AlertsRepository
import com.pockettravel.core.data.db.CityClimateEntity
import com.pockettravel.core.data.db.CityCoordinates
import com.pockettravel.core.data.db.CityDao
import com.pockettravel.core.data.db.CityPopulation
import com.pockettravel.core.data.db.CitySectionEntity
import com.pockettravel.core.data.db.CitySectionMatch
import com.pockettravel.core.data.db.DiplomaticMissionDao
import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.testing.FakeLastKnownPosition
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** CityDao in memoria: a loadCity servono solo le sezioni e il clima. */
private class ClimateCityDao : CityDao {
    val sections = mutableListOf<CitySectionEntity>()
    val climate = mutableListOf<CityClimateEntity>()

    override suspend fun insertAll(sections: List<CitySectionEntity>) {
        this.sections += sections
    }

    override suspend fun insertClimate(rows: List<CityClimateEntity>) {
        climate += rows
    }

    override suspend fun climateFor(regionId: String, city: String): List<CityClimateEntity> =
        climate.filter { it.regionId == regionId && it.city == city }.sortedBy { it.month }

    override suspend fun deleteClimateForRegion(regionId: String) {
        climate.removeAll { it.regionId == regionId }
    }

    override fun citiesForRegion(regionId: String): Flow<List<String>> = flowOf(emptyList())

    override fun mainCitiesForRegion(regionId: String, limit: Int): Flow<List<CityPopulation>> = flowOf(emptyList())

    override suspend fun coordinatesFor(regionId: String, city: String): CityCoordinates? = null

    override suspend fun sectionsFor(regionId: String, city: String): List<CitySectionEntity> =
        sections.filter { it.regionId == regionId && it.city == city }

    override suspend fun searchInRegionRanked(regionId: String, query: String, city: String?, candidateLimit: Int): List<CitySectionMatch> =
        emptyList()

    override suspend fun deleteForRegion(regionId: String) = Unit

    override suspend fun optimizeFts() = Unit
}

private object NoWeather : WeatherRepository {
    override suspend fun weather(cacheKey: String, coordinates: suspend () -> Pair<Double, Double>?): WeatherResult? = null

    override suspend fun coordinatesOf(city: String, countryCode: String): Pair<Double, Double>? = null
}

// Dipendenze che loadCity non usa: se una venisse chiamata il test fallisce.
private inline fun <reified T : Any> unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("${T::class.java.simpleName}.${method.name} non previsto")
    } as T

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModelClimateTest {

    private val dispatcher = StandardTestDispatcher()
    private val dao = ClimateCityDao()
    private val url = "https://it.wikipedia.org/wiki/Roma"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Nazionalita' gia' scelta: senza, NationalityPreferences leggerebbe il paese del sistema (assente nei test JVM).
    private fun nationality() = NationalityPreferences(
        InMemoryPreferencesFactory().also { it.open("profile").edit().putString("nationality", "IT").commit() },
    )

    private fun viewModel() = GuideViewModel(
        guideRepository = GuideRepository(unused<GuideDao>()),
        emergencyNumbersRepository = EmergencyNumbersRepository(unused<EmergencyNumbersDao>()),
        cityRepository = CityRepository(dao),
        regionRepository = FakeRegionRepository(),
        poiRepository = PoiRepository(unused<PoiDao>()),
        diplomaticMissionRepository = DiplomaticMissionRepository(unused<DiplomaticMissionDao>()),
        nationalityPreferences = nationality(),
        lastKnownPosition = FakeLastKnownPosition(),
        weatherRepository = NoWeather,
        lastOpenedCityGuide = LastOpenedCityGuide(),
        alertsRepository = unused<AlertsRepository>(),
        travelAdviceUpdates = unused<TravelAdviceUpdates>(),
    )

    @Test
    fun `loadCity carica le medie mensili della citta' senza rete`() = runTest(dispatcher) {
        dao.climate += listOf(
            CityClimateEntity("italia", "Roma", 2, 13.0, 3.0, 70.0, 7.0, url),
            CityClimateEntity("italia", "Roma", 1, 12.0, 2.0, null, null, url),
            CityClimateEntity("italia", "Bari", 1, 11.0, 4.0, 40.0, 6.0, url),
        )
        val viewModel = viewModel()

        viewModel.loadCity("italia", "Roma")
        advanceUntilIdle()

        val state = viewModel.cityUiState.value
        assertEquals(listOf(1, 2), state.climate.map { it.month })
        assertEquals(12.0, state.climate.first().tempMax)
        assertEquals(false, state.isLoading)
    }

    @Test
    fun `loadCity lascia vuote le medie di una citta' senza tabella climatica`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.loadCity("italia", "Roma")
        advanceUntilIdle()

        assertEquals(emptyList<Any>(), viewModel.cityUiState.value.climate)
    }
}
