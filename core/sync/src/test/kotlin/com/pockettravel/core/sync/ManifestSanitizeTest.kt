package com.pockettravel.core.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ManifestSanitizeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sha = "a".repeat(64)
    private val guidesUrl = "https://github.com/o/r/g.db"

    private fun guides(url: String = guidesUrl) = """{"version":"1","file":{"name":"guides.db","url":"$url","sizeBytes":1,"sha256":"$sha"}}"""

    private fun manifest(
        guides: String = guides(),
        guidesEn: String = guides(),
        worldMap: String = """{"version":"1","maxZoom":8,"url":"https://github.com/o/r/world.pmtiles","sizeBytes":10}""",
        addressGrid: String = """{"version":"1","url":"https://github.com/o/r/a.json","sizeBytes":1,"sha256":"$sha"}""",
        transit: String = """{"version":"1","url":"https://github.com/o/r/t.json","sizeBytes":1,"sha256":"$sha"}""",
    ) = json.decodeFromString(
        RegionManifest.serializer(),
        """{"manifestVersion":2,"guides":$guides,"guidesEn":$guidesEn,"worldMap":$worldMap,"addressGrid":$addressGrid,"transit":$transit,"regions":[]}""",
    )

    @Test
    fun `un manifest valido resta com'e'`() {
        val dropped = mutableListOf<String>()
        val parsed = manifest()
        assertEquals(parsed, sanitizeManifest(parsed) { what, _ -> dropped += what })
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun `una voce globale facoltativa non valida si scarta senza far fallire il manifest`() {
        val dropped = mutableListOf<String>()
        val bad = "https://evil.example.org/x"
        val result = sanitizeManifest(
            manifest(
                guidesEn = guides(bad),
                worldMap = """{"version":"1","maxZoom":99,"url":"https://github.com/o/r/world.pmtiles","sizeBytes":10}""",
                addressGrid = """{"version":"1","url":"$bad","sizeBytes":1,"sha256":"$sha"}""",
                transit = """{"version":"1","url":"https://github.com/o/r/t.json","sizeBytes":1,"sha256":"xyz"}""",
            ),
        ) { what, _ -> dropped += what }
        assertNull(result.guidesEn)
        assertNull(result.worldMap)
        assertNull(result.addressGrid)
        assertNull(result.transit)
        assertNotNull(result.guides)
        assertEquals(listOf("guidesEn", "worldMap", "addressGrid", "transit"), dropped)
    }

    @Test
    fun `una sola voce rotta non tocca le altre`() {
        val result = sanitizeManifest(manifest(addressGrid = """{"version":"1","url":"https://evil.example.org/x","sizeBytes":1,"sha256":"$sha"}""")) { _, _ -> }
        assertNull(result.addressGrid)
        assertNotNull(result.guidesEn)
        assertNotNull(result.worldMap)
        assertNotNull(result.transit)
    }

    @Test
    fun `le guide obbligatorie non valide fanno fallire il manifest`() {
        assertThrows(IllegalArgumentException::class.java) {
            sanitizeManifest(manifest(guides = guides("https://evil.example.org/g.db"))) { _, _ -> }
        }
    }
}
