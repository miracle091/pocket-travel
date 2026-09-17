package com.pockettravel.core.sync

import org.junit.Assert.assertThrows
import org.junit.Test

class AppVersionAndAiModelValidationTest {

    @Test
    fun `accepts a valid appVersion entry`() {
        AppVersionEntry(versionName = "0.3.0", versionCode = 3).validate()
    }

    @Test
    fun `rejects a non-positive versionCode`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppVersionEntry(versionName = "0.3.0", versionCode = 0).validate()
        }
    }

    @Test
    fun `rejects a versionName with unsafe characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppVersionEntry(versionName = "0.3.0; rm -rf", versionCode = 3).validate()
        }
    }

    @Test
    fun `accepts a valid aiModel entry`() {
        AiModelManifestEntry(modelId = "gemma3-1b-it", modelVersion = "gemma3-1b-it-int4-v2", sha256 = "a".repeat(64), sizeBytes = 600_000_000L).validate()
    }

    @Test
    fun `rejects a malformed sha256`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiModelManifestEntry(modelId = "gemma3-1b-it", modelVersion = "gemma3-1b-it-int4-v2", sha256 = "not-a-hash", sizeBytes = 600_000_000L).validate()
        }
    }

    @Test
    fun `rejects a negative sizeBytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiModelManifestEntry(modelId = "gemma3-1b-it", modelVersion = "gemma3-1b-it-int4-v2", sha256 = "a".repeat(64), sizeBytes = -1L).validate()
        }
    }

    @Test
    fun `rejects an invalid modelId`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiModelManifestEntry(modelId = "gemma 3n; rm -rf", modelVersion = "gemma3-1b-it-int4-v2", sha256 = "a".repeat(64), sizeBytes = 600_000_000L).validate()
        }
    }
}
