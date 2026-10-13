package com.pockettravel.app.regions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionListGroupingEdgeCasesTest {
    private fun region(
        name: String,
        continent: String? = "Europa",
        status: RegionStatus = RegionStatus.NOT_INSTALLED,
        id: String = name.lowercase(),
    ) = RegionUiItem(regionId = id, displayName = name, sizeBytes = 0, status = status, continent = continent)

    private fun part(group: String, label: String?, country: String? = "us", name: String = "$group - $label") = RegionUiItem(
        regionId = name.lowercase().replace(" ", "-"), displayName = name, sizeBytes = 0, status = RegionStatus.NOT_INSTALLED,
        continent = "Nord America", countryCode = country, groupName = group, groupLabel = label,
    )

    private fun entryName(entry: RegionListEntry) = when (entry) {
        is RegionListEntry.Single -> entry.item.displayName
        is RegionListEntry.Country -> entry.name
    }

    @Test
    fun `senza regioni non ci sono ne' gruppi ne' voci ne' righe`() {
        assertEquals(emptyList<Pair<RegionGroup, List<RegionUiItem>>>(), groupRegions(emptyList()))
        assertEquals(emptyList<RegionListEntry>(), countryEntries(emptyList()))
        assertEquals(emptyList<ListRow>(), visibleRows(emptyList()) { true })
    }

    @Test
    fun `un continente sconosciuto sta dopo quelli noti e prima delle regioni senza continente`() {
        val groups = groupRegions(listOf(region("A", null), region("B", "Antartide"), region("C", "Oceania"), region("D", "Europa")))

        assertEquals(listOf("Europa", "Oceania", "Antartide", null), groups.map { (it.first as RegionGroup.Continent).name })
    }

    @Test
    fun `i continenti noti seguono l'ordine della pipeline anche se arrivano mescolati`() {
        val names = listOf("Oceania", "Sud America", "Nord America", "Africa", "Asia", "Europa")
        val groups = groupRegions(names.mapIndexed { i, c -> region("R$i", c) })

        assertEquals(names.reversed(), groups.map { (it.first as RegionGroup.Continent).name })
    }

    @Test
    fun `senza nazioni scaricate il gruppo delle scaricate non c'e'`() {
        val groups = groupRegions(listOf(region("Andorra"), region("Belgio")))

        assertEquals(1, groups.size)
        assertTrue(groups.none { it.first == RegionGroup.Downloaded })
    }

    @Test
    fun `con tutte le nazioni scaricate resta solo il gruppo delle scaricate, da aggiornare per prime`() {
        val groups = groupRegions(
            listOf(
                region("Zambia", status = RegionStatus.INSTALLED),
                region("Belgio", status = RegionStatus.UPDATE_AVAILABLE),
                region("Andorra", status = RegionStatus.INSTALLED),
                region("Albania", status = RegionStatus.UPDATE_AVAILABLE),
            ),
        )

        assertEquals(listOf(RegionGroup.Downloaded), groups.map { it.first })
        assertEquals(listOf("Albania", "Belgio", "Andorra", "Zambia"), groups.single().second.map { it.displayName })
    }

    @Test
    fun `una nazione gia' scaricata in download non compare due volte`() {
        val groups = groupRegions(
            listOf(region("Andorra", status = RegionStatus.INSTALLED), region("Belgio")),
            downloading = setOf("andorra", "sconosciuta"),
        )

        assertEquals(listOf("Andorra"), groups[0].second.map { it.displayName })
        assertEquals(listOf("Belgio"), groups[1].second.map { it.displayName })
        assertEquals(2, groups.size)
    }

    @Test
    fun `l'ordine alfabetico ignora maiuscole e accenti`() {
        val groups = groupRegions(listOf(region("Zambia"), region("égypte"), region("Algeria"), region("belgio")))

        assertEquals(listOf("Algeria", "belgio", "égypte", "Zambia"), groups.single().second.map { it.displayName })
    }

    @Test
    fun `le chiavi dei gruppi sono distinte, anche per il continente mancante`() {
        assertEquals("downloaded", RegionGroup.Downloaded.key)
        assertEquals("continent_Asia", RegionGroup.Continent("Asia").key)
        assertEquals("continent_", RegionGroup.Continent(null).key)
        assertNotEquals(RegionGroup.Continent("Asia").key, RegionGroup.Continent(null).key)
    }

    @Test
    fun `le regioni senza gruppo restano righe singole, anche se hanno lo stesso paese`() {
        val entries = countryEntries(listOf(region("Italia"), region("Francia")))

        assertTrue(entries.all { it is RegionListEntry.Single })
        assertEquals(listOf("Francia", "Italia"), entries.map(::entryName))
    }

    @Test
    fun `voci singole e nazioni divise si ordinano insieme per nome`() {
        val entries = countryEntries(
            listOf(part("Stati Uniti d'America", "Texas"), region("Svizzera"), part("Stati Uniti d'America", "Ohio"), region("Spagna")),
        )

        assertEquals(listOf("Spagna", "Stati Uniti d'America", "Svizzera"), entries.map(::entryName))
    }

    @Test
    fun `le regioni di una nazione si ordinano per etichetta, o per nome se manca`() {
        val entries = countryEntries(listOf(part("Francia", "Bretagna", "fr"), part("Francia", null, "fr", name = "Alsazia"), part("Francia", "Corsica", "fr")))
        val country = entries.single() as RegionListEntry.Country

        assertEquals(listOf("Alsazia", "Bretagna", "Corsica"), country.items.map { it.groupLabel ?: it.displayName })
    }

    @Test
    fun `la nazione prende il codice dalla prima regione dell'elenco`() {
        val entries = countryEntries(listOf(part("Cina", "Nord", country = null), part("Cina", "Sud", country = "cn")))

        assertEquals(null, (entries.single() as RegionListEntry.Country).countryCode)
    }

    @Test
    fun `le righe visibili chiedono l'apertura della nazione per nome e segnano le regioni dentro di essa`() {
        val entries = countryEntries(listOf(part("Francia", "Bretagna", "fr"), part("Francia", "Corsica", "fr"), region("Andorra")))
        val asked = mutableListOf<String>()

        val rows = visibleRows(entries) { name -> asked += name; name == "Francia" }

        assertEquals(listOf("Francia"), asked)
        assertEquals(listOf("andorra", "country_Francia", "francia---bretagna", "francia---corsica"), rows.map { it.key })
        assertFalse((rows[0] as ListRow.Region).inCountry)
        assertTrue((rows[1] as ListRow.CountryHeader).expanded)
        assertTrue(rows.drop(2).all { (it as ListRow.Region).inCountry })
    }

    @Test
    fun `una nazione chiusa ha l'intestazione non espansa e nessuna regione`() {
        val entries = countryEntries(listOf(part("Francia", "Bretagna", "fr"), part("Francia", "Corsica", "fr")))

        val rows = visibleRows(entries) { false }

        assertEquals(1, rows.size)
        assertFalse((rows.single() as ListRow.CountryHeader).expanded)
    }

    @Test
    fun `la riga di una regione sostituita ha una chiave propria`() {
        val row = ListRow.Replaced(ReplacedRegionItem("stati-uniti", "Stati Uniti", "us", "Stati Uniti d'America", 10))

        assertEquals("replaced_stati-uniti", row.key)
        assertNotEquals(ListRow.Region(region("Stati Uniti", id = "stati-uniti"), inCountry = false).key, row.key)
    }
}
