package com.pockettravel.pipeline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class GenerateManifestTest {

    private val mapSource = MapSourceInput(
        sourceUrl = "https://build.protomaps.com/20260910.pmtiles",
        minLon = 10.0, minLat = 42.0, maxLon = 12.0, maxLat = 44.0,
        minZoom = 0, maxZoom = 14,
    )
    private val rd5 = ManifestFileEntry("E5_N45.rd5", "https://example.org/E5_N45.rd5", 79_980_459L, "a".repeat(64))

    @Test
    fun `il frammento di regione separa mappa, rete stradale e POI con le rispettive versioni`() {
        val packageDir = createTempDirectory("pocket-travel-test-manifest").toFile()
        try {
            val poiDb = File(packageDir, "poi.db").apply { writeText("contenuto di test") }

            val json = buildRegionFragmentJson(
                regionId = "test-region",
                displayName = "Regione di Test",
                version = "2026.09.10",
                updatedAt = "2026-09-10T00:00:00Z",
                mapSource = mapSource,
                routingFiles = listOf(rd5),
                poiFile = localFileEntry(poiDb, "poi.db", "https://example.org/poi.db"),
            )

            val root = JSONObject(json)
            assertEquals(2, root.getInt("manifestVersion"))
            val region = root.getJSONArray("regions").getJSONObject(0)
            assertEquals("test-region", region.getString("regionId"))
            assertEquals("Regione di Test", region.getString("displayName"))

            val map = region.getJSONObject("map")
            assertEquals("2026.09.10", map.getString("version"))
            assertEquals(14, map.getJSONObject("source").getInt("maxZoom"))

            val routing = region.getJSONObject("routing")
            assertEquals("2026.09.10", routing.getString("version"))
            assertEquals("E5_N45.rd5", routing.getJSONArray("files").getJSONObject(0).getString("name"))

            val poiFile = region.getJSONObject("poi").getJSONObject("file")
            assertEquals("poi.db", poiFile.getString("name"))
            assertEquals(poiDb.length(), poiFile.getLong("sizeBytes"))
            assertEquals(sha256Of(poiDb), poiFile.getString("sha256"))
        } finally {
            packageDir.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fallisce se la rete stradale non ha segmenti`() {
        buildRegionFragmentJson(
            "test-region", "Regione di Test", "1", "2026-09-10T00:00:00Z", mapSource, emptyList(),
            ManifestFileEntry("poi.db", "https://example.org/poi.db", 1, "b".repeat(64)),
        )
    }

    @Test
    fun `buildRegionFragmentJsonFromSpec assembla poi_db locale e segmenti gia' hashati`() {
        val packageDir = createTempDirectory("pocket-travel-test-manifest-spec").toFile()
        try {
            val poiDb = File(packageDir, "poi.db").apply { writeText("contenuto reale") }
            val spec = JSONObject()
                .put("regionId", "sm")
                .put("displayName", "San Marino")
                .put("version", "2026.09.14")
                .put("poiDb", JSONObject().put("path", poiDb.absolutePath).put("url", "https://example.org/sm/poi.db"))
                .put(
                    "routingFiles",
                    JSONArray().put(
                        JSONObject()
                            .put("name", "E10_N40.rd5")
                            .put("url", "https://example.org/E10_N40.rd5")
                            .put("sizeBytes", 79_980_459L)
                            .put("sha256", "b".repeat(64)),
                    ),
                )
                .put(
                    "mapSource",
                    JSONObject()
                        .put("sourceUrl", "https://build.protomaps.com/20260914.pmtiles")
                        .put("minLon", 12.4).put("minLat", 43.85).put("maxLon", 12.52).put("maxLat", 43.99)
                        .put("minZoom", 0).put("maxZoom", 14),
                )

            val region = JSONObject(buildRegionFragmentJsonFromSpec(spec.toString())).getJSONArray("regions").getJSONObject(0)

            assertEquals("sm", region.getString("regionId"))
            assertEquals("https://example.org/sm/poi.db", region.getJSONObject("poi").getJSONObject("file").getString("url"))
            assertEquals(1, region.getJSONObject("routing").getJSONArray("files").length())
            assertEquals("https://build.protomaps.com/20260914.pmtiles", region.getJSONObject("map").getJSONObject("source").getString("sourceUrl"))
            assertFalse(region.has("poiExtra"))

            // POI extra facoltativi: stessa versione degli altri pacchetti, hash del file locale.
            val poiExtraDb = File(packageDir, "poi-extra.db").apply { writeText("extra") }
            spec.put("poiExtraDb", JSONObject().put("path", poiExtraDb.absolutePath).put("url", "https://example.org/sm/poi-extra.db"))
            val poiExtra = JSONObject(buildRegionFragmentJsonFromSpec(spec.toString()))
                .getJSONArray("regions").getJSONObject(0).getJSONObject("poiExtra")
            assertEquals("2026.09.14", poiExtra.getString("version"))
            assertEquals("poi-extra.db", poiExtra.getJSONObject("file").getString("name"))
            assertEquals(5L, poiExtra.getJSONObject("file").getLong("sizeBytes"))

            // Mappa d'insieme facoltativa: stessa versione, maxZoom dalla spec, niente fileXz (aggiunto
            // dopo da build-region.sh, come per poi.fileXz).
            val previewPmtiles = File(packageDir, "preview.pmtiles").apply { writeText("anteprima") }
            spec.put(
                "previewPmtiles",
                JSONObject().put("path", previewPmtiles.absolutePath).put("url", "https://example.org/sm/preview.pmtiles.xz").put("maxZoom", 9),
            )
            val preview = JSONObject(buildRegionFragmentJsonFromSpec(spec.toString()))
                .getJSONArray("regions").getJSONObject(0).getJSONObject("preview")
            assertEquals("2026.09.14", preview.getString("version"))
            assertEquals(9, preview.getInt("maxZoom"))
            assertEquals("preview.pmtiles", preview.getJSONObject("file").getString("name"))
            assertEquals(sha256Of(previewPmtiles), preview.getJSONObject("file").getString("sha256"))
            assertFalse(preview.has("fileXz"))
        } finally {
            packageDir.deleteRecursively()
        }
    }

    @Test
    fun `senza previewPmtiles nella spec la regione non ha la voce preview`() {
        val packageDir = createTempDirectory("pocket-travel-test-manifest-no-preview").toFile()
        try {
            val poiDb = File(packageDir, "poi.db").apply { writeText("contenuto") }
            val region = JSONObject(
                buildRegionFragmentJson(
                    "test-region", "Regione di Test", "1", "2026-09-10T00:00:00Z", mapSource,
                    routingFiles = listOf(rd5),
                    poiFile = localFileEntry(poiDb, "poi.db", "https://example.org/poi.db"),
                ),
            ).getJSONArray("regions").getJSONObject(0)

            assertFalse(region.has("preview"))
        } finally {
            packageDir.deleteRecursively()
        }
    }

    @Test
    fun `il frammento guide ha la voce guides e nessuna regione`() {
        val dir = createTempDirectory("pocket-travel-test-manifest-guides").toFile()
        try {
            val guidesDb = File(dir, "guides.db").apply { writeText("guide") }

            val root = JSONObject(
                buildGuidesFragmentJson(
                    "2026.09.23",
                    localFileEntry(guidesDb, "guides.db", "https://example.org/guides.db"),
                    mapOf("italia" to "https://it.wikivoyage.org/wiki/Italia"),
                ),
            )

            assertEquals(0, root.getJSONArray("regions").length())
            assertEquals("2026.09.23", root.getJSONObject("guides").getString("version"))
            assertEquals(sha256Of(guidesDb), root.getJSONObject("guides").getJSONObject("file").getString("sha256"))
            assertEquals("https://it.wikivoyage.org/wiki/Italia", root.getJSONObject("wikivoyageUrls").getString("italia"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = createTempDirectory("pocket-travel-test-manifest-extra").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun specFor(poiPath: String): JSONObject = JSONObject()
        .put("regionId", "sm")
        .put("displayName", "San Marino")
        .put("version", "2026.09.14")
        .put("poiDb", JSONObject().put("path", poiPath).put("url", "https://example.org/sm/poi.db"))
        .put(
            "routingFiles",
            JSONArray().put(
                JSONObject().put("name", "E10_N40.rd5").put("url", "https://example.org/E10_N40.rd5")
                    .put("sizeBytes", 1L).put("sha256", "b".repeat(64)),
            ),
        )
        .put(
            "mapSource",
            JSONObject().put("sourceUrl", "https://build.protomaps.com/20260914.pmtiles")
                .put("minLon", 12.4).put("minLat", 43.85).put("maxLon", 12.52).put("maxLat", 43.99)
                .put("minZoom", 0).put("maxZoom", 14),
        )

    @Test
    fun `sha256Of coincide con i vettori noti, anche per file vuoti`() {
        withTempDir { dir ->
            val empty = File(dir, "vuoto").apply { writeBytes(ByteArray(0)) }
            val abc = File(dir, "abc").apply { writeText("abc") }

            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Of(empty))
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Of(abc))
        }
    }

    @Test
    fun `sha256Of legge file piu' grandi del buffer e produce 64 caratteri esadecimali minuscoli`() {
        withTempDir { dir ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE * 3 + 7) { (it * 31).toByte() }
            val big = File(dir, "grande").apply { writeBytes(bytes) }
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

            assertEquals(expected, sha256Of(big))
            assertTrue(sha256Of(big).matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test
    fun `localFileEntry usa nome e URL passati, la dimensione reale e nessuna sourceKey`() {
        withTempDir { dir ->
            val file = File(dir, "qualunque.bin").apply { writeText("citta' è qui") }
            val entry = localFileEntry(file, "poi.db", "https://example.org/poi.db")

            assertEquals("poi.db", entry.name)
            assertEquals("https://example.org/poi.db", entry.url)
            assertEquals(file.length(), entry.sizeBytes)
            assertEquals(sha256Of(file), entry.sha256)
            assertNull(entry.sourceKey)
        }
    }

    @Test(expected = java.io.IOException::class)
    fun `localFileEntry fallisce se il file non esiste`() {
        localFileEntry(File("non-esiste-davvero.db"), "poi.db", "https://example.org/poi.db")
    }

    @Test
    fun `sourceKey dei segmenti compare solo se presente`() {
        val withKey = rd5.copy(name = "E5_N50.rd5", sourceKey = "123 5,50,10,55 0.5")
        val poi = ManifestFileEntry("poi.db", "https://example.org/poi.db", 1, "b".repeat(64))
        val files = JSONObject(buildRegionFragmentJson("r", "R", "1", "2026-09-10T00:00:00Z", mapSource, listOf(rd5, withKey), poi))
            .getJSONArray("regions").getJSONObject(0).getJSONObject("routing").getJSONArray("files")

        assertFalse(files.getJSONObject(0).has("sourceKey"))
        assertEquals("123 5,50,10,55 0.5", files.getJSONObject(1).getString("sourceKey"))
    }

    @Test
    fun `la spec con sourceKey vuota non la scrive e con sourceKey piena la porta nel routing`() {
        withTempDir { dir ->
            val poiDb = File(dir, "poi.db").apply { writeText("x") }
            val spec = specFor(poiDb.absolutePath)
            val routingFile = spec.getJSONArray("routingFiles").getJSONObject(0)

            routingFile.put("sourceKey", "")
            val empty = JSONObject(buildRegionFragmentJsonFromSpec(spec.toString()))
                .getJSONArray("regions").getJSONObject(0).getJSONObject("routing").getJSONArray("files").getJSONObject(0)
            assertFalse(empty.has("sourceKey"))

            routingFile.put("sourceKey", "99 1,2,3,4 0.1")
            val full = JSONObject(buildRegionFragmentJsonFromSpec(spec.toString()))
                .getJSONArray("regions").getJSONObject(0).getJSONObject("routing").getJSONArray("files").getJSONObject(0)
            assertEquals("99 1,2,3,4 0.1", full.getString("sourceKey"))
        }
    }

    @Test
    fun `la mappa d'insieme compare solo se ci sono sia il file sia il maxZoom`() {
        val poi = ManifestFileEntry("poi.db", "https://example.org/poi.db", 1, "b".repeat(64))
        val preview = ManifestFileEntry("preview.pmtiles", "https://example.org/preview.pmtiles.xz", 10, "c".repeat(64))
        fun region(file: ManifestFileEntry?, zoom: Int?) = JSONObject(
            buildRegionFragmentJson("r", "R", "7", "2026-09-10T00:00:00Z", mapSource, listOf(rd5), poi, previewFile = file, previewMaxZoom = zoom),
        ).getJSONArray("regions").getJSONObject(0)

        assertFalse(region(preview, null).has("preview"))
        assertFalse(region(null, 9).has("preview"))
        assertEquals(0, region(preview, 0).getJSONObject("preview").getInt("maxZoom"))
        assertEquals("7", region(preview, 9).getJSONObject("preview").getString("version"))
    }

    @Test
    fun `i POI extra del frammento hanno la stessa versione e il proprio file`() {
        val poi = ManifestFileEntry("poi.db", "https://example.org/poi.db", 1, "b".repeat(64))
        val extra = ManifestFileEntry("poi-extra.db", "https://example.org/poi-extra.db", 2, "d".repeat(64))
        val region = JSONObject(buildRegionFragmentJson("r", "R", "7", "2026-09-10T00:00:00Z", mapSource, listOf(rd5), poi, poiExtraFile = extra))
            .getJSONArray("regions").getJSONObject(0)

        assertEquals("7", region.getJSONObject("poiExtra").getString("version"))
        assertEquals("poi-extra.db", region.getJSONObject("poiExtra").getJSONObject("file").getString("name"))
        assertEquals(2L, region.getJSONObject("poiExtra").getJSONObject("file").getLong("sizeBytes"))
    }

    @Test
    fun `il frammento porta updatedAt e nome con caratteri unicode senza alterarli`() {
        val poi = ManifestFileEntry("poi.db", "https://example.org/poi.db", 1, "b".repeat(64))
        val name = "Citta' del Vaticano – Åland \"virgolette\" \\ barra"
        val region = JSONObject(buildRegionFragmentJson("r", name, "1", "2026-09-10T12:34:56Z", mapSource, listOf(rd5), poi))
            .getJSONArray("regions").getJSONObject(0)

        assertEquals(name, region.getString("displayName"))
        assertEquals("2026-09-10T12:34:56Z", region.getString("updatedAt"))
    }

    @Test
    fun `il frammento scrive il rettangolo e gli zoom della mappa con i tipi giusti`() {
        val poi = ManifestFileEntry("poi.db", "https://example.org/poi.db", 1, "b".repeat(64))
        val source = JSONObject(buildRegionFragmentJson("r", "R", "1", "2026-09-10T00:00:00Z", mapSource.copy(minLon = -179.5, maxLat = 83.25), listOf(rd5), poi))
            .getJSONArray("regions").getJSONObject(0).getJSONObject("map").getJSONObject("source")

        assertEquals(-179.5, source.getDouble("minLon"), 0.0)
        assertEquals(83.25, source.getDouble("maxLat"), 0.0)
        assertEquals(0, source.getInt("minZoom"))
        assertEquals(mapSource.sourceUrl, source.getString("sourceUrl"))
    }

    @Test
    fun `la spec con campi mancanti o JSON malformato fallisce`() {
        withTempDir { dir ->
            val poiDb = File(dir, "poi.db").apply { writeText("x") }
            listOf("regionId", "displayName", "version", "poiDb", "routingFiles", "mapSource").forEach { key ->
                val spec = specFor(poiDb.absolutePath).apply { remove(key) }
                assertThrows(org.json.JSONException::class.java) { buildRegionFragmentJsonFromSpec(spec.toString()) }
            }
            assertThrows(org.json.JSONException::class.java) { buildRegionFragmentJsonFromSpec("non e' json") }
        }
    }

    @Test
    fun `la spec senza segmenti della rete stradale fallisce`() {
        withTempDir { dir ->
            val poiDb = File(dir, "poi.db").apply { writeText("x") }
            val spec = specFor(poiDb.absolutePath).put("routingFiles", JSONArray())
            assertThrows(IllegalArgumentException::class.java) { buildRegionFragmentJsonFromSpec(spec.toString()) }
        }
    }

    @Test
    fun `la spec con un poi_db inesistente fallisce invece di scrivere un hash vuoto`() {
        withTempDir { dir ->
            val spec = specFor(File(dir, "manca.db").absolutePath)
            assertThrows(java.io.IOException::class.java) { buildRegionFragmentJsonFromSpec(spec.toString()) }
        }
    }

    @Test
    fun `la spec produce updatedAt come istante ISO-8601 valido`() {
        withTempDir { dir ->
            val poiDb = File(dir, "poi.db").apply { writeText("x") }
            val region = JSONObject(buildRegionFragmentJsonFromSpec(specFor(poiDb.absolutePath).toString())).getJSONArray("regions").getJSONObject(0)

            java.time.Instant.parse(region.getString("updatedAt"))
        }
    }

    @Test
    fun `il frammento guide senza link wikivoyage ha comunque l'oggetto wikivoyageUrls vuoto`() {
        val root = JSONObject(buildGuidesFragmentJson("1", ManifestFileEntry("guides.db", "https://example.org/g.db", 1, "c".repeat(64)), emptyMap()))

        assertEquals(0, root.getJSONObject("wikivoyageUrls").length())
        assertEquals(0, root.getJSONArray("regions").length())
        assertEquals(MANIFEST_VERSION, root.getInt("manifestVersion"))
    }

    @Test
    fun `il frammento guide porta sourceKey solo se presente e url con caratteri unicode`() {
        val entry = ManifestFileEntry("guides.db", "https://example.org/g.db", 1, "c".repeat(64), sourceKey = "k")
        val urls = mapOf("citta-del-messico" to "https://it.wikivoyage.org/wiki/Citt%C3%A0_del_Messico", "åland" to "https://it.wikivoyage.org/wiki/Åland")
        val root = JSONObject(buildGuidesFragmentJson("1", entry, urls))

        assertEquals("k", root.getJSONObject("guides").getJSONObject("file").getString("sourceKey"))
        assertEquals("https://it.wikivoyage.org/wiki/Åland", root.getJSONObject("wikivoyageUrls").getString("åland"))
        assertEquals(2, root.getJSONObject("wikivoyageUrls").length())
    }
}
