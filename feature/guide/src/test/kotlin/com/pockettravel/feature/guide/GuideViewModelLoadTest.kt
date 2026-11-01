package com.pockettravel.feature.guide

import com.pockettravel.core.data.CityRepository
import com.pockettravel.core.data.DiplomaticMissionRepository
import com.pockettravel.core.data.EmergencyNumbersRepository
import com.pockettravel.core.data.GuideRepository
import com.pockettravel.core.data.GuideSourceTarget
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.LastOpenedCityGuide
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.advice.TravelAdviceUpdate
import com.pockettravel.core.data.advice.TravelAdviceUpdates
import com.pockettravel.core.data.alerts.AlertsRepository
import com.pockettravel.core.data.alerts.RegionAlerts
import com.pockettravel.core.data.db.DiplomaticMissionDao
import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.testing.FakeLastKnownPosition
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** DAO che risponde con una lista vuota (o col valore indicato per nome) e registra le chiamate. */
private inline fun <reified T : Any> recording(calls: MutableList<String>, vararg answers: Pair<String, Any?>): T {
    val byName = answers.toMap()
    return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        calls += method.name
        if (method.name in byName) byName[method.name] else emptyList<Any>()
    } as T
}

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModelLoadTest {

    private val dispatcher = StandardTestDispatcher()
    private val dao = ClimateCityDao()
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = GuideViewModel(
        guideRepository = GuideRepository(recording<GuideDao>(calls)),
        emergencyNumbersRepository = EmergencyNumbersRepository(
            recording<EmergencyNumbersDao>(calls, "forRegion" to null, "hasNoCentralNumber" to false),
        ),
        cityRepository = CityRepository(dao),
        regionRepository = FakeRegionRepository(),
        poiRepository = PoiRepository(recording<PoiDao>(calls)),
        diplomaticMissionRepository = DiplomaticMissionRepository(recording<DiplomaticMissionDao>(calls)),
        nationalityPreferences = NationalityPreferences(
            InMemoryPreferencesFactory().also { it.open("profile").edit().putString("nationality", "IT").commit() },
        ),
        lastKnownPosition = FakeLastKnownPosition(),
        weatherRepository = NoWeather,
        lastOpenedCityGuide = LastOpenedCityGuide(),
        alertsRepository = object : AlertsRepository {
            override suspend fun alerts(regionId: String): RegionAlerts? = null
        },
        travelAdviceUpdates = object : TravelAdviceUpdates {
            override suspend fun latest(slug: String): TravelAdviceUpdate? = null
        },
    )

    @Test
    fun `la nazione del catalogo che arriva dopo la guida non la ricarica`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.load("italia")
        advanceUntilIdle()

        viewModel.load("italia", "it")
        advanceUntilIdle()

        assertEquals(1, calls.count { it == "sectionsForRegion" })
        assertTrue(!viewModel.uiState.value.isLoading)
    }

    @Test
    fun `cambiando regione i dati della precedente spariscono subito`() = runTest(dispatcher) {
        dao.citiesByRegion["italia"] = listOf("Roma")
        val viewModel = viewModel()
        viewModel.load("italia")
        advanceUntilIdle()
        assertEquals(listOf("Roma"), viewModel.uiState.value.cities)

        viewModel.load("francia")

        assertEquals(emptyList<String>(), viewModel.uiState.value.cities)
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `retry ricarica la guida della stessa regione`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.load("italia")
        advanceUntilIdle()

        viewModel.load("italia", reload = true)
        advanceUntilIdle()

        assertEquals(2, calls.count { it == "sectionsForRegion" })
    }

    @Test
    fun `retry della citta' dopo un errore ricarica le sezioni`() = runTest(dispatcher) {
        val viewModel = viewModel()
        dao.failSections = true
        viewModel.loadCity("italia", "Roma")
        advanceUntilIdle()
        assertEquals(R.string.guide_city_load_error, viewModel.cityUiState.value.loadError)

        dao.failSections = false
        viewModel.loadCity("italia", "Roma")
        advanceUntilIdle()

        assertEquals(null, viewModel.cityUiState.value.loadError)
        assertTrue(!viewModel.cityUiState.value.isLoading)
    }

    @Test
    fun `ogni richiesta di mostrare una sezione e' distinta anche per la stessa fonte`() {
        val target = GuideSourceTarget(city = null, category = GuideCategory.SALUTE, title = "Acqua", answer = "Si' beve.")

        assertNotEquals(FocusRequest(target), FocusRequest(target))
    }
}
