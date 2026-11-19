package com.pockettravel.feature.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Coordinate e link nella ricerca, preferiti e posti che arrivano da altre app. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationPlannerDestinationsTest : NavigationPlannerTestBase() {
    private fun TestScope.search(viewModel: NavigationPlannerViewModel, text: String): List<PlannerResult> {
        viewModel.setQuery(text)
        advanceTimeBy(300)
        runCurrent()
        return settled(viewModel.uiState).results
    }

    // --- Coordinate nella ricerca ---

    @Test
    fun `delle coordinate nella ricerca diventano la prima riga, con la regione che contiene il punto`() = runTest {
        val viewModel = viewModel()
        viewModel.load("r1")
        viewModel.startSearch(PlannerField.TO)

        val row = search(viewModel, "48,86 2,33").single()

        assertEquals(NavigationPlace("48.86000, 2.33000", 48.86, 2.33, "r2"), row.place)
        assertEquals(R.string.planner_type_coordinates, row.typeLabel)
        assertEquals("Francia", row.otherRegionName)
    }

    @Test
    fun `delle coordinate danno solo la riga del punto, senza cercare per nome`() = runTest {
        dao.rows += poiRow(1, "12 34", category = "museum", osmTag = "tourism=museum")
        val viewModel = viewModel()
        viewModel.load("r1")
        viewModel.startSearch(PlannerField.TO)

        val names = search(viewModel, "12 34").map { it.place.name }

        assertEquals(listOf("12.00000, 34.00000"), names)
    }

    @Test
    fun `un link di Google Maps da' la riga con il nome del posto e non cerca per nome`() = runTest {
        dao.rows += poiRow(1, "Eiffel", category = "museum", osmTag = "tourism=museum")
        val viewModel = viewModel()
        viewModel.load("r1")
        viewModel.startSearch(PlannerField.WAYPOINT)

        val rows = search(viewModel, "https://www.google.com/maps/place/Eiffel+Tower/@48.8583,2.2944,17z")

        assertEquals(listOf(NavigationPlace("Eiffel Tower", 48.8583, 2.2944, "r2")), rows.map { it.place })
    }

    @Test
    fun `un punto fuori da ogni regione va alla regione aperta`() = runTest {
        val viewModel = viewModel()
        viewModel.load("r2")
        viewModel.startSearch(PlannerField.TO)

        val row = search(viewModel, "-33.86, 151.21").single()

        assertEquals("r2", row.place.regionId)
        assertNull(row.otherRegionName)
    }

    @Test
    fun `un testo che non e' un punto non aggiunge la riga delle coordinate`() = runTest {
        val viewModel = viewModel()
        viewModel.load("r1")
        viewModel.startSearch(PlannerField.TO)

        assertEquals(emptyList<PlannerResult>(), search(viewModel, "https://esempio.it/pagina"))
    }

    // --- Preferiti ---

    @Test
    fun `un preferito si salva col nome scelto, compare nello stato e si toglie`() = runTest {
        val viewModel = viewModel()

        viewModel.saveFavorite(colosseo, "Alloggio")
        assertEquals(listOf(colosseo.copy(name = "Alloggio")), settled(viewModel.uiState).favorites)

        viewModel.removeFavorite(colosseo.copy(name = "Alloggio"))
        assertEquals(emptyList<NavigationPlace>(), settled(viewModel.uiState).favorites)
    }

    @Test
    fun `la destinazione salvata tra i preferiti prende il nome scelto`() = runTest {
        val viewModel = viewModel()
        val point = NavigationPlace("Punto sulla mappa", 41.89, 12.49, "r1")
        viewModel.setDestination(point)
        runCurrent()

        viewModel.saveFavorite(point, "  Alloggio ")

        assertEquals(point.copy(name = "Alloggio"), settled(viewModel.uiState).to)
    }

    @Test
    fun `salvare lo stesso punto cambia il nome invece di ripeterlo`() = runTest {
        val viewModel = viewModel()
        viewModel.saveFavorite(colosseo, "Alloggio")

        viewModel.saveFavorite(colosseo, "Da Marco")

        assertEquals(listOf(colosseo.copy(name = "Da Marco")), settled(viewModel.uiState).favorites)
    }

    @Test
    fun `un nome vuoto non si salva e i preferiti di regioni non installate non compaiono`() = runTest {
        val viewModel = viewModel()

        viewModel.saveFavorite(colosseo, "   ")
        viewModel.saveFavorite(NavigationPlace("Lontano", 1.0, 1.0, "r9"), "Lontano")

        assertEquals(emptyList<NavigationPlace>(), settled(viewModel.uiState).favorites)
    }

    @Test
    fun `i preferiti restano dopo la chiusura dell'app`() {
        FavoriteDestinations(files).save(duomo, "Alloggio")

        assertEquals(listOf(duomo.copy(name = "Alloggio")), FavoriteDestinations(files).places.value)
    }

    // --- Posti da altre app ---

    @Test
    fun `un geo con coordinate diventa la destinazione e apre l'anteprima`() = runTest {
        val viewModel = viewModel()

        val message = viewModel.openSharedPlace("geo:48.86,2.33")
        runCurrent()

        assertNull(message)
        assertEquals(NavigationPlace("48.86000, 2.33000", 48.86, 2.33, "r2"), settled(viewModel.uiState).to)
        assertNull(settled(viewModel.uiState).from)
    }

    @Test
    fun `un testo condiviso con un link di OpenStreetMap da' la destinazione`() = runTest {
        val viewModel = viewModel()

        viewModel.openSharedPlace("Guarda: https://www.openstreetmap.org/?mlat=45.46&mlon=9.19")

        assertEquals(NavigationPlace("45.46000, 9.19000", 45.46, 9.19, "r1"), settled(viewModel.uiState).to)
    }

    @Test
    fun `un geo che chiede di cercare un testo apre la ricerca della destinazione`() = runTest {
        val viewModel = viewModel()

        val message = viewModel.openSharedPlace("geo:0,0?q=Via%20Roma%201")

        assertNull(message)
        assertEquals(PlannerField.TO, settled(viewModel.uiState).searching)
        assertEquals("Via Roma 1", settled(viewModel.uiState).query)
        assertNull(settled(viewModel.uiState).to)
    }

    @Test
    fun `un link breve o un testo senza posto danno un messaggio e lasciano la destinazione com'e'`() = runTest {
        val viewModel = viewModel()

        assertEquals(R.string.shared_place_short_link, viewModel.openSharedPlace("https://maps.app.goo.gl/AbC123"))
        assertEquals(R.string.shared_place_unreadable, viewModel.openSharedPlace("Ciao a tutti"))
        assertNull(settled(viewModel.uiState).to)
    }
}
