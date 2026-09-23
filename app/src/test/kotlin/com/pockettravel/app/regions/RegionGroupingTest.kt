package com.pockettravel.app.regions

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionGroupingTest {
    private fun region(name: String, continent: String?, status: RegionStatus = RegionStatus.NOT_INSTALLED) =
        RegionUiItem(regionId = name.lowercase(), displayName = name, sizeBytes = 0, status = status, continent = continent)

    @Test
    fun `continenti nell'ordine della pipeline, regioni in ordine alfabetico, senza continente in coda`() {
        val groups = groupRegions(
            listOf(
                region("Giappone", "Asia"),
                region("Vecchia regione", null),
                region("San Marino", "Europa"),
                region("Andorra", "Europa"),
                region("Cina", "Asia"),
                region("Égypte", "Africa"),
            ),
        )

        assertEquals(
            listOf("Europa", "Asia", "Africa", null),
            groups.map { (it.first as RegionGroup.Continent).name },
        )
        assertEquals(listOf("Andorra", "San Marino"), groups[0].second.map { it.displayName })
        assertEquals(listOf("Cina", "Giappone"), groups[1].second.map { it.displayName })
    }

    @Test
    fun `le nazioni scaricate o da aggiornare stanno in cima, prima quelle da aggiornare`() {
        val groups = groupRegions(
            listOf(
                region("Andorra", "Europa"),
                region("San Marino", "Europa", RegionStatus.INSTALLED),
                region("Italia", "Europa", RegionStatus.UPDATE_AVAILABLE),
                region("Belgio", "Europa", RegionStatus.INSTALLED),
            ),
        )

        assertEquals(RegionGroup.Downloaded, groups[0].first)
        assertEquals(listOf("Italia", "Belgio", "San Marino"), groups[0].second.map { it.displayName })
        assertEquals(listOf("Andorra"), groups[1].second.map { it.displayName })
    }
}
