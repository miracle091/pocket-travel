package com.pockettravel.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Convalida delle voci del catalogo e degli indirizzi ammessi: il confine fra dati pubblicati e file scaricati. */
class RegionManifestValidationTest {

    private val sha = "a".repeat(64)
    private fun file(name: String, url: String = "https://github.com/o/r/releases/download/x/$name", size: Long = 10, hash: String = sha) =
        RegionManifestFile(name, url, size, hash)

    private val source = MapExtractionSource("https://build.protomaps.com/x.pmtiles", 10.0, 42.0, 12.0, 44.0, 0, 14)

    private val entry = RegionManifestEntry(
        regionId = "toscana",
        displayName = "Toscana",
        updatedAt = "2026-10-01T00:00:00Z",
        map = MapPackageEntry("m1", source),
        routing = RoutingPackageEntry("r1", listOf(file("E10_N40.rd5"))),
        poi = PoiPackageEntry("p1", file("poi.db")),
    )

    private fun invalid(bad: RegionManifestEntry) = assertThrows(IllegalArgumentException::class.java) { bad.validate() }

    private fun withSource(newSource: MapExtractionSource) = entry.copy(map = entry.map.copy(source = newSource))

    @Test
    fun `la voce di base passa`() {
        entry.validate()
    }

    @Test
    fun `un regionId non sicuro come nome di cartella non passa`() {
        listOf("", ".", "..", "../x", "a/b", "a\\b", "a b", "x\n").forEach { invalid(entry.copy(regionId = it)) }
        entry.copy(regionId = "stati-uniti.california_1").validate()
    }

    @Test
    fun `i segmenti della rete stradale non possono ripetersi`() {
        val dup = RoutingPackageEntry("r1", listOf(file("E10_N40.rd5"), file("E10_N40.rd5")))
        invalid(entry.copy(routing = dup))
    }

    @Test
    fun `routingCar e' convalidato come routing`() {
        entry.copy(routingCar = RoutingPackageEntry("r1", listOf(file("E10_N40.rd5")))).validate()
        invalid(entry.copy(routingCar = RoutingPackageEntry("r1", emptyList())))
        invalid(entry.copy(routingCar = RoutingPackageEntry("../x", listOf(file("E10_N40.rd5")))))
        invalid(entry.copy(routingCar = RoutingPackageEntry("r1", listOf(file("a.rd5"), file("a.rd5")))))
        invalid(entry.copy(routingCar = RoutingPackageEntry("r1", listOf(file("a.rd5", url = "https://evil.example.org/a.rd5")))))
    }

    @Test
    fun `i file del catalogo devono avere nome, dimensione, hash e host validi`() {
        invalid(entry.copy(poi = PoiPackageEntry("p1", file("a/b.db"))))
        invalid(entry.copy(poi = PoiPackageEntry("p1", file("poi.db", size = -1))))
        invalid(entry.copy(poi = PoiPackageEntry("p1", file("poi.db", hash = "abc"))))
        invalid(entry.copy(poi = PoiPackageEntry("p1", file("poi.db", hash = "g".repeat(64)))))
        invalid(entry.copy(poi = PoiPackageEntry("p1", file("poi.db", url = "https://evil.example.org/poi.db"))))
        invalid(entry.copy(poi = PoiPackageEntry("p1", file("poi.db"), fileXz = file("poi.db.xz", hash = "abc"))))
        entry.copy(poi = PoiPackageEntry("p1", file("poi.db", hash = "A".repeat(64)), fileXz = file("poi.db.xz", size = 0))).validate()
    }

    @Test
    fun `i pacchetti facoltativi sono convalidati se presenti`() {
        val extra = PoiPackageEntry("x1", file("poi-extra.db"))
        entry.copy(poiExtra = extra).validate()
        invalid(entry.copy(poiExtra = extra.copy(version = "a b")))
        invalid(entry.copy(poiExtra = extra.copy(file = file("poi-extra.db", url = "http://github.com/x"))))

        val cities = CitiesPackageEntry("c1", file("cities.db"))
        entry.copy(cities = cities, citiesEn = cities).validate()
        invalid(entry.copy(cities = cities.copy(version = "../x")))
        invalid(entry.copy(citiesEn = cities.copy(file = file("cities.db", hash = "1"))))
    }

    @Test
    fun `la mappa d'insieme ha zoom massimo fra 0 e 22 e file validi`() {
        val preview = PreviewPackageEntry("v1", 6, file("preview.pmtiles"))
        entry.copy(preview = preview).validate()
        entry.copy(preview = preview.copy(maxZoom = 0)).validate()
        entry.copy(preview = preview.copy(maxZoom = 22)).validate()
        invalid(entry.copy(preview = preview.copy(maxZoom = -1)))
        invalid(entry.copy(preview = preview.copy(maxZoom = 23)))
        invalid(entry.copy(preview = preview.copy(version = "a b")))
        invalid(entry.copy(preview = preview.copy(fileXz = file("preview.pmtiles.xz", size = -5))))
    }

