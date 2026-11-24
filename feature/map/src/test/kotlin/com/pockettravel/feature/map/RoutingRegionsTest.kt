package com.pockettravel.feature.map

import com.pockettravel.core.data.MapBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingRegionsTest {
    private val installed = listOf(
        installedRegion("italia"),
        installedRegion("francia"),
        installedRegion("svizzera", routing = false),
    )
    private val bounds = MapBounds(6.0, 36.0, 18.0, 47.0)

    @Test
    fun `in auto contano tutte le regioni con la rete stradale`() {
        val regions = routingRegions(installed, setOf("francia"), RouteProfile.CAR) { if (it == "italia") bounds else null }
        assertEquals(listOf(RoutingRegion("italia", bounds), RoutingRegion("francia", null)), regions)
    }

    @Test
    fun `a piedi le regioni con la rete solo auto non contano`() {
        val regions = routingRegions(installed, setOf("francia"), RouteProfile.WALK) { bounds }
        assertEquals(listOf(RoutingRegion("italia", bounds)), regions)
    }
}
