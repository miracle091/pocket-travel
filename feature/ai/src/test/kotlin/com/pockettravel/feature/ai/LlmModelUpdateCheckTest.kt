package com.pockettravel.feature.ai

import com.pockettravel.core.sync.AiModelManifestEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelUpdateCheckTest {

    private val installedSha256 = "a".repeat(64)

    @Test
    fun `reports an update when remote sha256 differs`() {
        val remote = AiModelManifestEntry("qwen3-0.6b", "qwen3-0.6b-v2", "b".repeat(64), 600_000_000L)

        assertTrue(isAiModelUpdateAvailable(installedSha256, remote))
    }

    @Test
    fun `reports no update when remote sha256 matches installed`() {
        val remote = AiModelManifestEntry("qwen3-0.6b", "qwen3-0.6b", installedSha256, 584_000_000L)

        assertFalse(isAiModelUpdateAvailable(installedSha256, remote))
    }

    @Test
    fun `comparison is case-insensitive`() {
        val remote = AiModelManifestEntry("qwen3-0.6b", "qwen3-0.6b", installedSha256.uppercase(), 584_000_000L)

        assertFalse(isAiModelUpdateAvailable(installedSha256, remote))
    }
}
