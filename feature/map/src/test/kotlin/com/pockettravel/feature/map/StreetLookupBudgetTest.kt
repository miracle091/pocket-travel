package com.pockettravel.feature.map

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
}
