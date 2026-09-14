package com.pockettravel.pipeline

import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateManifestTest {

    @Test
    fun `il manifest generato rispetta lo schema atteso da RegionManifest`() {
        val packageDir = createTempDirectory("pocket-travel-test-manifest").toFile()
        try {
            val contentDb = File(packageDir, "content.db").apply { writeText("contenuto di test") }
            val files = listOf(
                localFileEntry(contentDb, "content.db", "https://example.org/test-region/content.db"),
                ManifestFileEntry(
                    name = "E5_N45.rd5",
                    url = "https://brouter.de/brouter/segments4/E5_N45.rd5",
                    sizeBytes = 79_980_459L,
                    sha256 = "a".repeat(64),
                ),
            )

            val json = buildManifestJson(
                regionId = "test-region",
                displayName = "Regione di Test",
                version = "2026.09.10",
                updatedAt = "2026-09-10T00:00:00Z",
                files = files,
                mapSource = MapSourceInput(
                    sourceUrl = "https://build.protomaps.com/20260910.pmtiles",
                    minLon = 10.0, minLat = 42.0, maxLon = 12.0, maxLat = 44.0,
                    minZoom = 0, maxZoom = 14,
                ),
            )

            val root = JSONObject(json)
            assertEquals(1, root.getInt("manifestVersion"))
            val region = root.getJSONArray("regions").getJSONObject(0)
            assertEquals("test-region", region.getString("regionId"))
            assertEquals("Regione di Test", region.getString("displayName"))

            val filesJson = region.getJSONArray("files")
            assertEquals(2, filesJson.length())
            val contentDbJson = (0 until filesJson.length()).map { filesJson.getJSONObject(it) }
                .first { it.getString("name") == "content.db" }
            assertEquals(contentDb.length(), contentDbJson.getLong("sizeBytes"))
            assertEquals(sha256Of(contentDb), contentDbJson.getString("sha256"))

            val mapSourceJson = region.getJSONObject("mapSource")
            assertEquals("https://build.protomaps.com/20260910.pmtiles", mapSourceJson.getString("sourceUrl"))
            assertEquals(14, mapSourceJson.getInt("maxZoom"))
        } finally {
            packageDir.deleteRecursively()
        }
    }

    @Test
    fun `mapSource e' assente se non fornito`() {
        val packageDir = createTempDirectory("pocket-travel-test-manifest-no-map").toFile()
        try {
            val contentDb = File(packageDir, "content.db").apply { writeText("x") }
            val json = buildManifestJson(
                regionId = "test-region",
                displayName = "Regione di Test",
                version = "1",
                updatedAt = "2026-09-10T00:00:00Z",
                files = listOf(localFileEntry(contentDb, "content.db", "https://example.org/content.db")),
            )

            val region = JSONObject(json).getJSONArray("regions").getJSONObject(0)
            assertFalse(region.has("mapSource"))
        } finally {
            packageDir.deleteRecursively()
        }
    }

    @Test
    fun `buildManifestJsonFromSpec assembla content_db locale e file remoti gia' hashati`() {
        val packageDir = createTempDirectory("pocket-travel-test-manifest-spec").toFile()
        try {
            val contentDb = File(packageDir, "content.db").apply { writeText("contenuto reale") }
            val spec = JSONObject()
                .put("regionId", "sm")
                .put("displayName", "San Marino")
                .put("version", "2026.09.14")
                .put(
                    "contentDb",
                    JSONObject().put("path", contentDb.absolutePath).put("url", "https://example.org/sm/content.db"),
                )
                .put(
                    "remoteFiles",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("name", "E10_N40.rd5")
                            .put("url", "https://brouter.de/brouter/segments4/E10_N40.rd5")
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

            val json = buildManifestJsonFromSpec(spec.toString())

            val region = JSONObject(json).getJSONArray("regions").getJSONObject(0)
            assertEquals("sm", region.getString("regionId"))
            val filesJson = region.getJSONArray("files")
            assertEquals(2, filesJson.length())
            assertTrue((0 until filesJson.length()).map { filesJson.getJSONObject(it).getString("name") }
                .containsAll(listOf("content.db", "E10_N40.rd5")))
        } finally {
            packageDir.deleteRecursively()
        }
    }
}
