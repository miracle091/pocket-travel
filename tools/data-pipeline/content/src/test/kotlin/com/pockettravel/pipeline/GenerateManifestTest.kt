package com.pockettravel.pipeline

import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GenerateManifestTest {

    private val mapSource = MapSourceInput(
        sourceUrl = "https://build.protomaps.com/20260910.pmtiles",
        minLon = 10.0, minLat = 42.0, maxLon = 12.0, maxLat = 44.0,
        minZoom = 0, maxZoom = 14,
    )
    private val rd5 = ManifestFileEntry("E5_N45.rd5", "https://example.org/E5_N45.rd5", 79_980_459L, "a".repeat(64))

    @Test
    fun `il frammento di regione separa mappa, routing e POI con le rispettive versioni`() {
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
    fun `fallisce se il routing non ha segmenti`() {
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
}