    @Test
    fun `il rettangolo della mappa deve essere ordinato e dentro i limiti geografici`() {
        invalid(withSource(source.copy(minLon = 12.0, maxLon = 10.0)))
        invalid(withSource(source.copy(minLon = 10.0, maxLon = 10.0)))
        invalid(withSource(source.copy(minLat = 44.0, maxLat = 42.0)))
        invalid(withSource(source.copy(minLat = 42.0, maxLat = 42.0)))
        invalid(withSource(source.copy(minLon = -180.5)))
        invalid(withSource(source.copy(maxLon = 180.5)))
        invalid(withSource(source.copy(minLat = -90.5)))
        invalid(withSource(source.copy(maxLat = 90.5)))
        withSource(source.copy(minLon = -180.0, maxLon = 180.0, minLat = -90.0, maxLat = 90.0)).validate()
    }

    @Test
    fun `i livelli di zoom della mappa devono essere ordinati e al massimo 22`() {
        invalid(withSource(source.copy(minZoom = -1)))
        invalid(withSource(source.copy(maxZoom = 23)))
        invalid(withSource(source.copy(minZoom = 10, maxZoom = 9)))
        withSource(source.copy(minZoom = 5, maxZoom = 5)).validate()
        withSource(source.copy(minZoom = 0, maxZoom = 22)).validate()
    }

    @Test
    fun `la sorgente della mappa deve stare su un host ammesso`() {
        invalid(withSource(source.copy(sourceUrl = "https://example.org/x.pmtiles")))
        invalid(withSource(source.copy(sourceUrl = "http://build.protomaps.com/x.pmtiles")))
        invalid(withSource(source.copy(sourceUrl = "file:///sdcard/x.pmtiles")))
    }

    @Test
    fun `la mappa del mondo ha zoom, dimensione e indirizzo validi`() {
        val world = WorldMapEntry("1", 8, "https://github.com/o/r/world.pmtiles", 100)
        world.validate()
        listOf(
            world.copy(version = "a b"), world.copy(maxZoom = -1), world.copy(maxZoom = 23), world.copy(sizeBytes = -1),
            world.copy(url = "https://example.org/world.pmtiles"),
        ).forEach { assertThrows(IllegalArgumentException::class.java) { it.validate() } }
    }

    @Test
    fun `le guide hanno versione e file validi`() {
        val guides = GuidesManifestEntry("1", file("guides.db"))
        guides.validate()
        assertThrows(IllegalArgumentException::class.java) { guides.copy(version = "").validate() }
        assertThrows(IllegalArgumentException::class.java) { guides.copy(fileXz = file("guides.db.xz", hash = "xx")).validate() }
    }

    @Test
    fun `isAllowedManifestUrl ammette https sugli host fissi e rifiuta il resto`() {
        listOf(
            "https://github.com/o/r/releases/download/x/a.db", "https://brouter.de/brouter/segments4/E5_N45.rd5",
            "https://build.protomaps.com/x.pmtiles", "HTTPS://github.com/x",
            // La porta non conta: solo l'host.
            "https://github.com:8443/x",
        ).forEach { assertTrue(it, isAllowedManifestUrl(it)) }
        listOf(
            "http://github.com/x", "ftp://github.com/x", "file:///etc/passwd", "//github.com/x", "github.com/x", "",
            "not a url", "https://user@github.com/x", "https://user:pw@github.com/x", "https://github.com/x#frag",
            "https://github.com.evil.org/x", "https://evilgithub.com/x", "https://example.org/x",
            "https://[::1]/x", "https:///x",
        ).forEach { assertFalse(it, isAllowedManifestUrl(it)) }
    }

    @Test
    fun `isSafeSegment ammette solo nomi di file semplici`() {
        listOf("a", "E10_N45.rd5", "poi-extra.db", "mdb-3502", "A.b_c-1").forEach { assertTrue(it, isSafeSegment(it)) }
        listOf("", ".", "..", "a/b", "a\\b", "a b", "a:b", "caffè", "x\n", "a*", "../etc").forEach { assertFalse(it, isSafeSegment(it)) }
    }

    @Test
    fun `isSafeVersion non ammette separatori, spazi e caratteri speciali`() {
        listOf("1", "2026.09.20", "en-1.2", "v_1-b").forEach { assertTrue(it, isSafeVersion(it)) }
        listOf("", "a b", "../x", "a/b", "1;2", "x\n", "vè").forEach { assertFalse(it, isSafeVersion(it)) }
    }
}
