package com.pockettravel.feature.map

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandRetryRouteEngineTest {

    private val from = RoutePoint(3.7520, 8.7740)
    private val to = RoutePoint(3.7553, 8.7087)

    /** Risponde con [answer] e tiene le richieste. */
    private class FakeEngine(private val answer: (RoutePoint, RoutePoint) -> RouteResult) : RouteEngine {
        val requests = mutableListOf<Pair<RoutePoint, RoutePoint>>()

        override suspend fun route(
            from: RoutePoint,
            to: RoutePoint,
            profile: String?,
            profileParams: Map<String, String>,
            onProgress: (Double) -> Unit,
        ): RouteResult {
            requests += from to to
            return answer(from, to)
        }
    }

    private fun found(from: RoutePoint, to: RoutePoint) = RouteResult.Found(Route(listOf(from, to), distanceMeters = 1000.0, durationSeconds = 60.0))

    @Test
    fun `senza isola il risultato resta quello del motore, con un solo calcolo`() = runBlocking {
        for (answer in listOf(found(from, to), RouteResult.NotFound, RouteResult.Failed("no track found at pass=0"))) {
            val inner = FakeEngine { _, _ -> answer }

            assertEquals(answer, IslandRetryRouteEngine(inner).route(from, to))
            assertEquals(1, inner.requests.size)
        }
    }

    @Test
    fun `destinazione isolata anche spostandola di 2 km in ogni direzione`() = runBlocking {
        val inner = FakeEngine { _, _ -> RouteResult.Failed("target island detected for section 0") }

        assertEquals(RouteResult.Isolated(atStart = false), IslandRetryRouteEngine(inner).route(from, to))
        // La richiesta, poi 4 distanze per 8 direzioni, sempre con la stessa partenza.
        assertEquals(33, inner.requests.size)
        assertTrue(inner.requests.all { it.first == from })
    }

    @Test
    fun `partenza isolata con un punto spostato in mare passa al punto dopo`() = runBlocking {
        var moved = 0
        val inner = FakeEngine { start, end ->
            when {
                start == from -> RouteResult.Failed("start island detected (no track found at pass=0)")
                moved++ == 0 -> RouteResult.NoRoutingData
                else -> found(start, end)
            }
        }

        val route = (IslandRetryRouteEngine(inner).route(from, to) as RouteResult.Found).route

        assertEquals(3, inner.requests.size)
        assertEquals(inner.requests.last().first, route.points.first())
        // Due punti a 250 m: il primo in mare, il secondo sulla strada.
        assertEquals(250.0, route.startOffsetMeters, 1.0)
        assertEquals(0.0, route.endOffsetMeters, 0.0)
    }

    @Test
    fun `il primo punto provato e' a 250 m verso l'altro estremo`() {
        val first = IslandRetryRouteEngine.candidates(to, toward = from).first()

        assertEquals(250.0, NavigationTracker.distanceMeters(to, first), 1.0)
        // from e' a est di to: il punto si sposta a est, alla stessa latitudine.
        assertTrue(first.longitude > to.longitude)
        assertEquals(to.latitude, first.latitude, 0.001)
    }
}
