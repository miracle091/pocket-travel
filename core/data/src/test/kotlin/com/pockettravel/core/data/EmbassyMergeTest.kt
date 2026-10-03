package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbassyMergeTest {

    private fun poi(name: String, phone: String? = null, nameEn: String? = null, nameIt: String? = null, website: String? = null) = Poi(
        id = 1, regionId = "spagna", name = name, category = "embassy", latitude = 40.0, longitude = -3.0,
        osmTag = "amenity=embassy", phone = phone, website = website, nameEn = nameEn, nameIt = nameIt,
    )

    private fun mission(
        id: String,
        name: String,
        kind: MissionKind = MissionKind.CONSULATE,
        nameEn: String? = null,
        city: String? = null,
        phone: String? = null,
        website: String? = null,
    ) = DiplomaticMission(id, kind, name, nameEn, city, null, phone, website, null)

    @Test
    fun `senza rappresentanze Wikidata restano i POI OSM`() {
        val result = mergeEmbassies(listOf(poi("Embajada de Italia", "+34 91 123 45 67")), emptyList(), emptyList(), "it")
        assertEquals(listOf("Embajada de Italia"), result.map { it.name })
        // Il tipo si ricava dal nome del POI.
        assertEquals(MissionKind.EMBASSY, result.single().kind)
    }

    @Test
    fun `un POI senza tipo nel nome resta di tipo ignoto`() {
        assertNull(mergeEmbassies(listOf(poi("Italia")), emptyList(), emptyList(), "it").single().kind)
    }

    @Test
    fun `i consolati d'Italia non si fondono con l'ambasciata d'Italia`() {
        val osm = poi("Ambasciata d'Italia", nameIt = "Ambasciata d'Italia")
        val missions = listOf(
            mission("Q2", "Consolato generale d'Italia a Barcellona", MissionKind.CONSULATE_GENERAL, city = "Barcellona", phone = "+34 93 000 00 01"),
            mission("Q3", "Consolato d'Italia a Siviglia", MissionKind.CONSULATE, phone = "+34 95 000 00 02"),
            mission("Q1", "Ambasciata d'Italia a Madrid", MissionKind.EMBASSY, phone = "+34 91 000 00 03"),
        )
        val result = mergeEmbassies(listOf(osm), missions, emptyList(), "it")
        // L'ambasciata OSM prende il telefono dell'ambasciata Wikidata, i due consolati restano a parte.
        assertEquals(3, result.size)
        assertEquals("+34 91 000 00 03", result.first { it.name == "Ambasciata d'Italia" }.phone)
        assertEquals(listOf(MissionKind.EMBASSY, MissionKind.CONSULATE_GENERAL, MissionKind.CONSULATE), result.map { it.kind })
    }

    @Test
    fun `una rappresentanza Wikidata completa al massimo un POI`() {
        val osm = listOf(poi("Embassy of Italy", phone = "+34 91 123 45 67"), poi("Embajada de Italia"))
        val wikidata = mission("Q1", "Embassy of Italy", MissionKind.EMBASSY, phone = "+34 91 123 45 67", website = "https://amb.esteri.it")
        val result = mergeEmbassies(osm, listOf(wikidata), emptyList(), "it")
        assertEquals(1, result.count { it.website == "https://amb.esteri.it" })
    }

    @Test
    fun `la stessa rappresentanza con telefono scritto diversamente non si ripete e prende il tipo`() {
        val osm = poi("Embajada de Italia", "+34 91 123 45 67")
        val wikidata = mission("Q1", "Italian embassy in Spain", MissionKind.EMBASSY, phone = "0034911234567", website = "https://amb.esteri.it")
        val result = mergeEmbassies(listOf(osm), listOf(wikidata), emptyList(), "it")
        assertEquals(1, result.size)
        assertEquals(MissionKind.EMBASSY, result.single().kind)
        assertEquals("https://amb.esteri.it", result.single().website)
        assertEquals("Embajada de Italia", result.single().name)
    }

    @Test
    fun `lo stesso nome senza parole generiche e maiuscole o accenti conta come duplicato`() {
        val osm = poi("Consulado General de Italia en Bárcelona")
        val wikidata = mission("Q2", "Consulate General of Italy, Barcelona", MissionKind.CONSULATE_GENERAL, nameEn = "Consulado General de Italia en Barcelona")
        assertEquals(1, mergeEmbassies(listOf(osm), listOf(wikidata), emptyList(), "it").size)
    }

    @Test
    fun `un solo termine in comune non basta, e una parola generica non e un nome`() {
        val osm = poi("Consolato onorario di Italia a Malaga")
        val other = mission("Q3", "Consulate of Italy, Seville", nameEn = "Consulate of Italy, Seville")
        val onlyGeneric = mission("Q4", "Embassy")
        assertEquals(3, mergeEmbassies(listOf(osm), listOf(other, onlyGeneric), emptyList(), "en").size)
    }

    @Test
    fun `telefoni troppo corti non si confrontano`() {
        val result = mergeEmbassies(listOf(poi("A", "112")), listOf(mission("Q5", "B", phone = "112")), emptyList(), "it")
        assertEquals(2, result.size)
    }

    @Test
    fun `ordine per tipo, poi citta della regione, poi nome`() {
        val missions = listOf(
            mission("Q1", "Zeta", MissionKind.CONSULATE, city = "Girona"),
            mission("Q2", "Beta", MissionKind.CONSULATE, city = "Barcelona"),
            mission("Q3", "Alfa", MissionKind.CONSULATE, city = "Valencia"),
            mission("Q4", "Gamma", MissionKind.CONSULATE_GENERAL, city = "Madrid"),
            mission("Q5", "Omega", MissionKind.EMBASSY, city = "Madrid"),
        )
        val result = mergeEmbassies(emptyList(), missions, listOf("Barcelona", "Girona"), "it")
        // Ambasciata, poi consolato generale, poi i consolati: quelli della regione (nome) prima di Valencia.
        assertEquals(listOf("Omega", "Gamma", "Beta", "Zeta", "Alfa"), result.map { it.name })
    }

    @Test
    fun `i POI OSM di tipo ignoto vengono per primi`() {
        val result = mergeEmbassies(
            listOf(poi("Rappresentanza locale")),
            listOf(mission("Q1", "Ambasciata", MissionKind.EMBASSY, city = "Madrid")),
            emptyList(),
            "it",
        )
        assertEquals(listOf("Rappresentanza locale", "Ambasciata"), result.map { it.name })
    }

    @Test
    fun `il nome segue la lingua con ripiego sull'altro`() {
        val both = mission("Q1", "Ambasciata d'Italia", nameEn = "Embassy of Italy")
        val onlyLocal = mission("Q2", "Consolato di Barcellona")
        assertEquals(setOf("Ambasciata d'Italia", "Consolato di Barcellona"), mergeEmbassies(emptyList(), listOf(both, onlyLocal), emptyList(), "it").map { it.name }.toSet())
        assertEquals(setOf("Embassy of Italy", "Consolato di Barcellona"), mergeEmbassies(emptyList(), listOf(both, onlyLocal), emptyList(), "en").map { it.name }.toSet())
    }

    @Test
    fun `vicino a te entro 100 km, dalla piu' vicina, al massimo tre`() {
        fun entry(name: String, lat: Double?, lon: Double?) = EmbassyEntry(name, MissionKind.CONSULATE, null, null, null, null, lat, lon)
        val entries = listOf(
            entry("Lontana", 41.39, 2.17), // Barcellona, ~500 km da Madrid
            entry("Senza posizione", null, null),
            entry("Vicina", 40.42, -3.70),
            entry("Toledo", 39.86, -4.02), // ~70 km
        )
        val nearby = nearbyEmbassies(entries, 40.4168, -3.7038)
        assertEquals(listOf("Vicina", "Toledo"), nearby.map { it.first.name })
        assertEquals(0.0, nearby.first().second, 1.0)
    }
}
