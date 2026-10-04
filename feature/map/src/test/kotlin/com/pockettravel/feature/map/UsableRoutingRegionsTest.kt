package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class UsableRoutingRegionsTest {

    private val withRouting = setOf("italia", "francia")
    private val carOnly = setOf("italia")

    @Test
    fun `in auto valgono anche i percorsi solo per l'auto`() {
        assertEquals(withRouting, usableRoutingRegions(withRouting, carOnly, RouteProfile.CAR))
    }

    @Test
    fun `a piedi e in bici i percorsi solo per l'auto non valgono`() {
        assertEquals(setOf("francia"), usableRoutingRegions(withRouting, carOnly, RouteProfile.WALK))
        assertEquals(setOf("francia"), usableRoutingRegions(withRouting, carOnly, RouteProfile.BIKE))
    }
}
