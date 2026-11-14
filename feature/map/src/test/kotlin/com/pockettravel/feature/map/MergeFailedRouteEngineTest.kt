package com.pockettravel.feature.map

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeFailedRouteEngineTest {

    private val from = RoutePoint(43.94, 12.45)
    private val to = RoutePoint(44.06, 12.56)

    private fun answering(result: RouteResult) = object : RouteEngine {
        override suspend fun route(
            from: RoutePoint,
            to: RoutePoint,
            profile: String?,
            profileParams: Map<String, String>,
            onProgress: (Double) -> Unit,
        ): RouteResult = result
    }

    @Test
    fun `senza i dati della prima regione dice che l'unione non e' riuscita`() = runBlocking {
        assertEquals(RouteResult.MergeFailed, MergeFailedRouteEngine(answering(RouteResult.NoRoutingData)).route(from, to))
    }

    @Test
    fun `un percorso dentro la prima regione resta valido`() = runBlocking {
        val found = RouteResult.Found(Route(listOf(from, to), distanceMeters = 15_000.0, durationSeconds = 900.0))

        assertEquals(found, MergeFailedRouteEngine(answering(found)).route(from, to))
    }

    @Test
    fun `gli altri errori restano com'erano`() = runBlocking {
        assertEquals(RouteResult.TimedOut, MergeFailedRouteEngine(answering(RouteResult.TimedOut)).route(from, to))
    }
}
