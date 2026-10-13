package com.pockettravel.core.sync

import com.pockettravel.core.data.TransitFeedInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scelta delle reti dei mezzi pubblici (distanze, esclusioni, versione) e convalida di transit.json oltre TransitManifestTest. */
class TransitSelectionTest {

    private val sha = "a".repeat(64)

    private fun RegionManifestEntry.transitEntry(): RegionTransitEntry = checkNotNull(transit)

    private fun feed(
        id: String,
        bbox: List<Double>? = null,
        regions: List<String> = listOf("r"),
        version: String = "v1",
    ) = TransitFeed(
        id, "Rete $id", regions, "CC0-1.0", "Attribuzione $id", null, version,
        RegionManifestFile("transit.db", "https://github.com/o/r/releases/download/t/$id.db", 100, sha), bbox = bbox,
    )

    private fun region(regionId: String) = RegionManifestEntry(
        regionId = regionId, displayName = regionId, updatedAt = "2026-10-01T00:00:00Z",
        map = MapPackageEntry("v1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", 0.0, 0.0, 1.0, 1.0, 0, 14)),
        routing = RoutingPackageEntry("v1", listOf(RegionManifestFile("E0_N0.rd5", "https://github.com/o/r/E0_N0.rd5", 1, sha))),
        poi = PoiPackageEntry("v1", RegionManifestFile("poi.db", "https://github.com/o/r/poi.db", 1, sha)),
    )

    // Quattro reti lontane tra loro; la prima occupa il riquadro da (0, 0) a (1, 1) gradi.
    private val four = listOf(
        feed("a", listOf(0.0, 0.0, 1.0, 1.0)),
        feed("b", listOf(50.0, 50.0, 51.0, 51.0)),
        feed("c", listOf(100.0, 20.0, 101.0, 21.0)),
        feed("d", listOf(-100.0, -20.0, -99.0, -19.0)),
    )

    @Test
    fun `con tre reti o meno non si esclude nulla, nemmeno con la posizione`() {
        assertEquals(TransitDefault(emptySet(), null), defaultTransitChoice(four.take(3), 0.5, 0.5))
        assertEquals(TransitDefault(emptySet(), null), defaultTransitChoice(emptyList(), 0.5, 0.5))
    }

    @Test
    fun `con una sola coordinata nota si scaricano tutte`() {
        assertEquals(TransitDefault(emptySet(), TransitDefaultReason.ALL), defaultTransitChoice(four, 0.5, null))
        assertEquals(TransitDefault(emptySet(), TransitDefaultReason.ALL), defaultTransitChoice(four, null, 0.5))
    }

    @Test
    fun `se nessuna rete ha il riquadro si scaricano tutte`() {
        val noBox = four.map { it.copy(bbox = null) }
        assertEquals(TransitDefault(emptySet(), TransitDefaultReason.ALL), defaultTransitChoice(noBox, 0.5, 0.5))
    }

    @Test
    fun `dentro il riquadro la distanza e' zero e resta quella rete`() {
        val choice = defaultTransitChoice(four, 0.5, 0.5)
        assertEquals(setOf("b", "c", "d"), choice.excluded)
        assertEquals(TransitDefaultReason.NEAR, choice.reason)
    }

    @Test
    fun `la soglia dei 50 km passa fra 44 e 56 km dal riquadro`() {
        // 0,4 gradi di latitudine = circa 44,5 km a nord del riquadro: vicina.
        assertEquals(TransitDefaultReason.NEAR, defaultTransitChoice(four, 1.4, 0.5).reason)
        // 0,5 gradi = circa 55,7 km: nessuna vicina, resta la piu' vicina.
        val far = defaultTransitChoice(four, 1.5, 0.5)
        assertEquals(TransitDefaultReason.NEAREST, far.reason)
        assertEquals(setOf("b", "c", "d"), far.excluded)
    }

    @Test
    fun `la distanza in longitudine si accorcia con la latitudine`() {
        // A 60 gradi nord un grado di longitudine vale circa 56 km: 0,8 gradi dal riquadro sono 44 km e contano come vicini.
        val north = listOf(feed("n", listOf(10.0, 60.0, 11.0, 61.0))) + four.drop(1)
        assertEquals(TransitDefaultReason.NEAR, defaultTransitChoice(north, 60.5, 11.8).reason)
        // Alla stessa differenza di longitudine all'equatore sarebbero 89 km.
        val equator = listOf(feed("e", listOf(10.0, 0.0, 11.0, 1.0))) + four.drop(1)
        assertEquals(TransitDefaultReason.NEAREST, defaultTransitChoice(equator, 0.5, 11.8).reason)
    }

    @Test
    fun `il riquadro con longitudini negative si legge allo stesso modo`() {
        val west = listOf(feed("w", listOf(-3.0, 51.0, -2.0, 52.0))) + four.drop(1)
        val choice = defaultTransitChoice(west, 51.5, -2.5)
        assertEquals(TransitDefaultReason.NEAR, choice.reason)
        assertEquals(setOf("b", "c", "d"), choice.excluded)
    }

    @Test
    fun `due reti vicine restano entrambe`() {
        val overlapping = listOf(feed("a", listOf(0.0, 0.0, 1.0, 1.0)), feed("a2", listOf(0.9, 0.9, 2.0, 2.0))) + four.drop(1)
        assertEquals(setOf("b", "c", "d"), defaultTransitChoice(overlapping, 0.95, 0.95).excluded)
    }

    @Test
    fun `le reti escluse dall'utente restano disponibili ma non da scaricare`() {
        val index = TransitIndex("v1", listOf(feed("a"), feed("b"), feed("c")))
        val (entry) = attachTransitFeeds(listOf(region("r")), index, excluded = mapOf("r" to setOf("b")))
        assertEquals(listOf("a", "c"), entry.transitEntry().feeds.map { it.id })
        assertEquals(listOf("a", "b", "c"), entry.transitEntry().available.map { it.id })
    }

    @Test
    fun `se l'utente le toglie tutte restano tutte`() {
        val index = TransitIndex("v1", listOf(feed("a"), feed("b")))
        val (entry) = attachTransitFeeds(listOf(region("r")), index, excluded = mapOf("r" to setOf("a", "b")))
        assertEquals(listOf("a", "b"), entry.transitEntry().feeds.map { it.id })
    }

    @Test
    fun `le esclusioni e i motivi valgono solo per la loro regione`() {
        val index = TransitIndex("v1", listOf(feed("a", regions = listOf("r", "s")), feed("b", regions = listOf("r", "s"))))
        val (r, s) = attachTransitFeeds(
            listOf(region("r"), region("s")), index,
            excluded = mapOf("r" to setOf("a")), defaultReasons = mapOf("r" to TransitDefaultReason.NEAR),
        )
        assertEquals(listOf("b"), r.transitEntry().feeds.map { it.id })
        assertEquals(TransitDefaultReason.NEAR, r.transitEntry().defaultReason)
        assertEquals(listOf("a", "b"), s.transitEntry().feeds.map { it.id })
        assertNull(s.transitEntry().defaultReason)
    }

    @Test
    fun `una regione senza reti nell'indice resta com'era`() {
        val index = TransitIndex("v1", listOf(feed("a", regions = listOf("altra"))))
        val plain = region("r")
        assertEquals(listOf(plain), attachTransitFeeds(listOf(plain), index))
    }

    @Test
    fun `la versione senza reti e' lo sha256 della stringa vuota, quella con reti dipende da id e version`() {
        assertEquals("transit-e3b0c44298fc1c14", regionTransitVersion(emptyList()))
        val one = regionTransitVersion(listOf(feed("a", version = "1")))
        assertEquals(24, one.length)
        assertTrue(one != regionTransitVersion(listOf(feed("a", version = "2"))))
        assertTrue(one != regionTransitVersion(listOf(feed("b", version = "1"))))
    }

    @Test
    fun `in staging solo le reti compresse prendono il nome xz`() {
        assertNull(feed("a").stagedFileXz)
        val withXz = feed("a").copy(fileXz = RegionManifestFile("transit.db.xz", "https://github.com/o/r/a.db.xz", 10, sha))
        assertEquals("a.db.xz", withXz.stagedDownloadFile.name)
        assertEquals("a.db", feed("a").stagedDownloadFile.name)
    }

    @Test
    fun `info porta nome, attribuzione e link di licenza`() {
        val withLicense = feed("a").copy(licenseUrl = "https://creativecommons.org/publicdomain/zero/1.0/")
        assertEquals(
            TransitFeedInfo("a", "Rete a", "Attribuzione a", "https://creativecommons.org/publicdomain/zero/1.0/"),
            withLicense.info(),
        )
    }

    @Test
    fun `la convalida rifiuta un riquadro che non ha quattro valori e accetta l'assenza`() {
        TransitIndex("v1", listOf(feed("a", bbox = null))).validate()
        TransitIndex("v1", listOf(feed("a", bbox = listOf(0.0, 0.0, 1.0, 1.0)))).validate()
        listOf(emptyList(), listOf(0.0, 0.0, 1.0), listOf(0.0, 0.0, 1.0, 1.0, 2.0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { TransitIndex("v1", listOf(feed("a", bbox = it))).validate() }
        }
    }

    @Test
    fun `la convalida rifiuta attribuzione vuota, versione dell'indice non sicura e dimensione negativa`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransitIndex("v1", listOf(feed("a").copy(attribution = ""))).validate()
        }
        assertThrows(IllegalArgumentException::class.java) { TransitIndex("../x", listOf(feed("a"))).validate() }
        assertThrows(IllegalArgumentException::class.java) {
            val bad = feed("a")
            TransitIndex("v1", listOf(bad.copy(file = bad.file.copy(sizeBytes = -1)))).validate()
        }
    }

    @Test
    fun `la voce transit del manifest rifiuta dimensione negativa e versione non sicura`() {
        val entry = TransitManifestEntry("1", "https://github.com/o/r/releases/download/t/transit.json", 10, sha)
        entry.validate()
        assertThrows(IllegalArgumentException::class.java) { entry.copy(sizeBytes = -1).validate() }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(version = "a b").validate() }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(url = "http://github.com/o/r/transit.json").validate() }
    }

    @Test
    fun `un link di licenza https senza host o con altro schema non passa`() {
        listOf("https://", "ftp://example.org/l", "file:///etc/passwd", "not a url").forEach {
            assertThrows(it, IllegalArgumentException::class.java) {
                TransitIndex("v1", listOf(feed("a").copy(licenseUrl = it))).validate()
            }
        }
        TransitIndex("v1", listOf(feed("a").copy(licenseUrl = "https://example.org/license"))).validate()
    }
}
