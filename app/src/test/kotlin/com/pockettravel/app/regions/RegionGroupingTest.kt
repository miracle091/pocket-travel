package com.pockettravel.app.regions

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionGroupingTest {
    private fun region(name: String, continent: String?) =
        RegionUiItem(regionId = name.lowercase(), displayName = name, sizeBytes = 0, status = RegionStatus.NOT_INSTALLED, continent = continent)

    @Test
    fun `continenti nell'ordine della pipeline, regioni in ordine alfabetico, senza continente in coda`() {
        val groups = groupByContinent(
            listOf(
                region("Giappone", "Asia"),
                region("Vecchia regione", null),
                region("San Marino", "Europa"),
                region("Andorra", "Europa"),
                region("Cina", "Asia"),
                region("Égypte", "Africa"),
            ),
        )

        assertEquals(listOf("Europa", "Asia", "Africa", null), groups.map { it.first })
        assertEquals(listOf("Andorra", "San Marino"), groups[0].second.map { it.displayName })
        assertEquals(listOf("Cina", "Giappone"), groups[1].second.map { it.displayName })
    }
}
