package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationFormattingTest {
    private fun minutes(value: Int) = formatMinutes(value) { id, args -> "$id:${args.toList()}" }

    private fun distance(meters: Double) = formatDistance(meters) { id, args -> "$id:${args.toList()}" }

    @Test
    fun `sotto l'ora i minuti, da un'ora in poi ore e minuti`() {
        assertEquals("${R.string.navigation_minutes}:[59]", minutes(59))
        assertEquals("${R.string.navigation_hours_minutes}:[1, 0]", minutes(60))
        assertEquals("${R.string.navigation_hours_minutes}:[2, 5]", minutes(125))
    }

    @Test
    fun `la distanza ha le stesse etichette di distanceLabel`() {
        assertEquals("${R.string.navigation_meters}:[250]", distance(254.0))
        assertEquals("${R.string.navigation_meters}:[990]", distance(990.0))
        assertEquals(R.string.navigation_kilometers.toString(), distance(996.0).substringBefore(':'))
    }
}
