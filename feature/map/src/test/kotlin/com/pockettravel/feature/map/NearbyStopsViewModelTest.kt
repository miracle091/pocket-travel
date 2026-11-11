package com.pockettravel.feature.map

import com.pockettravel.core.data.FavoriteStop
import com.pockettravel.core.data.MapBounds
import com.pockettravel.core.data.NearbyStop
import com.pockettravel.core.data.TransitDeparture
import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.data.testing.FakeFavoriteStopsRepository
import com.pockettravel.core.data.testing.FakeLastKnownPosition
import com.pockettravel.core.data.testing.FakeTransitRepository
import com.pockettravel.feature.map.testing.FakeOfflineTileSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyStopsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val transit = FakeTransitRepository()
    private val favorites = FakeFavoriteStopsRepository()
    private val position = FakeLastKnownPosition()
    private val tileSource = FakeOfflineTileSource().apply { bounds["r1"] = MapBounds(9.0, 45.0, 10.0, 46.0) }
    private var host: ViewModelHost<NearbyStopsViewModel>? = null

    // La rilettura di ogni minuto non finisce da sola: il ViewModel va chiuso dentro runTest, che prima di tornare esegue
    // tutto quello che resta in coda (un @After arriverebbe troppo tardi).
    private fun runViewModelTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            host?.clear()
        }
    }

    private fun TestScope.viewModel(): NearbyStopsViewModel {
        val created = ViewModelHost(NearbyStopsViewModel::class.java) {
            NearbyStopsViewModel(transit, favorites, position, tileSource, main.dispatcher)
        }
        host = created
        keepCollecting(created.viewModel.state)
        created.viewModel.setRegion("r1")
        runCurrent()
        return created.viewModel
    }

    private fun departure(line: String, inMinutes: Int = 5, mode: TransitMode = TransitMode.BUS) =
        TransitDeparture(line, mode, null, null, "Capolinea", 600, inMinutes)

    private fun stop(name: String, meters: Double, mode: TransitMode = TransitMode.BUS, next: List<TransitDeparture> = listOf(departure("1"))) =
        NearbyStop(name, 45.5, 9.5, meters, setOf(mode), next, emptyList())

    @Test
    fun `con una posizione recente dentro la mappa si cerca da li`() = runViewModelTest {
        position.position = 45.2 to 9.2
        transit.nearbyStops = listOf(stop("Duomo", 100.0))
        val viewModel = viewModel()

        viewModel.open(45.9, 9.9)
        runCurrent()

        assertEquals(NearbyOrigin.POSITION, settled(viewModel.state).origin)
        assertEquals(listOf("Duomo"), settled(viewModel.state).rows.map { it.stop.name })
    }

    @Test
    fun `senza posizione, o con una posizione fuori dalla mappa, si cerca dal centro della mappa`() = runViewModelTest {
        transit.nearbyStops = listOf(stop("Duomo", 100.0))
        val viewModel = viewModel()

        viewModel.open(45.9, 9.9)
        runCurrent()
        assertEquals(NearbyOrigin.MAP_CENTER, settled(viewModel.state).origin)

        position.position = 10.0 to 10.0
        viewModel.close()
        viewModel.open(45.9, 9.9)
        runCurrent()
        assertEquals(NearbyOrigin.MAP_CENTER, settled(viewModel.state).origin)
    }

    @Test
    fun `una posizione piu' vecchia di qualche minuto non vale`() = runViewModelTest {
        position.position = 45.2 to 9.2
        position.ageMillis = 10 * 60_000L
        val viewModel = viewModel()

        viewModel.open(45.9, 9.9)
        runCurrent()

        assertEquals(NearbyOrigin.MAP_CENTER, settled(viewModel.state).origin)
    }

    @Test
    fun `le fermate salvate stanno in cima`() = runViewModelTest {
        transit.nearbyStops = listOf(stop("Duomo", 100.0), stop("Stazione", 300.0), stop("Porta Nuova", 400.0))
        favorites.add("r1", FavoriteStop("Stazione", 45.5, 9.5))
        val viewModel = viewModel()

        viewModel.open(45.5, 9.5)
        runCurrent()

        val rows = settled(viewModel.state).rows
        assertEquals(listOf("Stazione", "Duomo", "Porta Nuova"), rows.map { it.stop.name })
        assertEquals(listOf(true, false, false), rows.map { it.favorite })
    }

    @Test
    fun `la stella salva e toglie la fermata`() = runViewModelTest {
        val viewModel = viewModel()

        viewModel.toggleFavorite("Stazione", 45.5, 9.5)
        runCurrent()
        assertEquals(listOf(FavoriteStop("Stazione", 45.5, 9.5)), settled(viewModel.state).favorites)
        assertTrue(isFavoriteStop(settled(viewModel.state).favorites, "stazione", 45.5001, 9.5))

        viewModel.toggleFavorite("Stazione", 45.5, 9.5)
        runCurrent()
        assertEquals(emptyList<FavoriteStop>(), settled(viewModel.state).favorites)
    }

    @Test
    fun `una fermata omonima lontana non e' la salvata`() {
        val saved = listOf(FavoriteStop("Stazione", 45.5, 9.5))

        assertFalse(isFavoriteStop(saved, "Stazione", 45.6, 9.5))
    }

    @Test
    fun `i filtri sono i mezzi presenti e scegliere un mezzo tiene solo le sue fermate`() = runViewModelTest {
        transit.nearbyStops = listOf(stop("Duomo", 100.0, TransitMode.METRO), stop("Piazza", 200.0, TransitMode.BUS), stop("Lago", 300.0, TransitMode.METRO))
        val viewModel = viewModel()
        viewModel.open(45.5, 9.5)
        runCurrent()

        assertEquals(listOf(TransitMode.METRO, TransitMode.BUS), settled(viewModel.state).modes)
        assertEquals(3, settled(viewModel.state).visibleRows.size)

        viewModel.setMode(TransitMode.METRO)
        assertEquals(listOf("Duomo", "Lago"), settled(viewModel.state).visibleRows.map { it.stop.name })

        viewModel.setMode(null)
        assertEquals(3, settled(viewModel.state).visibleRows.size)
    }

    @Test
    fun `un mezzo scelto che non c'e' piu' dopo l'aggiornamento torna a tutti`() = runViewModelTest {
        transit.nearbyStops = listOf(stop("Duomo", 100.0, TransitMode.METRO), stop("Piazza", 200.0, TransitMode.BUS))
        val viewModel = viewModel()
        viewModel.open(45.5, 9.5)
        runCurrent()
        viewModel.setMode(TransitMode.METRO)

        transit.nearbyStops = listOf(stop("Piazza", 200.0, TransitMode.BUS))
        advanceTimeBy(60_001)
        runCurrent()

        assertEquals(listOf("Piazza"), settled(viewModel.state).visibleRows.map { it.stop.name })
    }

    @Test
    fun `senza fermate entro il raggio l'elenco e' vuoto e non sta piu' caricando`() = runViewModelTest {
        val viewModel = viewModel()

        viewModel.open(45.5, 9.5)
        assertTrue(viewModel.state.value.loading)
        runCurrent()

        assertTrue(settled(viewModel.state).open)
        assertFalse(settled(viewModel.state).loading)
        assertEquals(emptyList<NearbyStopRow>(), settled(viewModel.state).visibleRows)
    }

    @Test
    fun `l'elenco si rilegge ogni minuto finche' e' aperto`() = runViewModelTest {
        val viewModel = viewModel()
        viewModel.open(45.5, 9.5)
        runCurrent()
        val first = transit.nearbyRequests.size

        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(first + 1, transit.nearbyRequests.size)

        viewModel.close()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(first + 1, transit.nearbyRequests.size)
        assertFalse(settled(viewModel.state).open)
    }
}
