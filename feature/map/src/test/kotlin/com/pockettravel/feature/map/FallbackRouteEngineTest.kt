package com.pockettravel.feature.map

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackRouteEngineTest {

    private val from = RoutePoint(50.88, 4.37)
    private val to = RoutePoint(50.89, 4.38)
    private val route = RouteResult.Found(Route(listOf(from, to), distanceMeters = 1200.0, durationSeconds = 900.0))
    private val island = RouteResult.Failed("target island detected for section 0")

    /** Risponde sempre [result] e conta le chiamate. */
    private class FakeEngine(private val result: RouteResult) : RouteEngine {
        var calls = 0

        override suspend fun route(
            from: RoutePoint,
            to: RoutePoint,
            profile: String?,
            profileParams: Map<String, String>,
            onProgress: (Double) -> Unit,
        ): RouteResult {
            calls++
            return result
        }
    }

    @Test
    fun `con i segmenti uniti trovati le regioni da sole non si provano`() = runBlocking {
        val single = FakeEngine(route)

        assertEquals(route, FallbackRouteEngine(FakeEngine(route), listOf(single)).route(from, to))
        assertEquals(0, single.calls)
    }

    @Test
    fun `con un'isola sui segmenti uniti vale il primo percorso trovato da una regione da sola`() = runBlocking {
        for (failure in listOf(island, RouteResult.Failed("start island detected for section 0"))) {
            val first = FakeEngine(RouteResult.NoRoutingData)
            val second = FakeEngine(route)
            val third = FakeEngine(route)

            assertEquals(route, FallbackRouteEngine(FakeEngine(failure), listOf(first, second, third)).route(from, to))
            assertEquals(listOf(1, 1, 0), listOf(first.calls, second.calls, third.calls))
        }
    }

    @Test
    fun `senza percorso nemmeno da sole resta l'errore dei segmenti uniti`() = runBlocking {
        val engine = FallbackRouteEngine(FakeEngine(island), listOf(FakeEngine(RouteResult.NoRoutingData), FakeEngine(RouteResult.NotFound)))

        assertEquals(island, engine.route(from, to))
    }

    // Dopo la ricerca completa (nessun percorso, tempo scaduto) ripeterla per ogni regione raddoppierebbe l'attesa.
    @Test
    fun `senza isola non si riprova`() = runBlocking {
        val noTrack = RouteResult.Failed("no track found at pass=0")
        for (result in listOf(noTrack, RouteResult.NotFound, RouteResult.TimedOut, RouteResult.NoRoutingData)) {
            val single = FakeEngine(route)

            assertEquals(result, FallbackRouteEngine(FakeEngine(result), listOf(single)).route(from, to))
            assertEquals(0, single.calls)
        }
    }
}
