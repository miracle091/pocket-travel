package com.pockettravel.feature.map

import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.RealtimeFeed
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.data.RegionZonePreferences
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.data.TransitFeedInfo
import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.FakeTransitRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.feature.map.testing.FakeConnectivityChecker
import com.pockettravel.feature.map.testing.FakeConnectivityObserver
import com.pockettravel.feature.map.testing.FakeTransitRealtimeClient
import com.pockettravel.feature.map.testing.MutableClock
import com.pockettravel.feature.map.testing.FakeOfflineTileSource
import com.pockettravel.feature.map.testing.FakePoiDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class MapRouteViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val files = InMemoryPreferencesFactory()
    private val dao = FakePoiDao()
    private val tileSource = FakeOfflineTileSource().apply {
        bounds["r1"] = MapBounds(9.0, 45.0, 10.0, 46.0)
        bounds["r2"] = MapBounds(20.0, 50.0, 21.0, 51.0)
    }
    private val regions = FakeRegionRepository(listOf(installedRegion("r1"), installedRegion("r2")))
    private val transit = FakeTransitRepository()
    private val connectivity = FakeConnectivityObserver()
    private val realtimeClient = FakeTransitRealtimeClient()
    private val realtimeConnectivity = FakeConnectivityChecker()
    private val realtimeClock = MutableClock()
    private val filters = MapFilterPreferences(files)
    private val usageModes = UsageModePreferences(files, filters, RoutingVariantPreferences(files))
    private val zones = RegionZonePreferences(files)
    private var host: ViewModelHost<MapRouteViewModel>? = null

    @After
    fun closeViewModel() {
        host?.clear()
    }

    private fun TestScope.viewModel(): MapRouteViewModel {
        val created = ViewModelHost(MapRouteViewModel::class.java) {
            MapRouteViewModel(
                tileSource, PoiRepository(dao), zones, regions, filters, transit, usageModes, connectivity,
                TransitRealtimeLoader(realtimeClient, realtimeConnectivity, realtimeClock), main.dispatcher,
            )
        }
        host = created
        keepCollecting(created.viewModel.uiState)
        return created.viewModel
    }

    private fun pin(osmTag: String, id: String = "1") =
        MapPin(id, "Stazione", 45.5, 9.5, PoiCategory.TRENO, osmTag, null)

    @Test
    fun `senza una regione i segnalini sono vuoti e non si legge nulla`() = runTest {
        val viewModel = viewModel()
        runCurrent()

        assertEquals(emptyList<MapPin>(), settled(viewModel.uiState).pins)
        assertTrue(dao.inBoundsCalls.isEmpty())
    }

    @Test
    fun `prima del primo fermo della mappa i segnalini sono quelli del rettangolo della mappa installata`() = runTest {
        dao.rows += poiRow(1, "Da Mario", lat = 45.5, lon = 9.5)
        dao.rows += poiRow(2, "Lontano", lat = 10.0, lon = 10.0)
        val viewModel = viewModel()

        viewModel.loadPins("r1")
        runCurrent()

        assertEquals(listOf("1"), settled(viewModel.uiState).pins.map { it.id })
        val pin = settled(viewModel.uiState).pins.single()
        assertEquals("Da Mario", pin.name)
        assertEquals(PoiCategory.CIBO_BEVANDE, pin.category)
        assertEquals(45.5, pin.latitude, 0.0)
    }

    @Test
    fun `senza mappa ne zona si legge tutto il mondo`() = runTest {
        tileSource.bounds.remove("r1")
        dao.rows += poiRow(1, "Da Mario", lat = -33.0, lon = 151.0)
        val viewModel = viewModel()

        viewModel.loadPins("r1")
        runCurrent()

        assertEquals(listOf("1"), settled(viewModel.uiState).pins.map { it.id })
    }

    @Test
    fun `il fermo della mappa limita i segnalini all'area inquadrata`() = runTest {
        dao.rows += poiRow(1, "Dentro", lat = 45.2, lon = 9.2)
        dao.rows += poiRow(2, "Fuori", lat = 45.8, lon = 9.8)
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()

        viewModel.setViewport(MapBounds(9.0, 45.0, 9.5, 45.5))
        runCurrent()

        assertEquals(listOf("1"), settled(viewModel.uiState).pins.map { it.id })
    }

    @Test
    fun `l'area dei segnalini resta dentro la zona scaricata`() = runTest {
        zones.setZone("r1", RegionZone(minLon = 9.0, minLat = 45.0, maxLon = 9.5, maxLat = 45.5))
        dao.rows += poiRow(1, "Dentro la zona", lat = 45.2, lon = 9.2)
        dao.rows += poiRow(2, "Fuori dalla zona", lat = 45.8, lon = 9.8)
        val viewModel = viewModel()

        viewModel.loadPins("r1")
        runCurrent()
        viewModel.setViewport(MapBounds(8.0, 44.0, 11.0, 47.0))
        runCurrent()

        assertEquals(listOf("1"), settled(viewModel.uiState).pins.map { it.id })
        val call = dao.inBoundsCalls.last()
        assertEquals(9.0, call.minLon, 0.0)
        assertEquals(45.5, call.maxLat, 0.0)
    }

    @Test
    fun `una vista fuori dalla zona scaricata non legge nulla e non cambia le categorie presenti`() = runTest {
        zones.setZone("r1", RegionZone(minLon = 9.0, minLat = 45.0, maxLon = 9.5, maxLat = 45.5))
        dao.rows += poiRow(1, "Da Mario", lat = 45.2, lon = 9.2)
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()
        val readsBefore = dao.inBoundsCalls.size

        viewModel.setViewport(MapBounds(30.0, 60.0, 31.0, 61.0))
        runCurrent()

        assertEquals(readsBefore, dao.inBoundsCalls.size)
        assertEquals(emptyList<MapPin>(), settled(viewModel.uiState).pins)
        assertEquals(setOf(PoiCategory.CIBO_BEVANDE), settled(viewModel.uiState).presentCategories)
    }

    @Test
    fun `i POI nascosti sulla mappa non diventano segnalini ma quelli extra si`() = runTest {
        dao.rows += poiRow(1, "Panchina", category = "bench", osmTag = "amenity=bench")
        dao.rows += poiRow(2, "Tavolo", category = "picnic_table", osmTag = "leisure=picnic_table", extra = true)
        dao.rows += poiRow(3, "Da Mario")
        val viewModel = viewModel()

        viewModel.loadPins("r1")
        runCurrent()

        assertEquals(setOf("2", "3"), settled(viewModel.uiState).pins.map { it.id }.toSet())
    }

    @Test
    fun `un POI senza nome in OSM diventa un segnalino senza nome quando la sua categoria si mostra lo stesso`() = runTest {
        dao.rows += poiRow(1, "toilets", category = "toilets", osmTag = "amenity=toilets")
        val viewModel = viewModel()

        viewModel.loadPins("r1")
        runCurrent()

        val pin = settled(viewModel.uiState).pins.singleOrNull()
        assertNull(pin?.name)
        assertEquals(PoiCategory.BAGNI_PUBBLICI, pin?.category)
    }

    @Test
    fun `nascondere una categoria ricarica i segnalini senza di lei ma la lascia tra le presenti`() = runTest {
        dao.rows += poiRow(1, "Da Mario")
        dao.rows += poiRow(2, "Hotel Roma", category = "hotel", osmTag = "tourism=hotel")
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()
        assertEquals(setOf("1", "2"), settled(viewModel.uiState).pins.map { it.id }.toSet())

        viewModel.setHiddenCategories(setOf(PoiCategory.ALLOGGIO))
        runCurrent()

        assertEquals(setOf(PoiCategory.ALLOGGIO), settled(viewModel.uiState).hiddenCategories)
        assertEquals(listOf("1"), settled(viewModel.uiState).pins.map { it.id })
        assertEquals(listOf("tourism=hotel").map { "hotel|$it" }, dao.inBoundsCalls.last().excluded)
        assertEquals(setOf(PoiCategory.CIBO_BEVANDE, PoiCategory.ALLOGGIO), settled(viewModel.uiState).presentCategories)
        // La scelta resta per la prossima apertura.
        assertEquals(setOf(PoiCategory.ALLOGGIO), MapFilterPreferences(files).hiddenCategories.value)
    }

    @Test
    fun `le categorie della regione si leggono una volta e si rileggono riaprendo la regione`() = runTest {
        dao.rows += poiRow(1, "Da Mario")
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()
        viewModel.setViewport(MapBounds(9.0, 45.0, 9.9, 45.9))
        runCurrent()
        viewModel.setOnlyAccessible(true)
        runCurrent()
        assertEquals(1, dao.categoryTagReads)

        viewModel.loadPins("r1")
        runCurrent()

        assertEquals(2, dao.categoryTagReads)
    }

    @Test
    fun `il filtro in sedia a rotelle segue le impostazioni e solo accessibili`() = runTest {
        dao.rows += poiRow(1, "Da Mario")
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()
        assertEquals(0, dao.inBoundsCalls.last().accessibility)

        // "In sedia a rotelle" si accende nelle Impostazioni: i segnalini si rileggono senza i non accessibili.
        usageModes.setAccessible(true)
        runCurrent()
        assertEquals(1, dao.inBoundsCalls.last().accessibility)

        viewModel.setOnlyAccessible(true)
        runCurrent()
        assertEquals(2, dao.inBoundsCalls.last().accessibility)
        assertTrue(settled(viewModel.uiState).onlyAccessible)
        assertTrue(settled(viewModel.uiState).accessible)

        // "Solo accessibili" non vale senza la casella principale.
        usageModes.setAccessible(false)
        runCurrent()
        assertEquals(0, dao.inBoundsCalls.last().accessibility)
    }

    @Test
    fun `cambiare regione dimentica l'area inquadrata, tornare nella stessa la tiene`() = runTest {
        dao.rows += poiRow(1, "Uno", regionId = "r1", lat = 45.2, lon = 9.2)
        dao.rows += poiRow(2, "Due", regionId = "r1", lat = 45.8, lon = 9.8)
        dao.rows += poiRow(3, "Tre", regionId = "r2", lat = 50.5, lon = 20.5)
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()
        viewModel.setViewport(MapBounds(9.0, 45.0, 9.5, 45.5))
        runCurrent()

        viewModel.loadPins("r1")
        runCurrent()
        assertEquals(listOf("1"), settled(viewModel.uiState).pins.map { it.id })

        viewModel.loadPins("r2")
        runCurrent()
        assertEquals(listOf("3"), settled(viewModel.uiState).pins.map { it.id })
        viewModel.loadPins("r1")
        runCurrent()
        assertEquals(setOf("1", "2"), settled(viewModel.uiState).pins.map { it.id }.toSet())
    }

    @Test
    fun `un caricamento di una regione lasciata non pubblica i suoi segnalini`() = runTest {
        dao.rows += poiRow(1, "Uno", regionId = "r1", lat = 45.2, lon = 9.2)
        dao.rows += poiRow(3, "Tre", regionId = "r2", lat = 50.5, lon = 20.5)
        val gate = CompletableDeferred<Unit>()
        dao.gates["r1"] = gate
        val viewModel = viewModel()

        viewModel.loadPins("r1")
        runCurrent()
        viewModel.loadPins("r2")
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("3"), settled(viewModel.uiState).pins.map { it.id })
    }

    @Test
    fun `la sorgente della mappa e' ignota finche' non c'e' una regione`() = runTest {
        val viewModel = viewModel()
        val states = mutableListOf<MapSourceState?>()
        backgroundScope.launch { viewModel.uiState.collect { states += it.mapSource } }
        runCurrent()

        assertEquals(listOf<MapSourceState?>(null), states)
    }

    @Test
    fun `la sorgente della mappa porta il tipo e le versioni installate`() = runTest {
        tileSource.kinds["r1"] = MapSourceKind.FULL
        val viewModel = viewModel()

        viewModel.loadPins("r1")
        runCurrent()

        assertEquals(MapSourceState(MapSourceKind.FULL, listOf("m1", null, null)), settled(viewModel.uiState).mapSource)
    }

    @Test
    fun `una regione non installata ha la sorgente senza versioni`() = runTest {
        tileSource.kinds["r9"] = MapSourceKind.ONLINE_WORLD
        val viewModel = viewModel()

        viewModel.loadPins("r9")
        runCurrent()

        assertEquals(MapSourceState(MapSourceKind.ONLINE_WORLD, null), settled(viewModel.uiState).mapSource)
    }

    @Test
    fun `la sorgente si ricalcola quando la rete cambia e quando un pacchetto si installa`() = runTest {
        tileSource.kinds["r1"] = MapSourceKind.ONLINE_WORLD
        regions.installedRegions.value = listOf(installedRegion("r1", map = false))
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()
        assertEquals(MapSourceKind.ONLINE_WORLD, settled(viewModel.uiState).mapSource?.kind)

        // La rete cade: la sorgente si rilegge.
        tileSource.kinds["r1"] = MapSourceKind.NONE
        connectivity.online.value = false
        runCurrent()
        assertEquals(MapSourceKind.NONE, settled(viewModel.uiState).mapSource?.kind)

        // La mappa finisce di installarsi con la scheda aperta: cambia la versione e lo stile si ricostruisce.
        tileSource.kinds["r1"] = MapSourceKind.FULL
        regions.installedRegions.value = listOf(installedRegion("r1", map = true))
        runCurrent()
        assertEquals(MapSourceState(MapSourceKind.FULL, listOf("m1", null, null)), settled(viewModel.uiState).mapSource)
    }

    @Test
    fun `senza un riquadro aperto le partenze non si cercano`() = runTest {
        val viewModel = viewModel()

        viewModel.showDepartures(pin("railway=station"))
        runCurrent()

        assertNull(settled(viewModel.uiState).transitBoard)
        assertTrue(transit.requests.isEmpty())
    }

    @Test
    fun `le partenze di una stazione si leggono per i suoi mezzi e si rileggono ogni minuto`() = runTest {
        transit.boards["r1"] = TransitBoard.NoStops
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()

        viewModel.showDepartures(pin("railway=station"))
        runCurrent()
        assertEquals(1, transit.requests.size)
        assertEquals(setOf(TransitMode.TRAIN, TransitMode.METRO), transit.requests.single().stationModes)
        assertEquals("r1", transit.requests.single().regionId)
        assertEquals(TransitBoard.NoStops, settled(viewModel.uiState).transitBoard)

        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(1, transit.requests.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, transit.requests.size)
        // La rilettura ogni minuto non finisce mai da sola: senza chiuderla runTest non termina.
        viewModel.showDepartures(null)
    }

    @Test
    fun `chiudere il riquadro azzera il tabellone e ferma la rilettura`() = runTest {
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()
        viewModel.showDepartures(pin("amenity=bus_station"))
        runCurrent()
        assertEquals(setOf(TransitMode.BUS, TransitMode.TROLLEYBUS), transit.requests.single().stationModes)

        viewModel.showDepartures(null)
        advanceTimeBy(180_000)
        runCurrent()

        assertNull(settled(viewModel.uiState).transitBoard)
        assertEquals(1, transit.requests.size)
    }

    @Test
    fun `aprire un altro riquadro sostituisce la rilettura del precedente`() = runTest {
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()
        viewModel.showDepartures(pin("railway=station", id = "1"))
        runCurrent()

        viewModel.showDepartures(pin("amenity=ferry_terminal", id = "2"))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        // Una lettura per riquadro all'apertura e una del solo secondo dopo un minuto.
        assertEquals(
            listOf(setOf(TransitMode.TRAIN, TransitMode.METRO), setOf(TransitMode.FERRY), setOf(TransitMode.FERRY)),
            transit.requests.map { it.stationModes },
        )
        viewModel.showDepartures(null)
    }

    private val rtNetwork = TransitFeedInfo("renfe", "Renfe", "Renfe", realtimeUrl = "https://rt.example/renfe.pb", realtimeSizeBytes = 30_000)
    private val rtFeed = RealtimeFeed(null, emptyMap())
    private val rtBoard = TransitBoard.Departures(emptyList(), LocalDate.parse("2026-11-01"), 10, listOf(rtNetwork))

    @Test
    fun `con la rete il tabellone si rilegge con il feed del tempo reale, e Aggiorna i ritardi non aspetta il minuto`() = runTest {
        transit.boards["r1"] = rtBoard
        realtimeClient.feeds["https://rt.example/renfe.pb"] = rtFeed
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()

        viewModel.showDepartures(pin("railway=station"))
        runCurrent()
        // Prima l'orario, poi con il feed appena scaricato.
        assertEquals(listOf(emptyMap(), mapOf("renfe" to rtFeed)), transit.requests.map { it.realtime })
        assertEquals(1, realtimeClient.requests.size)
        assertEquals(false, settled(viewModel.uiState).transitRealtime?.loading)

        advanceTimeBy(30_000)
        realtimeClock.now += java.time.Duration.ofSeconds(30)
        viewModel.refreshRealtime()
        runCurrent()
        assertEquals(2, realtimeClient.requests.size)
        viewModel.showDepartures(null)
        assertNull(settled(viewModel.uiState).transitRealtime)
    }

    @Test
    fun `un tocco rimasto in coda non scarica i feed grandi della fermata dopo`() = runTest {
        val large = rtNetwork.copy(id = "vbb", realtimeUrl = "https://rt.example/vbb.pb", realtimeSizeBytes = 9_000_000)
        transit.boards["r1"] = rtBoard.copy(feeds = listOf(large))
        realtimeClient.feeds["https://rt.example/vbb.pb"] = rtFeed
        realtimeConnectivity.metered = true
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()

        viewModel.refreshRealtime()
        viewModel.showDepartures(pin("railway=station"))
        runCurrent()
        assertTrue(realtimeClient.requests.isEmpty())
        assertEquals(9_000_000L, settled(viewModel.uiState).transitRealtime?.tapSizeBytes)
        viewModel.showDepartures(null)
    }

    @Test
    fun `persa la rete con il riquadro aperto il tabellone torna subito l'orario`() = runTest {
        transit.boards["r1"] = rtBoard
        realtimeClient.feeds["https://rt.example/renfe.pb"] = rtFeed
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()
        viewModel.showDepartures(pin("railway=station"))
        runCurrent()
        assertEquals(mapOf("renfe" to rtFeed), transit.requests.last().realtime)

        realtimeConnectivity.online = false
        connectivity.online.value = false
        runCurrent()
        assertEquals(emptyMap<String, RealtimeFeed>(), transit.requests.last().realtime)
        assertNull(settled(viewModel.uiState).transitRealtime)
        // Nessun download in piu': il cambio di rete non vale come un tocco.
        assertEquals(1, realtimeClient.requests.size)
        viewModel.showDepartures(null)
    }

    @Test
    fun `senza rete nessun download e il tabellone resta l'orario`() = runTest {
        transit.boards["r1"] = rtBoard
        realtimeConnectivity.online = false
        val viewModel = viewModel()
        viewModel.loadPins("r1")
        runCurrent()

        viewModel.showDepartures(pin("railway=station"))
        runCurrent()
        assertTrue(realtimeClient.requests.isEmpty())
        assertEquals(listOf(emptyMap<String, RealtimeFeed>()), transit.requests.map { it.realtime })
        assertNull(settled(viewModel.uiState).transitRealtime)
        viewModel.showDepartures(null)
    }
}
