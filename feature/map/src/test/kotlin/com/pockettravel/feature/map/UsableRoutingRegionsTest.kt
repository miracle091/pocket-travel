package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class UsableRoutingRegionsTest {

    private val withRouting = setOf("italia", "francia")
    private val carOnly = setOf("italia")

    @Test
    fun `in auto vale anche la rete stradale solo auto`() {
        assertEquals(withRouting, usableRoutingRegions(withRouting, carOnly, RouteProfile.CAR))
    }

    @Test
    fun `a piedi e in bici la rete stradale solo auto non vale`() {
        assertEquals(setOf("francia"), usableRoutingRegions(withRouting, carOnly, RouteProfile.WALK))
        assertEquals(setOf("francia"), usableRoutingRegions(withRouting, carOnly, RouteProfile.BIKE))
    }
}
