package com.pockettravel.core.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppStatusTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legge appVersion e aiModels quando ci sono`() {
        val statusJson = """
            {
              "appVersion": { "versionName": "0.3.0", "versionCode": 3 },
              "aiModels": [
                { "modelId": "qwen3-0.6b", "modelVersion": "Qwen3-0.6B-Q4_K_M.gguf", "sha256": "${"a".repeat(64)}", "sizeBytes": 612368384 }
              ]
            }
        """.trimIndent()

        val status = json.decodeFromString(AppStatus.serializer(), statusJson)

        assertEquals("0.3.0", status.appVersion?.versionName)
        assertEquals(3, status.appVersion?.versionCode)
        assertEquals("qwen3-0.6b", status.aiModels.single().modelId)
        assertEquals("Qwen3-0.6B-Q4_K_M.gguf", status.aiModels.single().modelVersion)
        assertEquals(612_368_384L, status.aiModels.single().sizeBytes)
    }

    @Test
    fun `appVersion e' facoltativo e aiModels senza valore e' vuoto`() {
        val status = json.decodeFromString(AppStatus.serializer(), "{}")

        assertNull(status.appVersion)
        assertEquals(emptyList<Any>(), status.aiModels)
    }

    @Test
    fun `ignora i campi sconosciuti per compatibilita' con le versioni future`() {
        val statusJson = """{ "generatedBy": "publish-apk.yml", "appVersion": { "versionName": "0.3.0", "versionCode": 3 } }"""

        val status = json.decodeFromString(AppStatus.serializer(), statusJson)

        assertEquals("0.3.0", status.appVersion?.versionName)
    }
}
