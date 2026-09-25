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

    private fun state(label: String) = RegionUiItem(
        regionId = "stati-uniti-" + label.lowercase(), displayName = "Stati Uniti - $label", sizeBytes = 0,
        status = RegionStatus.NOT_INSTALLED, continent = "Nord America", countryCode = "us",
        groupName = "Stati Uniti d'America", groupLabel = label,
    )

    @Test
    fun `le regioni dello stesso paese diventano una voce sola, in ordine per nome`() {
        val entries = countryEntries(listOf(state("Texas"), region("Messico", "Nord America"), state("Alaska"), region("Canada", "Nord America")))

        assertEquals(listOf("Canada", "Messico", "Stati Uniti d'America"), entries.map {
            when (it) {
                is RegionListEntry.Single -> it.item.displayName
                is RegionListEntry.Country -> it.name
            }
        })
        assertEquals(listOf("Alaska", "Texas"), (entries[2] as RegionListEntry.Country).items.map { it.groupLabel })
    }

    @Test
    fun `un gruppo con una sola regione resta una riga normale`() {
        assertEquals(RegionListEntry.Single(state("Ohio")), countryEntries(listOf(state("Ohio"))).single())
    }

    @Test
    fun `un paese chiuso mostra solo la sua riga, aperto anche le regioni`() {
        val entries = countryEntries(listOf(state("Texas"), state("Ohio"), region("Messico", "Nord America")))

        assertEquals(2, visibleRows(entries) { false }.size)
        assertEquals(
            listOf("country_Stati Uniti d'America", "stati-uniti-ohio", "stati-uniti-texas"),
            visibleRows(entries) { true }.drop(1).map { it.key },
        )
    }
}
