package com.pockettravel.feature.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.cos

/** Il tempo che manca all'arrivo viene dai tempi di BRouter lungo il percorso, non dalla velocita' media. */
class RemainingTimeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Punto a [x] metri verso est da (45, 10), con la stessa proiezione piana del tracker.
    private fun east(x: Double) = RoutePoint(45.0, 10.0 + x / (METERS_PER_DEGREE * cos(Math.toRadians(45.0))))

    @Test
    fun `il tempo restante segue i tempi dei punti del percorso`() {
        // 1 km in centro a 10 km/h (360 s), poi 1 km fuori a 90 km/h (40 s): a meta' strada mancano 40 s, non 200.
        val route = Route(listOf(east(0.0), east(1000.0), east(2000.0)), 2000.0, 400.0, pointSeconds = listOf(0.0, 360.0, 400.0))
        val tracker = NavigationTracker(route)

        assertEquals(400.0, tracker.progress(east(0.0)).remainingSeconds, 1.0)
        assertEquals(220.0, NavigationTracker(route).progress(east(500.0)).remainingSeconds, 1.0)
        assertEquals(40.0, NavigationTracker(route).progress(east(1000.0)).remainingSeconds, 1.0)
        assertEquals(20.0, NavigationTracker(route).progress(east(1500.0)).remainingSeconds, 1.0)
    }

    @Test
    fun `senza i tempi dei punti il tempo restante e' in proporzione ai metri`() {
        val route = Route(listOf(east(0.0), east(1000.0), east(2000.0)), 2000.0, 400.0)

        assertEquals(200.0, NavigationTracker(route).progress(east(1000.0)).remainingSeconds, 1.0)
    }

    @Test
    fun `su un percorso vero il tempo restante combacia con quello di BRouter`() = runBlocking {
        val profiles = tempFolder.newFolder().also { dir ->
            listOf("car-vario.brf", "lookups.dat").forEach { File("src/main/assets/brouter-profile", it).copyTo(File(dir, it)) }
        }
        val segments = tempFolder.newFolder().also { dir ->
            requireNotNull(javaClass.getResourceAsStream("/bioko-segments/E5_N0.rd5")) { "E5_N0.rd5 di Bioko mancante" }.use { input ->
                File(dir, "E5_N0.rd5").outputStream().use { input.copyTo(it) }
            }
        }
        val engine = BRouterRouteEngine(segments, profiles, "car-vario", ioDispatcher = Dispatchers.IO, defaultDispatcher = Dispatchers.Default)
        // Malabo, dal centro verso sud-ovest: strade di citta' e strade veloci, velocita' diverse lungo il percorso.
        val route = requireNotNull((engine.route(RoutePoint(3.7520, 8.7740), RoutePoint(3.7300, 8.7500)) as? RouteResult.Found)?.route) { "nessun percorso" }

        assertEquals(route.points.size, route.pointSeconds.size)
        assertEquals(0.0, route.pointSeconds.first(), 0.0)
        assertEquals(route.durationSeconds, route.pointSeconds.last(), 1.0)
        assertTrue("tempi crescenti", route.pointSeconds.zipWithNext().all { (a, b) -> b >= a })
        val middle = route.points.size / 2
        val remaining = NavigationTracker(route).progress(route.points[middle]).remainingSeconds
        assertEquals(route.pointSeconds.last() - route.pointSeconds[middle], remaining, 1.0)
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_194.9
    }
}
