package com.pockettravel.core.sync

import org.junit.Assert.assertThrows
import org.junit.Test

class AppVersionAndAiModelValidationTest {

    @Test
    fun `accetta una voce appVersion valida`() {
        AppVersionEntry(versionName = "0.3.0", versionCode = 3).validate()
    }

    @Test
    fun `rifiuta un versionCode non positivo`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppVersionEntry(versionName = "0.3.0", versionCode = 0).validate()
        }
    }

    @Test
    fun `rifiuta un versionName con caratteri non sicuri`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppVersionEntry(versionName = "0.3.0; rm -rf", versionCode = 3).validate()
        }
    }

    @Test
    fun `accetta una voce appVersion con l'impronta dell'APK`() {
        AppVersionEntry(versionName = "0.15.0", versionCode = 15, apkSha256 = "a".repeat(64), apkSizeBytes = 19_000_000L).validate()
    }

    @Test
    fun `rifiuta un'impronta dell'APK senza la dimensione, o la dimensione senza l'impronta`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppVersionEntry(versionName = "0.15.0", versionCode = 15, apkSha256 = "a".repeat(64)).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVersionEntry(versionName = "0.15.0", versionCode = 15, apkSizeBytes = 19_000_000L).validate()
        }
    }

    @Test
    fun `rifiuta un'impronta dell'APK non esadecimale a 64 cifre o una dimensione non positiva`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppVersionEntry(versionName = "0.15.0", versionCode = 15, apkSha256 = "g".repeat(64), apkSizeBytes = 19_000_000L).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVersionEntry(versionName = "0.15.0", versionCode = 15, apkSha256 = "a".repeat(63), apkSizeBytes = 19_000_000L).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppVersionEntry(versionName = "0.15.0", versionCode = 15, apkSha256 = "a".repeat(64), apkSizeBytes = 0L).validate()
        }
    }

    @Test
    fun `accetta una voce aiModel valida`() {
        AiModelManifestEntry(modelId = "qwen3-0.6b", modelVersion = "qwen3-0.6b-v2", sha256 = "a".repeat(64), sizeBytes = 600_000_000L).validate()
    }

    @Test
    fun `rifiuta uno sha256 malformato`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiModelManifestEntry(modelId = "qwen3-0.6b", modelVersion = "qwen3-0.6b-v2", sha256 = "not-a-hash", sizeBytes = 600_000_000L).validate()
        }
    }

    @Test
    fun `rifiuta un sizeBytes negativo`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiModelManifestEntry(modelId = "qwen3-0.6b", modelVersion = "qwen3-0.6b-v2", sha256 = "a".repeat(64), sizeBytes = -1L).validate()
        }
    }

    @Test
    fun `rifiuta un modelId non valido`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiModelManifestEntry(modelId = "qwen 3; rm -rf", modelVersion = "qwen3-0.6b-v2", sha256 = "a".repeat(64), sizeBytes = 600_000_000L).validate()
        }
    }
}
