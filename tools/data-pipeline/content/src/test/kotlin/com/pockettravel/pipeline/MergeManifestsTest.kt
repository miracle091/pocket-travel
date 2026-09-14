package com.pockettravel.pipeline

import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeManifestsTest {

    private fun fragmentFor(regionDir: File, regionId: String, displayName: String, version: String = "1"): String {
        val file = File(regionDir, "content.db").apply { writeText("contenuto di test per $regionId") }
        val manifestFiles = listOf(localFileEntry(file, "content.db", "https://example.org/$regionId/content.db"))
        return buildManifestJson(regionId, displayName, version, "2026-09-14T00:00:00Z", manifestFiles)
    }

    @Test
    fun `unisce piu' manifest in uno unico con tutte le regioni`() {
        val dir = createTempDirectory("pocket-travel-test-merge").toFile()
        try {
            val sanMarino = fragmentFor(dir, "san-marino", "San Marino")
            val italia = fragmentFor(dir, "italia", "Italia")

            val merged = JSONObject(mergeManifestJson(listOf(sanMarino, italia)))

            assertEquals(1, merged.getInt("manifestVersion"))
            val regions = merged.getJSONArray("regions")
            assertEquals(2, regions.length())
            val regionIds = (0 until regions.length()).map { regions.getJSONObject(it).getString("regionId") }.toSet()
            assertEquals(setOf("san-marino", "italia"), regionIds)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `una regionId duplicata viene sostituita dall'ultimo manifest fornito`() {
        val dir = createTempDirectory("pocket-travel-test-merge-dup").toFile()
        try {
            val italiaV1 = fragmentFor(dir, "italia", "Italia", version = "1")
            val italiaV2 = fragmentFor(dir, "italia", "Italia", version = "2")

            val merged = JSONObject(mergeManifestJson(listOf(italiaV1, italiaV2)))

            val regions = merged.getJSONArray("regions")
            assertEquals(1, regions.length())
            assertEquals("2", regions.getJSONObject(0).getString("version"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `una pubblicazione parziale non fa sparire le regioni non toccate`() {
        val dir = createTempDirectory("pocket-travel-test-merge-partial").toFile()
        try {
            val giaPubblicato = mergeManifestJson(
                listOf(fragmentFor(dir, "giappone", "Giappone"), fragmentFor(dir, "stati-uniti", "Stati Uniti")),
            )
            val nuovoFrammento = fragmentFor(dir, "stati-uniti", "Stati Uniti", version = "2")

            val merged = JSONObject(mergeManifestJson(listOf(giaPubblicato, nuovoFrammento)))

            val regions = merged.getJSONArray("regions")
            assertEquals(2, regions.length())
            val byId = (0 until regions.length()).associate {
                regions.getJSONObject(it).getString("regionId") to regions.getJSONObject(it).getString("version")
            }
            assertEquals("1", byId.getValue("giappone"))
            assertEquals("2", byId.getValue("stati-uniti"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fallisce se non ci sono manifest da unire`() {
        mergeManifestJson(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fallisce se un manifest ha una manifestVersion non supportata`() {
        mergeManifestJson(listOf("""{"manifestVersion": 2, "regions": []}"""))
    }

    @Test
    fun `il risultato e' JSON valido riparsabile`() {
        val dir = createTempDirectory("pocket-travel-test-merge-json").toFile()
        try {
            val merged = mergeManifestJson(listOf(fragmentFor(dir, "san-marino", "San Marino")))
            assertTrue(JSONObject(merged).has("regions"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
