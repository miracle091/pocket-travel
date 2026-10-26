package com.pockettravel.feature.map

import androidx.lifecycle.SavedStateHandle
import com.pockettravel.core.data.MapBounds
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.feature.map.testing.FakeGpsLocationSource
import com.pockettravel.feature.map.testing.FakeGuidanceServiceStarter
import com.pockettravel.feature.map.testing.FakeOfflineTileSource
import com.pockettravel.feature.map.testing.FakeRouteSimulationLog
import com.pockettravel.feature.map.testing.ScriptedRouteEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Orologio che parte da un'ora fissa e avanza con il tempo virtuale di [scheduler]. */
@OptIn(ExperimentalCoroutinesApi::class)
private class SchedulerClock(private val scheduler: TestCoroutineScheduler) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(millis())

    override fun millis(): Long = START_MILLIS + scheduler.currentTime

    private companion object {
        val START_MILLIS = Instant.parse("2026-10-12T12:00:00Z").toEpochMilli()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationViewModelTest {
    // Come Main.immediate: le posizioni del GPS e i calcoli si propagano subito.
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    // Il tempo del test e' quello virtuale dello scheduler: il ViewModel e il GPS finto vedono lo stesso orologio.
    private val clock = SchedulerClock(main.dispatcher.scheduler)
    private val files = InMemoryPreferencesFactory()
    private val engines = ScriptedRouteEngineFactory()
    private val regions = FakeRegionRepository(listOf(installedRegion("r1", countryCode = "IT")))
    private val gps = FakeGpsLocationSource()
    private val tileSource = FakeOfflineTileSource().apply { bounds["r1"] = MapBounds(9.0, 44.0, 11.0, 46.0) }
    private val variants = RoutingVariantPreferences(files)
    private val usageModes = UsageModePreferences(files, MapFilterPreferences(files), variants)
    private val session = NavigationSession()
    private val preferences = NavigationPreferences(files)
    // La nazionalita' e' gia' salvata: senza, le preferenze la chiederebbero alle risorse di sistema di Android.
    private val nationality = NationalityPreferences(files.also { it.open("profile").edit().putString("nationality", "IT").commit() })
    private val starter = FakeGuidanceServiceStarter()
    private val simulationLog = FakeRouteSimulationLog()
    private var handle = SavedStateHandle()
    private var host: ViewModelHost<NavigationViewModel>? = null

    // Percorso a L come in NavigationTest: verso est (circa 393 m), poi verso nord (circa 445 m).
    private val origin = RoutePoint(45.0, 10.0)
    private val corner = RoutePoint(45.0, 10.005)
    private val end = RoutePoint(45.004, 10.005)
    private val route = Route(
        points = listOf(origin, corner, end),
        distanceMeters = 838.0,
        durationSeconds = 600.0,
        instructions = listOf(TurnInstruction(TurnType.LEFT, 445.0, 1), TurnInstruction(TurnType.ARRIVE, 0.0, 2)),
    )
    private val destination = NavigationPlace("Destinazione", end.latitude, end.longitude, "r1")
    private val arrival = LocalDateTime.of(2026, 10, 12, 18, 0)

    @After
    fun close() {
        host?.clear()
    }

    private fun TestScope.viewModel(): NavigationViewModel {
        val created = ViewModelHost(NavigationViewModel::class.java) {
            NavigationViewModel(
                handle, engines, regions, gps, tileSource, usageModes, session, preferences, variants, nationality, starter,
                simulationLog, main.dispatcher, clock,
            )
        }
        host = created
        keepCollecting(created.viewModel.uiState)
        return created.viewModel
    }

    private fun fix(point: RoutePoint, timeMillis: Long = clock.millis(), accuracy: Float = 5f) =
        GpsFix(point.latitude, point.longitude, accuracy, timeMillis)

    private fun minutes(time: LocalDateTime) = time.toEpochSecond(ZoneOffset.UTC) / 60

    private fun NavigationViewModel.begin(mode: RouteProfile = RouteProfile.CAR, preview: PlannerPreview.Ready? = null) =
        start("r1", destination, mode, arrival, preview)

    // --- Stati iniziali ---

    @Test
    fun `senza navigazione in corso non c'e' destinazione ne proposta di ripresa e il GPS non si ascolta`() = runTest {
        val viewModel = viewModel()
        runCurrent()

        assertNull(viewModel.uiState.value.target)
        assertNull(viewModel.uiState.value.resumeOffer)
        assertEquals(0, gps.listeners)
        assertEquals(NavigationStatus.WaitingForFix, viewModel.uiState.value.status)
    }

    @Test
    fun `senza permesso o con il GPS spento la navigazione lo dice`() = runTest {
        gps.permission = false
        val viewModel = viewModel()
        runCurrent()
        assertEquals(NavigationStatus.NeedsPermission, viewModel.uiState.value.status)

        gps.permission = true
        viewModel.onPermissionResult(true)
        gps.enabled.value = false
        runCurrent()
        assertEquals(NavigationStatus.GpsDisabled, viewModel.uiState.value.status)

        gps.enabled.value = true
        runCurrent()
        assertEquals(NavigationStatus.WaitingForFix, viewModel.uiState.value.status)
    }

    @Test
    fun `la vibrazione a piedi segue le impostazioni`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.walkingHaptics)

        preferences.setWalkingHaptics(false)

        assertFalse(viewModel.uiState.value.walkingHaptics)
    }

    // --- Avvio e fine della navigazione ---

    @Test
    fun `Avvia imposta la destinazione, avvisa la notifica, parte il servizio e salva la navigazione`() = runTest {
        val viewModel = viewModel()

        viewModel.begin(RouteProfile.CAR)

        assertEquals(destination, viewModel.uiState.value.target)
        assertEquals(RouteProfile.CAR, viewModel.uiState.value.routeProfile)
        assertEquals(arrival, viewModel.uiState.value.arriveBy)
        assertEquals(listOf("r1"), viewModel.uiState.value.regionIds)
        assertEquals(NavigationSnapshot("Destinazione", null, 0.0, 0.0, 0.0, waitingForFix = true), session.snapshot.value)
        assertEquals(1, starter.starts)
        assertEquals(1, gps.listeners)
        val saved = preferences.activeNavigation(clock.millis())
        assertEquals(destination, saved?.place)
        assertEquals(RouteProfile.CAR, saved?.mode)
        assertEquals("r1", saved?.regionId)
        assertEquals(minutes(arrival), saved?.arriveByEpochMinute)
        assertEquals(destination, handle.get<String>("destination")?.toPlace())
        assertEquals("r1", handle.get<String>("regionId"))
        assertEquals("CAR", handle.get<String>("mode"))
    }

    @Test
    fun `senza permesso il servizio parte solo quando il permesso arriva`() = runTest {
        gps.permission = false
        val viewModel = viewModel()
        viewModel.onPermissionResult(true)
        assertEquals("senza navigazione il permesso non avvia nulla", 0, starter.starts)

        viewModel.begin()
        assertEquals(0, starter.starts)
        assertEquals(0, gps.listeners)

        viewModel.onPermissionResult(false)
        assertEquals(0, starter.starts)
        gps.permission = true
        viewModel.onPermissionResult(true)
        assertEquals(1, starter.starts)
        assertEquals(1, gps.listeners)
    }

    @Test
    fun `Termina chiude la navigazione, svuota la notifica, il disco e lo stato salvato e ferma il GPS`() = runTest {
        val viewModel = viewModel()
        viewModel.begin()

        viewModel.stop()

        assertNull(viewModel.uiState.value.target)
        assertNull(viewModel.uiState.value.arriveBy)
        assertNull(session.snapshot.value)
        assertNull(preferences.activeNavigation(clock.millis()))
        assertNull(handle.get<String>("destination"))
        assertEquals(0, gps.listeners)
    }

    @Test
    fun `Termina dalla notifica chiude la navigazione in corso`() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.begin()

        session.requestStop()
        runCurrent()

        assertNull(viewModel.uiState.value.target)
        assertNull(session.snapshot.value)
    }

    @Test
    fun `chiudere l'hub con una navigazione in corso toglie la notifica, senza navigazione non tocca nulla`() = runTest {
        val viewModel = viewModel()
        viewModel.begin()
        assertNotNull(session.snapshot.value)

        host?.clear()

        assertNull(session.snapshot.value)

        val other = NavigationSnapshot("Altro", null, 0.0, 0.0, 0.0)
        session.update(other)
        viewModel().also { host?.clear() }
        assertEquals(other, session.snapshot.value)
    }

    // --- Calcolo e navigazione ---

    @Test
    fun `alla prima posizione il percorso si calcola dal punto GPS e poi si naviga`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        engines.gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.begin(RouteProfile.CAR)

        gps.emit(fix(origin))
        runCurrent()

        assertTrue(viewModel.uiState.value.status is NavigationStatus.Calculating)
        assertEquals(NavigationSnapshot("Destinazione", null, 0.0, 0.0, 0.0), session.snapshot.value)
        val request = engines.requests.single()
        assertEquals(origin, request.from)
        assertEquals(destination.point, request.to)
        assertEquals("car-vario", request.profile)
        assertEquals(listOf(listOf("r1")), engines.created)

        engines.gate?.complete(Unit)
        runCurrent()

        val state = viewModel.uiState.value.status as NavigationStatus.Navigating
        assertEquals(route, state.route)
        assertEquals(TurnType.LEFT, state.progress.nextInstruction.type)
        assertFalse(state.signalLost)
        // La notifica passa dal calcolo alla svolta appena il percorso c'e', senza aspettare la posizione successiva.
        val snapshot = session.snapshot.value
        assertEquals(TurnType.LEFT, snapshot?.turn?.type)
        assertEquals(838.0, snapshot?.remainingMeters ?: 0.0, 5.0)
        assertEquals(listOf(route), simulationLog.routes)
    }

    @Test
    fun `l'avanzamento del motore arriva alla schermata`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        engines.progressSteps = listOf(0.6)
        engines.gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.begin()

        gps.emit(fix(origin))
        runCurrent()

        assertEquals(0.6, (viewModel.uiState.value.status as NavigationStatus.Calculating).progress, 0.0)
        engines.gate?.complete(Unit)
    }

    @Test
    fun `a piedi in sedia a rotelle il motore riceve il profilo apposta`() = runTest {
        usageModes.setAccessible(true)
        usageModes.setAllowSteps(true)
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.begin(RouteProfile.WALK)

        gps.emit(fix(origin))
        runCurrent()

        assertEquals("wheelchair", engines.requests.single().profile)
        assertEquals(mapOf("allow_steps" to "1"), engines.requests.single().params)
    }

    @Test
    fun `se il percorso non si trova la schermata lo dice e Riprova ricalcola dall'ultima posizione`() = runTest {
        engines.defaultResult = RouteResult.NotFound
        val viewModel = viewModel()
        viewModel.begin()
        gps.emit(fix(origin))
        runCurrent()
        assertEquals(NavigationStatus.Unavailable(RouteResult.NotFound), viewModel.uiState.value.status)

        engines.defaultResult = RouteResult.Found(route)
        viewModel.retry()
        runCurrent()

        assertTrue(viewModel.uiState.value.status is NavigationStatus.Navigating)
        assertEquals(2, engines.requests.size)
    }

    @Test
    fun `Riprova durante un calcolo non ne avvia un altro, senza posizione non fa nulla`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.begin()
        viewModel.retry()
        assertTrue(engines.requests.isEmpty())

        engines.gate = CompletableDeferred()
        gps.emit(fix(origin))
        runCurrent()
        viewModel.retry()
        runCurrent()

        assertEquals(1, engines.requests.size)
        engines.gate?.complete(Unit)
    }

    @Test
    fun `l'anteprima del percorso gia' calcolata evita il calcolo se la posizione e' sul percorso`() = runTest {
        val viewModel = viewModel()
        viewModel.begin(preview = PlannerPreview.Ready(route, listOf("r1", "r2")))
        assertEquals(listOf("r1", "r2"), viewModel.uiState.value.regionIds)

        gps.emit(fix(origin))
        runCurrent()

        assertTrue(engines.requests.isEmpty())
        assertEquals(route, (viewModel.uiState.value.status as NavigationStatus.Navigating).route)
        assertEquals(listOf(route), simulationLog.routes)
    }

    @Test
    fun `con l'anteprima del percorso ma la posizione lontana dal percorso si ricalcola`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.begin(preview = PlannerPreview.Ready(route, listOf("r1")))

        gps.emit(fix(RoutePoint(45.02, 10.02)))
        runCurrent()

        assertEquals(1, engines.requests.size)
    }

    @Test
    fun `fuori percorso si ricalcola dopo l'intervallo e un errore non butta il percorso buono`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.begin()
        val t0 = clock.millis()
        gps.emit(fix(origin, t0))
        runCurrent()
        assertEquals(1, engines.requests.size)

        // Fuori strada ma troppo presto dall'ultimo calcolo: niente ricalcolo.
        val off = RoutePoint(45.003, 10.0)
        gps.emit(fix(off, t0 + 5_000))
        runCurrent()
        assertEquals(1, engines.requests.size)

        // Passato l'intervallo si ricalcola; il tentativo fallisce e il percorso resta.
        engines.defaultResult = RouteResult.Failed("parcheggio")
        gps.emit(fix(off, t0 + 11_000))
        runCurrent()
        assertEquals(2, engines.requests.size)
        assertEquals(off, engines.requests.last().from)
        assertEquals(route, (viewModel.uiState.value.status as NavigationStatus.Navigating).route)

        // Un nuovo percorso trovato sostituisce il vecchio.
        val detour = route.copy(distanceMeters = 900.0)
        engines.defaultResult = RouteResult.Found(detour)
        gps.emit(fix(off, t0 + 22_000))
        runCurrent()
        assertEquals(detour, (viewModel.uiState.value.status as NavigationStatus.Navigating).route)
    }

    @Test
    fun `con una precisione scarsa fuori percorso non si ricalcola`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.begin()
        val t0 = clock.millis()
        gps.emit(fix(origin, t0))
        runCurrent()

        gps.emit(fix(RoutePoint(45.003, 10.0), t0 + 30_000, accuracy = 80f))
        runCurrent()

        assertEquals(1, engines.requests.size)
    }

    @Test
    fun `una posizione vecchia con il percorso gia' trovato segna il segnale perso`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.begin()
        gps.emit(fix(origin))
        runCurrent()

        gps.emit(fix(corner, clock.millis() - 60_000))
        runCurrent()

        val state = viewModel.uiState.value.status as NavigationStatus.Navigating
        assertTrue(state.signalLost)
        assertEquals(corner, state.position)
    }

    @Test
    fun `una posizione che invecchia senza nuove posizioni passa al segnale perso`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.begin()
        gps.emit(fix(origin))
        runCurrent()
        assertFalse((viewModel.uiState.value.status as NavigationStatus.Navigating).signalLost)

        advanceTimeBy(FIX_MAX_AGE_MILLIS + 2_000)
        runCurrent()

        val state = viewModel.uiState.value.status as NavigationStatus.Navigating
        assertTrue(state.signalLost)
        assertEquals(origin, state.position)
    }

    @Test
    fun `i nomi delle strade trovati dalla mappa arrivano alla notifica`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.begin()
        gps.emit(fix(origin))
        runCurrent()
        gps.emit(fix(origin))
        runCurrent()
        assertNull(session.snapshot.value?.street)

        viewModel.onStreetNames(mapOf(1 to "Via Verdi"))

        assertEquals("Via Verdi", session.snapshot.value?.street)
    }

    @Test
    fun `ripartire cancella il calcolo della navigazione precedente`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        engines.gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.begin()
        gps.emit(fix(origin))
        runCurrent()
        assertTrue(viewModel.uiState.value.status is NavigationStatus.Calculating)

        viewModel.start("r1", NavigationPlace("Altra", 45.01, 10.01, "r1"), RouteProfile.WALK)
        engines.gate?.complete(Unit)
        runCurrent()

        assertEquals("Altra", viewModel.uiState.value.target?.name)
        assertEquals(NavigationStatus.WaitingForFix, viewModel.uiState.value.status)
        assertEquals(RouteProfile.WALK, viewModel.uiState.value.routeProfile)
        assertNull(viewModel.uiState.value.arriveBy)
    }

    @Test
    fun `chiudere la navigazione durante il calcolo ne scarta il risultato`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        engines.gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.begin()
        gps.emit(fix(origin))
        runCurrent()

        viewModel.stop()
        engines.gate?.complete(Unit)
        runCurrent()

        assertNull(session.snapshot.value)
        assertFalse(viewModel.uiState.value.status is NavigationStatus.Navigating)
    }

    // --- Arrivo ---

    @Test
    fun `all'arrivo la navigazione si chiude, il GPS si spegne e il disco si svuota`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.begin()
        gps.emit(fix(origin))
        runCurrent()
        assertNull(viewModel.uiState.value.arrivedAtMillis)

        gps.emit(fix(end))
        runCurrent()

        assertEquals(NavigationStatus.Arrived, viewModel.uiState.value.status)
        assertNotNull(viewModel.uiState.value.arrivedAtMillis)
        assertNull(session.snapshot.value)
        assertNull(preferences.activeNavigation(clock.millis()))
        assertEquals(0, gps.listeners)
        // La destinazione resta fino a "Termina": e' il Navigatore a chiudere la schermata.
        assertEquals(destination, viewModel.uiState.value.target)
    }

    @Test
    fun `senza spegnere il GPS all'arrivo la navigazione resta aperta e l'arrivo si segna una volta`() = runTest {
        preferences.setStopGpsOnArrival(false)
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.begin()
        gps.emit(fix(origin))
        runCurrent()

        gps.emit(fix(end))
        runCurrent()
        val first = viewModel.uiState.value.arrivedAtMillis
        assertNotNull(first)
        assertTrue(viewModel.uiState.value.status is NavigationStatus.Navigating)
        assertEquals(1, gps.listeners)
        assertNotNull(preferences.activeNavigation(clock.millis()))

        gps.emit(fix(end, clock.millis() + 5))
        runCurrent()
        assertEquals(first, viewModel.uiState.value.arrivedAtMillis)
    }

    // --- Ripresa dopo la chiusura del processo ---

    @Test
    fun `dallo stato salvato da Android la navigazione interrotta si propone e riparte uguale`() = runTest {
        handle = SavedStateHandle(
            mapOf(
                "destination" to destination.toSavedState(), "regionId" to "r1", "mode" to "BIKE", "arriveBy" to minutes(arrival),
            ),
        )
        val viewModel = viewModel()
        assertEquals(destination, viewModel.uiState.value.resumeOffer)
        assertEquals(RouteProfile.BIKE, viewModel.uiState.value.routeProfile)
        assertEquals(minutes(arrival), viewModel.uiState.value.arriveBy?.let(::minutes))
        assertNull(viewModel.uiState.value.target)

        viewModel.resume()

        assertNull(viewModel.uiState.value.resumeOffer)
        assertEquals(destination, viewModel.uiState.value.target)
        assertEquals(RouteProfile.BIKE, viewModel.uiState.value.routeProfile)
        assertEquals(arrival, viewModel.uiState.value.arriveBy)
        assertEquals(1, starter.starts)
    }

    @Test
    fun `ricreato con lo stesso handle il ViewModel ritrova destinazione e modo e la navigazione riprende`() = runTest {
        viewModel().begin(RouteProfile.CAR)
        host?.clear()

        val recreated = viewModel()

        assertNull(recreated.uiState.value.target)
        assertEquals(destination, recreated.uiState.value.resumeOffer)
        assertEquals(RouteProfile.CAR, recreated.uiState.value.routeProfile)
        assertEquals(arrival, recreated.uiState.value.arriveBy)

        recreated.resume()

        assertEquals(destination, recreated.uiState.value.target)
        assertEquals(RouteProfile.CAR, recreated.uiState.value.routeProfile)
    }

    @Test
    fun `dal disco la navigazione interrotta riparte con regione, modo e ora salvati`() = runTest {
        preferences.saveActiveNavigation(
            ActiveNavigation("r1", destination, RouteProfile.CAR, minutes(arrival), clock.millis()),
        )
        val viewModel = viewModel()
        assertEquals(destination, viewModel.uiState.value.resumeOffer)

        viewModel.resume()

        assertEquals(destination, viewModel.uiState.value.target)
        assertEquals(RouteProfile.CAR, viewModel.uiState.value.routeProfile)
        assertEquals(arrival, viewModel.uiState.value.arriveBy)
        assertEquals(listOf("r1"), viewModel.uiState.value.regionIds)
    }

    @Test
    fun `una navigazione salvata da troppo tempo non si propone`() = runTest {
        preferences.saveActiveNavigation(
            ActiveNavigation("r1", destination, RouteProfile.CAR, null, clock.millis() - 13 * 60 * 60 * 1000L),
        )

        val viewModel = viewModel()

        assertNull(viewModel.uiState.value.resumeOffer)
    }

    @Test
    fun `No dimentica la navigazione interrotta ovunque sia salvata`() = runTest {
        handle = SavedStateHandle(mapOf("destination" to destination.toSavedState(), "arriveBy" to minutes(arrival)))
        preferences.saveActiveNavigation(ActiveNavigation("r1", destination, RouteProfile.CAR, null, clock.millis()))
        val viewModel = viewModel()

        viewModel.dismissResume()

        assertNull(viewModel.uiState.value.resumeOffer)
        assertNull(viewModel.uiState.value.arriveBy)
        assertNull(handle.get<String>("destination"))
        assertNull(preferences.activeNavigation(clock.millis()))
        viewModel.resume()
        assertEquals("senza proposta Riprendi non fa nulla", 0, starter.starts)
    }

    // --- Lato di guida ---

    @Test
    fun `nelle nazioni con un lato diverso da quello di casa compare l'avviso`() = runTest {
        regions.installedRegions.value = listOf(installedRegion("r1", countryCode = "GB"))
        val viewModel = viewModel()
        viewModel.begin()
        runCurrent()
        assertEquals(DrivingSide.LEFT, viewModel.uiState.value.drivingSideWarning)

        nationality.setNationality("GB")
        runCurrent()
        assertNull(viewModel.uiState.value.drivingSideWarning)
    }

    @Test
    fun `nella stessa nazione di casa o senza navigazione l'avviso non c'e'`() = runTest {
        val viewModel = viewModel()
        runCurrent()
        assertNull(viewModel.uiState.value.drivingSideWarning)

        viewModel.begin()
        runCurrent()

        assertNull(viewModel.uiState.value.drivingSideWarning)
    }
}
