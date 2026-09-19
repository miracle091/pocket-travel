package com.pockettravel.core.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppStatusTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses appVersion and aiModels when present`() {
        val statusJson = """
            {
              "appVersion": { "versionName": "0.3.0", "versionCode": 3 },
              "aiModels": [
                { "modelId": "qwen3-0.6b", "modelVersion": "qwen3_0_6b_mixed_int4.litertlm", "sha256": "${"a".repeat(64)}", "sizeBytes": 612368384 }
              ]
            }
        """.trimIndent()

        val status = json.decodeFromString(AppStatus.serializer(), statusJson)

        assertEquals("0.3.0", status.appVersion?.versionName)
        assertEquals(3, status.appVersion?.versionCode)
        assertEquals("qwen3-0.6b", status.aiModels.single().modelId)
        assertEquals("qwen3_0_6b_mixed_int4.litertlm", status.aiModels.single().modelVersion)
        assertEquals(612_368_384L, status.aiModels.single().sizeBytes)
    }

    @Test
    fun `appVersion is optional and aiModels defaults to empty`() {
        val status = json.decodeFromString(AppStatus.serializer(), "{}")

        assertNull(status.appVersion)
        assertEquals(emptyList<Any>(), status.aiModels)
    }

    @Test
    fun `ignores unknown fields for forward compatibility`() {
        val statusJson = """{ "generatedBy": "publish-apk.yml", "appVersion": { "versionName": "0.3.0", "versionCode": 3 } }"""

        val status = json.decodeFromString(AppStatus.serializer(), statusJson)

        assertEquals("0.3.0", status.appVersion?.versionName)
    }
}
