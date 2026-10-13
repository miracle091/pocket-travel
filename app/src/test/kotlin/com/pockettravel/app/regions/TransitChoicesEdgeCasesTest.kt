package com.pockettravel.app.regions

import com.pockettravel.core.sync.MapExtractionSource
import com.pockettravel.core.sync.MapPackageEntry
import com.pockettravel.core.sync.PoiPackageEntry
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionManifestFile
import com.pockettravel.core.sync.RoutingPackageEntry
import com.pockettravel.core.sync.TransitDefaultReason
import com.pockettravel.core.sync.TransitFeed
import com.pockettravel.core.sync.TransitIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TransitChoicesEdgeCasesTest {
    private fun feed(id: String, regions: List<String>, bbox: List<Double>? = null) = TransitFeed(
        id, id, regions, "OGL-UK-3.0", "DfT", null, "v1",
        RegionManifestFile("transit.db", "https://github.com/x/$id.db", 1, "0".repeat(64)), bbox = bbox,
    )

    private fun region(id: String, country: String?) = RegionManifestEntry(
        regionId = id,
        displayName = id,
        updatedAt = "2026-09-29T00:00:00Z",
        map = MapPackageEntry("m1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", -8.7, 49.8, 1.8, 60.9, 0, 14)),
        routing = RoutingPackageEntry("r1", emptyList()),
        poi = PoiPackageEntry("p1", RegionManifestFile("poi.db", "https://github.com/poi.db", 1, "a".repeat(64))),
        countryCode = country,
    )

    private val uk = region("regno-unito", "gb")
    private val manyFeeds = listOf(
        feed("london", listOf("regno-unito"), listOf(-0.51, 51.28, 0.33, 51.69)),
        feed("north-west", listOf("regno-unito"), listOf(-3.6, 52.9, -2.0, 55.2)),
        feed("scotland", listOf("regno-unito"), listOf(-7.6, 54.6, -0.7, 60.9)),
        feed("wales", listOf("regno-unito"), listOf(-5.4, 51.3, -2.6, 53.5)),
    )
    private val index = TransitIndex("v1", manyFeeds)
    private val london = DevicePlace(51.5074, -0.1278, "gb")

    @Test
    fun `senza indice resta la scelta salvata, senza motivi`() {
        val stored = mapOf("regno-unito" to setOf("london"), "altra" to emptySet<String>())

        val choices = effectiveTransitChoices(listOf(uk), null, stored, london)

        assertEquals(stored, choices.excluded)
        assertEquals(emptyMap<String, TransitDefaultReason>(), choices.reasons)
    }

    @Test
    fun `senza indice e senza scelte non c'e' nulla`() {
        val choices = effectiveTransitChoices(emptyList(), null, emptyMap(), null)

        assertEquals(emptyMap<String, Set<String>>(), choices.excluded)
        assertEquals(emptyMap<String, TransitDefaultReason>(), choices.reasons)
    }

    @Test
    fun `con l'indice solo le regioni dell'elenco hanno una scelta`() {
        val choices = effectiveTransitChoices(listOf(uk), index, mapOf("altra" to setOf("x")), london)

        assertEquals(setOf("regno-unito"), choices.excluded.keys)
    }

    @Test
    fun `senza posizione con molte reti restano tutte, con il motivo`() {
        val choices = effectiveTransitChoices(listOf(uk), index, emptyMap(), null)

        assertEquals(emptySet<String>(), choices.excluded["regno-unito"])
        assertEquals(TransitDefaultReason.ALL, choices.reasons["regno-unito"])
    }

    @Test
    fun `una posizione senza paese non conta come vicina`() {
        val choices = effectiveTransitChoices(listOf(uk), index, emptyMap(), DevicePlace(51.5074, -0.1278, null))

        assertEquals(emptySet<String>(), choices.excluded["regno-unito"])
        assertEquals(TransitDefaultReason.ALL, choices.reasons["regno-unito"])
    }

    @Test
    fun `una regione senza codice paese non usa la posizione`() {
        val choices = effectiveTransitChoices(listOf(region("regno-unito", null)), index, emptyMap(), london)

        assertEquals(TransitDefaultReason.ALL, choices.reasons["regno-unito"])
    }

    @Test
    fun `il confronto del paese ignora le maiuscole`() {
        val choices = effectiveTransitChoices(listOf(uk), index, emptyMap(), DevicePlace(51.5074, -0.1278, "GB"))

        assertEquals(TransitDefaultReason.NEAR, choices.reasons["regno-unito"])
        assertEquals(setOf("north-west", "scotland", "wales"), choices.excluded["regno-unito"])
    }

    @Test
    fun `lontano da ogni rete resta la piu' vicina`() {
        // Cornovaglia: nessuna rete entro 50 km, la piu' vicina e' il Galles.
        val choices = effectiveTransitChoices(listOf(uk), index, emptyMap(), DevicePlace(50.2, -5.0, "gb"))

        assertEquals(setOf("london", "north-west", "scotland"), choices.excluded["regno-unito"])
        assertEquals(TransitDefaultReason.NEAREST, choices.reasons["regno-unito"])
    }

    @Test
    fun `una scelta salvata vuota vince sul default e non ha un motivo`() {
        val choices = effectiveTransitChoices(listOf(uk), index, mapOf("regno-unito" to emptySet()), london)

        assertEquals(emptySet<String>(), choices.excluded["regno-unito"])
        assertNull(choices.reasons["regno-unito"])
    }

    @Test
    fun `con poche reti non c'e' nulla da escludere ne' da spiegare`() {
        val few = TransitIndex("v1", manyFeeds.take(3))

        val choices = effectiveTransitChoices(listOf(uk), few, emptyMap(), london)

        assertEquals(emptySet<String>(), choices.excluded["regno-unito"])
        assertFalse(choices.reasons.containsKey("regno-unito"))
    }

    @Test
    fun `una regione senza reti nell'indice ha una scelta vuota`() {
        val choices = effectiveTransitChoices(listOf(region("francia", "fr")), index, emptyMap(), DevicePlace(48.85, 2.35, "fr"))

        assertEquals(mapOf("francia" to emptySet<String>()), choices.excluded)
        assertEquals(emptyMap<String, TransitDefaultReason>(), choices.reasons)
    }

    @Test
    fun `le regioni si scelgono ognuna per conto proprio`() {
        val francia = region("francia", "fr")
        val feeds = manyFeeds + feed("parigi", listOf("francia"))

        val choices = effectiveTransitChoices(
            listOf(uk, francia), TransitIndex("v1", feeds), mapOf("francia" to setOf("parigi")), london,
        )

        assertEquals(setOf("north-west", "scotland", "wales"), choices.excluded["regno-unito"])
        assertEquals(setOf("parigi"), choices.excluded["francia"])
        assertEquals(setOf("regno-unito"), choices.reasons.keys)
    }

    @Test
    fun `una rete senza rettangolo non si puo' dire vicina`() {
        val noBox = TransitIndex("v1", manyFeeds.map { it.copy(bbox = null) })

        val choices = effectiveTransitChoices(listOf(uk), noBox, emptyMap(), london)

        assertEquals(emptySet<String>(), choices.excluded["regno-unito"])
        assertEquals(TransitDefaultReason.ALL, choices.reasons["regno-unito"])
    }
}
