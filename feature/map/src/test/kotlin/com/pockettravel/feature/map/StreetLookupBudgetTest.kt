package com.pockettravel.feature.map

import com.pockettravel.core.data.MapBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class StreetLookupBudgetTest {
    @Test
    fun `tra due ricerche passano almeno cinque secondi`() {
        val budget = StreetLookupBudget()
        assertEquals(listOf(1, 2), budget.due(listOf(1, 2), nowMillis = 0))
        budget.record(listOf(1, 2), found = listOf(1), nowMillis = 0)

        assertEquals(emptyList<Int>(), budget.due(listOf(2), nowMillis = 4_999))
        assertEquals(listOf(2), budget.due(listOf(2), nowMillis = 5_000))
    }

    @Test
    fun `una svolta senza nome si smette di cercare dopo tre tentativi`() {
        val budget = StreetLookupBudget()
        var now = 0L
        repeat(3) {
            assertEquals(listOf(7), budget.due(listOf(7), now))
            budget.record(listOf(7), found = emptyList(), nowMillis = now)
            now += 5_000
        }

        assertEquals(emptyList<Int>(), budget.due(listOf(7), now))
        assertEquals(listOf(8), budget.due(listOf(7, 8), now))
    }

    @Test
    fun `si cercano solo le svolte entro un chilometro e mezzo dalla posizione`() {
        val position = RoutePoint(latitude = 41.9, longitude = 12.5)
        // Un centesimo di grado di latitudine: circa 1,1 km.
        val probes = listOf(1 to RoutePoint(41.91, 12.5), 2 to RoutePoint(41.92, 12.5), 3 to RoutePoint(41.89, 12.5))

        assertEquals(listOf(1, 3), StreetLookupBudget.nearTurns(probes, position))
    }

    @Test
    fun `solo le svolte con la sonda nell'area letta consumano un tentativo`() {
        val area = MapBounds(minLon = 12.4, minLat = 41.8, maxLon = 12.6, maxLat = 42.0)
        val probes = listOf(1 to RoutePoint(41.9, 12.5), 2 to RoutePoint(43.0, 12.5))
        val budget = StreetLookupBudget()

        repeat(5) { attempt ->
            val inside = StreetLookupBudget.inArea(probes, area)
            assertEquals(listOf(1), inside.map { it.first })
            budget.record(inside.map { it.first }, found = emptyList(), nowMillis = attempt * 5_000L)
        }

        assertEquals(listOf(2), budget.due(listOf(1, 2), nowMillis = 100_000))
    }
}
