package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.TransitFeedInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory

class TransitManifestTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sha = "a".repeat(64)

    // transit.json come lo pubblica la pipeline, con campi che l'app non legge (bbox, stops, ...).
    private val sampleIndex = """
        {
          "version": "2026.09.29.1",
          "feeds": [
            {
              "id": "mdb-3502", "name": "Riga", "regions": ["lettonia"], "license": "CC0-1.0",
              "attribution": "Rigas satiksme (data.gov.lv, CC0 1.0)", "licenseUrl": "https://creativecommons.org/publicdomain/zero/1.0/",
              "version": "2026.09.29.34.1", "sourceSha256": "$sha", "validUntil": "2026-12-27",
              "bbox": [24.0, 56.8, 24.3, 57.1], "stops": 1652, "routes": 87,
              "file": { "name": "transit.db", "url": "https://github.com/o/r/releases/download/transit-feeds/mdb-3502--2026.09.29.34.1--transit.db", "sizeBytes": 4014080, "sha256": "$sha" },
              "fileXz": { "name": "transit.db.xz", "url": "https://github.com/o/r/releases/download/transit-feeds/mdb-3502--2026.09.29.34.1--transit.db.xz", "sizeBytes": 781484, "sha256": "${"b".repeat(64)}" }
            },
            {
              "id": "mdb-3503", "name": "Jurmala", "regions": ["lettonia"], "license": "CC0-1.0", "attribution": "Jurmala",
              "version": "2026.09.29.10.1",
              "file": { "name": "transit.db", "url": "https://github.com/o/r/releases/download/transit-feeds/mdb-3503--v--transit.db", "sizeBytes": 1000000, "sha256": "$sha" }
            },
            {
              "id": "mdb-100", "name": "Milano", "regions": ["italia"], "license": "CC-BY-4.0", "attribution": "ATM",
              "version": "2026.09.29.5.1",
              "file": { "name": "transit.db", "url": "https://github.com/o/r/releases/download/transit-feeds/mdb-100--v--transit.db", "sizeBytes": 9000000, "sha256": "$sha" },
              "fileXz": { "name": "transit.db.xz", "url": "https://github.com/o/r/releases/download/transit-feeds/mdb-100--v--transit.db.xz", "sizeBytes": 2000000, "sha256": "$sha" }
            }
          ]
        }
    """.trimIndent()

    private fun index(text: String = sampleIndex) = json.decodeFromString(TransitIndex.serializer(), text)

    private fun region(regionId: String) = RegionManifestEntry(
        regionId = regionId, displayName = regionId, updatedAt = "2026-09-29T00:00:00Z",
        map = MapPackageEntry("v1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", 20.0, 55.0, 29.0, 58.0, 0, 14)),
        routing = RoutingPackageEntry("v1", listOf(RegionManifestFile("E5_N45.rd5", "https://github.com/o/r/releases/download/region-data/E5_N45.rd5", 1000, sha))),
        poi = PoiPackageEntry("v1", RegionManifestFile("poi.db", "https://github.com/o/r/releases/download/region-data/poi.db", 1000, sha)),
    )

    @Test
    fun `transit json si legge, ignora i campi in piu' e supera la convalida`() {
        val index = index()
        index.validate()
        assertEquals(listOf("mdb-3502", "mdb-3503", "mdb-100"), index.feeds.map { it.id })
        assertEquals("https://creativecommons.org/publicdomain/zero/1.0/", index.feeds[0].licenseUrl)
        assertNull(index.feeds[1].licenseUrl)
        assertEquals(781_484L, index.feeds[0].downloadFile.sizeBytes)
        // Senza fileXz si scarica direttamente file.
        assertEquals(1_000_000L, index.feeds[1].downloadFile.sizeBytes)
    }

    @Test
    fun `la voce transit del catalogo e' facoltativa e convalidata come addressGrid`() {
        val manifest = """{"manifestVersion":2,"guides":{"version":"1","file":{"name":"guides.db","url":"https://github.com/o/r/g.db","sizeBytes":1,"sha256":"$sha"}},
            "transit":{"version":"2026.09.29.1","url":"https://github.com/o/r/releases/download/transit-feeds/transit.json","sizeBytes":3000,"sha256":"$sha"},"regions":[]}"""
        val parsed = json.decodeFromString(RegionManifest.serializer(), manifest)
        parsed.transit!!.validate()
        assertNull(json.decodeFromString(RegionManifest.serializer(), manifest.replace(Regex("\"transit\":\\{.*?\\},"), "")).transit)
        assertThrows(IllegalArgumentException::class.java) { parsed.transit.copy(url = "https://evil.example.org/transit.json").validate() }
        assertThrows(IllegalArgumentException::class.java) { parsed.transit.copy(sha256 = "xyz").validate() }
    }

    @Test
    fun `la convalida rifiuta id doppi o non sicuri, host non ammessi e link di licenza non https`() {
        val feed = index().feeds[0]
        fun invalid(bad: TransitIndex) = assertThrows(IllegalArgumentException::class.java) { bad.validate() }
        invalid(TransitIndex("v1", listOf(feed, feed)))
        invalid(TransitIndex("v1", listOf(feed.copy(id = "../x"))))
        invalid(TransitIndex("v1", listOf(feed.copy(version = "a b"))))
        invalid(TransitIndex("v1", listOf(feed.copy(regions = listOf("a/b")))))
        invalid(TransitIndex("v1", listOf(feed.copy(licenseUrl = "javascript:alert(1)"))))
        invalid(TransitIndex("v1", listOf(feed.copy(licenseUrl = "http://example.org/l"))))
        invalid(TransitIndex("v1", listOf(feed.copy(file = feed.file.copy(url = "https://evil.example.org/t.db")))))
        invalid(TransitIndex("v1", listOf(feed.copy(fileXz = feed.fileXz!!.copy(sha256 = "1234")))))
        invalid(TransitIndex("v1", listOf(feed.copy(name = " "))))
    }

    @Test
    fun `le reti di una regione sono quelle che la elencano`() {
        val index = index()
        assertEquals(listOf("mdb-3502", "mdb-3503"), regionTransitFeeds(index, "lettonia").map { it.id })
        assertEquals(listOf("mdb-100"), regionTransitFeeds(index, "italia").map { it.id })
        assertTrue(regionTransitFeeds(index, "francia").isEmpty())

        val attached = attachTransitFeeds(listOf(region("lettonia"), region("francia")), index)
        assertEquals(2, attached[0].transit!!.feeds.size)
        assertNull("nessuna rete per la Francia", attached[1].transit)
        // Senza indice (catalogo senza transit o non scaricato) le regioni restano com'erano.
        assertEquals(listOf(region("lettonia")), attachTransitFeeds(listOf(region("lettonia")), null))
    }

    @Test
    fun `le reti non incluse arrivano alle regioni che elencano, senza cambiare la versione`() {
        val missing = """"missing": [
            {"id": "marche", "name": "Marche, autobus e treni regionali", "regions": ["italia"], "reason": "licenza"},
            {"id": "lig-atc", "name": "La Spezia, autobus (ATC)", "regions": ["italia", "lettonia"], "reason": "orari"}
          ],"""
        val index = index(sampleIndex.replaceFirst("\"feeds\":", "$missing \"feeds\":"))
        index.validate()
        val (lettonia, italia) = attachTransitFeeds(listOf(region("lettonia"), region("italia")), index)
        assertEquals(listOf("La Spezia, autobus (ATC)"), lettonia.transit!!.missing.map { it.name })
        assertEquals(listOf("licenza", "orari"), italia.transit!!.missing.map { it.reason })
        assertEquals(regionTransitVersion(regionTransitFeeds(index, "italia")), italia.versionOf(PackageKind.TRANSIT))
        // L'indice di prima, senza "missing", resta leggibile.
        assertTrue(index().missing.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            index.copy(missing = listOf(index.missing[0].copy(regions = listOf("../x")))).validate()
        }
        assertThrows(IllegalArgumentException::class.java) { index.copy(missing = listOf(index.missing[0].copy(name = ""))).validate() }
    }

    @Test
    fun `una rete installata passata a missing e' non piu' aggiornata, una tornata in feeds no`() {
        val installed = listOf(TransitFeedInfo("mdb-100", "Milano", "ATM"), TransitFeedInfo("mdb-ex", "Torino", "GTT"))
        val withMissing = index().let { it.copy(missing = listOf(TransitMissing("Torino", listOf("italia"), "orari", id = "mdb-ex"))) }

        assertEquals(listOf("mdb-ex"), staleTransitFeeds(withMissing, "italia", installed).map { it.id })
        // Torna valida: e' di nuovo tra i feeds, non e' piu' non aggiornata.
        val back = withMissing.copy(feeds = withMissing.feeds + index().feeds[2].copy(id = "mdb-ex", name = "Torino", regions = listOf("italia")))
        assertTrue(staleTransitFeeds(back, "italia", installed).isEmpty())
        // Il confronto e' per id: un nome uguale con un altro id non basta.
        assertEquals(listOf("mdb-ex"), staleTransitFeeds(withMissing, "italia", listOf(TransitFeedInfo("mdb-ex", "Milano", "GTT"))).map { it.id })
        // Tolta del tutto dall'indice (sostituita da un'altra rete): non e' "non piu' aggiornata", l'aggiornamento la toglie.
        assertTrue(staleTransitFeeds(index(), "italia", listOf(TransitFeedInfo("mdb-sostituita", "Torino", "GTT"))).isEmpty())
    }

    @Test
    fun `una regione senza reti pubblicate riceve comunque le mancanti e quelle non piu' aggiornate, senza offrire il pacchetto`() {
        val missing = TransitMissing("Torino", listOf("francia"), "orari", id = "mdb-ex")
        val index = index().copy(missing = listOf(missing))
        val installed = mapOf("francia" to listOf(TransitFeedInfo("mdb-ex", "Torino", "GTT")))
        val (francia, spagna) = attachTransitFeeds(listOf(region("francia"), region("spagna")), index, installed = installed)

        assertEquals(listOf(missing), francia.transit!!.missing)
        assertEquals(listOf("Torino"), francia.transit.stale)
        assertTrue(francia.transit.feeds.isEmpty())
        assertNull("senza reti pubblicate il pacchetto non e' offerto", francia.versionOf(PackageKind.TRANSIT))
        assertFalse(PackageKind.TRANSIT in francia.availableKinds)
        assertEquals(0L, francia.downloadBytes(setOf(PackageKind.TRANSIT)))
        francia.validate()
        assertNull("nessuna rete ne' mancante ne' installata", spagna.transit)
    }

    @Test
    fun `un indice senza id nelle reti mancanti si legge ancora`() {
        val old = index(sampleIndex.replaceFirst("\"feeds\":", """"missing": [{"name": "Marche", "regions": ["italia"], "reason": "licenza"}], "feeds":"""))
        assertEquals("", old.missing.single().id)
        val withId = index(sampleIndex.replaceFirst("\"feeds\":", """"missing": [{"id": "marche", "name": "Marche", "regions": ["italia"], "reason": "licenza"}], "feeds":"""))
        assertEquals("marche", withId.missing.single().id)
    }

    @Test
    fun `la versione del pacchetto deriva dalle reti, non dall'ordine`() {
        val feeds = regionTransitFeeds(index(), "lettonia")
        val version = regionTransitVersion(feeds)
        assertTrue(version.startsWith("transit-"))
        assertEquals(version, regionTransitVersion(feeds.reversed()))
        assertNotEquals("una rete cambia versione", version, regionTransitVersion(listOf(feeds[0].copy(version = "2026.10.01.1"), feeds[1])))
        assertNotEquals("una rete sparisce", version, regionTransitVersion(listOf(feeds[0])))
        // Altri campi (file, nome) non cambiano la versione.
        assertEquals(version, regionTransitVersion(listOf(feeds[0].copy(name = "Altro"), feeds[1])))
    }

    @Test
    fun `il pacchetto TRANSIT e' offerto solo con le reti, fuori dal download completo e conta i fileXz`() {
        val plain = region("lettonia")
        assertNull(plain.versionOf(PackageKind.TRANSIT))
        assertFalse(PackageKind.TRANSIT in plain.availableKinds)

        val withTransit = attachTransitFeeds(listOf(plain), index()).single()
        withTransit.validate()
        assertTrue(PackageKind.TRANSIT in withTransit.availableKinds)
        assertFalse(PackageKind.TRANSIT in withTransit.defaultKinds)
        assertFalse(PackageKind.TRANSIT in withTransit.downloadKinds(withRouting = true))
        assertEquals(781_484L + 1_000_000L, withTransit.downloadBytes(setOf(PackageKind.TRANSIT)))
        assertEquals(0L, withTransit.downloadBytes(setOf(PackageKind.MAP)))
    }

    @Test
    fun `RegionManifestEntry con transit sopravvive a un giro di json (RegionPackageDownloadWorker)`() {
        val entry = attachTransitFeeds(listOf(region("lettonia")), index()).single()
        val roundTripped = json.decodeFromString(RegionManifestEntry.serializer(), json.encodeToString(RegionManifestEntry.serializer(), entry))
        assertEquals(entry, roundTripped)
        roundTripped.validate()
    }

    @Test
    fun `in staging ogni rete prende il nome dal suo id, cosi' non si scavalcano`() {
        val (riga, jurmala) = regionTransitFeeds(index(), "lettonia")
        assertEquals("mdb-3502.db", riga.stagedFile.name)
        assertEquals("mdb-3502.db.xz", riga.stagedDownloadFile.name)
        assertEquals("dimensione e sha256 restano quelli del catalogo", riga.file.sha256, riga.stagedFile.sha256)
        assertEquals("mdb-3503.db", jurmala.stagedDownloadFile.name)
    }

    @Test
    fun `assembleTransitDir mette le reti in transit e scrive le attribuzioni`() {
        val staging = createTempDirectory("pocket-travel-transit-test").toFile()
        val feeds = regionTransitFeeds(index(), "lettonia")
        feeds.forEach { File(staging, it.stagedFile.name).writeText("db-${it.id}") }

        assembleTransitDir(staging, feeds)

        val dir = File(staging, RegionStorage.TRANSIT_DIR)
        assertEquals("db-mdb-3502", File(dir, "mdb-3502.db").readText())
        assertEquals("db-mdb-3503", File(dir, "mdb-3503.db").readText())
        assertFalse("spostate, non copiate", File(staging, "mdb-3502.db").exists())
        val infos = TransitFeedInfo.decode(File(dir, RegionStorage.TRANSIT_FEEDS_FILE).readText())
        assertEquals(listOf("mdb-3502", "mdb-3503"), infos.map { it.id })
        assertEquals("Rigas satiksme (data.gov.lv, CC0 1.0)", infos[0].attribution)
        assertEquals("https://creativecommons.org/publicdomain/zero/1.0/", infos[0].licenseUrl)
        staging.deleteRecursively()
    }

    private val today = LocalDate.of(2026, 10, 16)

    private fun carried(published: Set<String>, validity: Map<String, LocalDate?>, missing: Set<String> = validity.keys) =
        carriedOverTransitFeeds(validity.keys.map { TransitFeedInfo(it, it, "x") }, published, missing, today) { validity[it.id] }.map { it.id }

    @Test
    fun `una rete passata tra le non incluse resta finche' gli orari sono validi, anche l'ultimo giorno`() {
        val validity = mapOf("valida" to today.plusDays(3), "oggi" to today, "scaduta" to today.minusDays(1))
        assertEquals(listOf("valida", "oggi"), carried(emptySet(), validity))
    }

    @Test
    fun `le reti ancora pubblicate, anche quelle tolte dall'utente, non si portano avanti`() {
        val validity = mapOf("scaricata" to today.plusDays(9), "tolta" to today.plusDays(9), "sparita" to today.plusDays(9))
        assertEquals(listOf("sparita"), carried(setOf("scaricata", "tolta"), validity))
    }

    @Test
    fun `una rete tolta del tutto dall'indice, sostituita da un'altra, non si porta avanti`() {
        val validity = mapOf("non-inclusa" to today.plusDays(9), "sostituita" to today.plusDays(9))
        assertEquals(listOf("non-inclusa"), carried(emptySet(), validity, missing = setOf("non-inclusa")))
    }

    @Test
    fun `una rete con orari illeggibili si scarta`() {
        assertEquals(emptyList<String>(), carried(emptySet(), mapOf("rotta" to null)))
    }

    @Test
    fun `assembleTransitDir copia le reti portate avanti accanto a quelle nuove`() {
        val staging = createTempDirectory("pocket-travel-transit-test").toFile()
        val installed = createTempDirectory("pocket-travel-transit-installed").toFile()
        File(installed, "vecchia.db").writeText("db-vecchia")
        val feeds = regionTransitFeeds(index(), "lettonia")
        feeds.forEach { File(staging, it.stagedFile.name).writeText("db-${it.id}") }
        val old = TransitFeedInfo("vecchia", "Vecchia", "Fonte")

        assembleTransitDir(staging, feeds, listOf(old), installed)

        val dir = File(staging, RegionStorage.TRANSIT_DIR)
        assertEquals("db-vecchia", File(dir, "vecchia.db").readText())
        assertEquals("db-vecchia", File(installed, "vecchia.db").readText())
        assertEquals(listOf("mdb-3502", "mdb-3503", "vecchia"), TransitFeedInfo.decode(File(dir, RegionStorage.TRANSIT_FEEDS_FILE).readText()).map { it.id })
        staging.deleteRecursively()
        installed.deleteRecursively()
    }
}
