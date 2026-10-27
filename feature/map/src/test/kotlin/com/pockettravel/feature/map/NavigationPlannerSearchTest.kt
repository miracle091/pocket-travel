package com.pockettravel.feature.map

import androidx.lifecycle.SavedStateHandle
import com.pockettravel.core.data.AddressResult
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.testing.FakeAddressSearchRepository
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationPlannerSearchTest : NavigationPlannerTestBase() {
    // --- Ricerca ---

    @Test
    fun `la ricerca aspetta la pausa nella digitazione e sotto due lettere non cerca`() = runTest {
        gps.permission = false
        dao.rows += poiRow(1, "Louvre", category = "museum", osmTag = "tourism=museum")
        val viewModel = viewModel()
        viewModel.load("r1")
        viewModel.startSearch(PlannerField.TO)

        viewModel.setQuery("l")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(emptyList<PlannerResult>(), settled(viewModel.uiState).results)

        viewModel.setQuery("lou")
        advanceTimeBy(299)
        runCurrent()
        assertEquals(emptyList<PlannerResult>(), settled(viewModel.uiState).results)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("Louvre"), settled(viewModel.uiState).results.map { it.place.name })
    }

    @Test
    fun `un risultato porta il posto, il tipo preciso e nessuna regione se e' quella aperta`() = runTest {
        gps.permission = false
        dao.rows += poiRow(1, "Louvre", category = "museum", osmTag = "tourism=museum", lat = 48.86, lon = 2.33)
        val viewModel = viewModel()
        viewModel.load("r1")

        viewModel.setQuery("louvre")
        advanceTimeBy(300)
        runCurrent()

        val result = settled(viewModel.uiState).results.single()
        assertEquals(NavigationPlace("Louvre", 48.86, 2.33, "r1"), result.place)
        assertEquals(poiTypeLabel("tourism=museum"), result.typeLabel)
        assertNull(result.otherRegionName)
        assertFalse(result.isAddress)
    }

    @Test
    fun `il nome mostrato segue la lingua del dispositivo`() = runTest {
        gps.permission = false
        dao.rows += poiRow(1, "Tour Eiffel", category = "attraction", osmTag = "tourism=attraction", nameIt = "Torre Eiffel", nameEn = "Eiffel Tower")
        val viewModel = viewModel()
        viewModel.load("r1")

        viewModel.setQuery("eiffel")
        advanceTimeBy(300)
        runCurrent()
        assertEquals("Torre Eiffel", settled(viewModel.uiState).results.single().place.name)

        Locale.setDefault(Locale.ENGLISH)
        viewModel.setQuery("eiffel tower")
        advanceTimeBy(300)
        runCurrent()
        assertEquals("Eiffel Tower", settled(viewModel.uiState).results.single().place.name)
    }

    @Test
    fun `i posti da visitare vengono prima degli hotel che ne portano il nome`() = runTest {
        gps.permission = false
        dao.rows += poiRow(1, "Hotel Louvre", category = "hotel", osmTag = "tourism=hotel")
        dao.rows += poiRow(2, "Louvre", category = "museum", osmTag = "tourism=museum")
        val viewModel = viewModel()
        viewModel.load("r1")

        viewModel.setQuery("louvre")
        advanceTimeBy(300)
        runCurrent()

        assertEquals(listOf("Louvre", "Hotel Louvre"), settled(viewModel.uiState).results.map { it.place.name })
    }

    @Test
    fun `i risultati di un'altra regione vengono dopo e ne portano il nome`() = runTest {
        gps.permission = false
        dao.rows += poiRow(1, "Louvre", regionId = "r2", category = "museum", osmTag = "tourism=museum")
        dao.rows += poiRow(2, "Hotel Louvre", regionId = "r1", category = "hotel", osmTag = "tourism=hotel")
        val viewModel = viewModel()
        viewModel.load("r1")

        viewModel.setQuery("louvre")
        advanceTimeBy(300)
        runCurrent()

        assertEquals(listOf("Hotel Louvre", "Louvre"), settled(viewModel.uiState).results.map { it.place.name })
        assertEquals(listOf<String?>(null, "Francia"), settled(viewModel.uiState).results.map { it.otherRegionName })
        // La regione aperta si cerca per prima, poi le altre.
        assertEquals(listOf("r1", "r2"), dao.searches.last().first)
    }

    @Test
    fun `a parita' di rilevanza il piu' vicino alla partenza viene per primo`() = runTest {
        gps.permission = false
        dao.rows += poiRow(1, "Bar Sport", lat = 41.9, lon = 12.5)
        dao.rows += poiRow(2, "Bar Sport", lat = 45.47, lon = 9.2)
        val viewModel = viewModel()
        viewModel.load("r1")
        viewModel.startSearch(PlannerField.FROM)
        viewModel.choose(duomo)

        viewModel.setQuery("bar sport")
        advanceTimeBy(300)
        runCurrent()

        assertEquals(listOf(45.47, 41.9), settled(viewModel.uiState).results.map { it.place.latitude })
        assertTrue((settled(viewModel.uiState).results.first().distanceMeters ?: Double.MAX_VALUE) < 2_000.0)
    }

    @Test
    fun `senza regioni installate la ricerca non trova nulla`() = runTest {
        gps.permission = false
        regions.installedRegions.value = emptyList()
        dao.rows += poiRow(1, "Louvre")
        val viewModel = viewModel()

        viewModel.setQuery("louvre")
        advanceTimeBy(300)
        runCurrent()

        assertEquals(emptyList<PlannerResult>(), settled(viewModel.uiState).results)
    }

    @Test
    fun `con un civico gli indirizzi vengono prima dei punti di interesse, senza dopo`() = runTest {
        gps.permission = false
        dao.rows += poiRow(1, "Via Roma 12 Pizzeria")
        addresses.addresses += AddressResult("r1", "Via Roma", "12", "Torino", 45.07, 7.68)
        val viewModel = viewModel()
        viewModel.load("r1")

        viewModel.setQuery("via roma 12")
        advanceTimeBy(300)
        runCurrent()
        val withNumber = settled(viewModel.uiState).results
        assertEquals(listOf(true, false), withNumber.map { it.isAddress })
        assertEquals(R.string.planner_type_address, withNumber.first().typeLabel)
        assertEquals("Via Roma 12, Torino", withNumber.first().place.name)

        viewModel.setQuery("via roma")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(false, true), settled(viewModel.uiState).results.map { it.isAddress })
    }

    @Test
    fun `i risultati sono al massimo trenta e gli indirizzi dieci`() = runTest {
        gps.permission = false
        repeat(40) { dao.rows += poiRow(it + 1L, "Bar $it") }
        repeat(12) { addresses.addresses += AddressResult("r1", "Via Bar", "$it", "Torino", 45.0 + it / 1000.0, 7.0) }
        val viewModel = viewModel()
        viewModel.load("r1")

        viewModel.setQuery("bar")
        advanceTimeBy(300)
        runCurrent()

        assertEquals(30, settled(viewModel.uiState).results.size)
        assertEquals(10, settled(viewModel.uiState).results.count { it.isAddress })
    }

    @Test
    fun `aprire la ricerca la azzera e prende la posizione per ordinare per distanza`() = runTest {
        val viewModel = viewModel()
        viewModel.setQuery("vecchia")

        viewModel.startSearch(PlannerField.FROM)
        runCurrent()

        assertEquals("", settled(viewModel.uiState).query)
        assertEquals(PlannerField.FROM, settled(viewModel.uiState).searching)
        // Uno per la posizione sulla mappa senza destinazione (lo stato e' osservato), uno per la ricerca.
        assertEquals(2, gps.listeners)
        gps.emit(fix())
        runCurrent()
        assertEquals(RoutePoint(duomo.latitude, duomo.longitude), viewModel.startPoint())

        viewModel.cancelSearch()
        assertNull(settled(viewModel.uiState).searching)
    }

    @Test
    fun `i fix del GPS vicini non rifanno la ricerca, la prima posizione e uno spostamento oltre 200 m si`() = runTest {
        dao.rows += poiRow(1, "Bar Sport", lat = 41.9, lon = 12.5)
        val viewModel = viewModel()
        viewModel.load("r1")
        viewModel.setQuery("bar sport")
        advanceTimeBy(300)
        runCurrent()
        val first = dao.searches.size

        gps.emit(fix())
        runCurrent()
        assertEquals(first + 1, dao.searches.size)

        // Circa 11 m e 55 m dal primo fix.
        gps.emit(fix(latitude = duomo.latitude + 0.0001))
        gps.emit(fix(latitude = duomo.latitude + 0.0005))
        runCurrent()
        assertEquals(first + 1, dao.searches.size)

        // Circa 330 m dal primo fix.
        gps.emit(fix(latitude = duomo.latitude + 0.003))
        runCurrent()
        assertEquals(first + 2, dao.searches.size)
    }

    @Test
    fun `senza permesso aprire la ricerca non ascolta il GPS`() = runTest {
        gps.permission = false
        val viewModel = viewModel()

        viewModel.startSearch(PlannerField.TO)
        runCurrent()

        assertEquals(0, gps.listeners)
        assertNull(viewModel.startPoint())
    }

    @Test
    fun `la destinazione dell'assistente compila la ricerca e preferisce il testo col momento del giorno se e' un posto`() = runTest {
        gps.permission = false
        dao.rows += poiRow(1, "Bar Stasera")
        val viewModel = viewModel()
        viewModel.load("r1")

        viewModel.searchDestination("bar", "bar stasera")
        runCurrent()

        assertEquals(PlannerField.TO, settled(viewModel.uiState).searching)
        assertEquals("bar stasera", settled(viewModel.uiState).query)
    }

    @Test
    fun `la destinazione dell'assistente resta senza il momento del giorno se non e' il nome di un posto`() = runTest {
        gps.permission = false
        dao.rows += poiRow(1, "Bar Roma")
        val viewModel = viewModel()
        viewModel.load("r1")

        viewModel.searchDestination("bar", "bar domani")
        runCurrent()

        assertEquals("bar", settled(viewModel.uiState).query)
    }

    @Test
    fun `se l'utente cambia la ricerca il controllo dell'assistente non la sovrascrive`() = runTest {
        gps.permission = false
        dao.rows += poiRow(1, "Bar Stasera")
        val viewModel = viewModel()
        viewModel.load("r1")

        viewModel.searchDestination("bar", "bar stasera")
        viewModel.setQuery("museo")
        runCurrent()

        assertEquals("museo", settled(viewModel.uiState).query)
    }
}
