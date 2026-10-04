package com.pockettravel.feature.ai

import com.pockettravel.core.sync.AiModelManifestEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PublishedFingerprintTest {

    private val model = LlmModelDefinition(
        id = "pt-qwen3.5-0.8b",
        displayName = "Pocket Travel 0.8B",
        url = "https://example.org/model.gguf",
        fileName = "model.gguf",
        sha256 = "a".repeat(64),
        sizeBytes = 100L,
        minRamTier = RamTier.MINIMO,
    )

    private fun entry(modelId: String = model.id, fileName: String = model.fileName) =
        AiModelManifestEntry(modelId = modelId, modelVersion = fileName, sha256 = "B".repeat(64), sizeBytes = 200L)

    @Test
    fun `published fingerprint for the same file replaces the APK one`() {
        val resolved = model.withPublishedFingerprint(listOf(entry()))

        assertEquals("b".repeat(64), resolved.sha256)
        assertEquals(200L, resolved.sizeBytes)
        assertEquals(model.url, resolved.url)
    }

    @Test
    fun `without a published entry the APK fingerprint stays`() {
        assertSame(model, model.withPublishedFingerprint(emptyList()))
    }

    @Test
    fun `entry for another model or another file is ignored`() {
        val published = listOf(entry(modelId = "pt-qwen3.5-2b"), entry(fileName = "other.gguf"))

        assertSame(model, model.withPublishedFingerprint(published))
    }
}
