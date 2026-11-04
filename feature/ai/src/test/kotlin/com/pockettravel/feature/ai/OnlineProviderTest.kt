package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Indirizzi con l'ID dell'account, condizioni dei servizi e filtro ZDR di OpenRouter.
class OnlineProviderTest {

    @Test
    fun `services without an ID keep their address`() {
        assertEquals("https://api.regolo.ai/v1", OnlineProvider.REGOLO.baseUrlFor(null))
        assertEquals("https://api.openai.com/v1", OnlineProvider.CHATGPT.baseUrlFor("ignorato"))
    }

    @Test
    fun `the ID goes into the address of Alibaba, Cloudflare and Infomaniak`() {
        assertEquals(
            "https://llm-abc123.eu-central-1.maas.aliyuncs.com/compatible-mode/v1",
            OnlineProvider.ALIBABA.baseUrlFor(" llm-abc123 "),
        )
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/0123abcd/ai/v1",
            OnlineProvider.CLOUDFLARE.baseUrlFor("0123abcd"),
        )
        assertEquals("https://api.infomaniak.com/2/ai/4242/openai/v1", OnlineProvider.INFOMANIAK.baseUrlFor("4242"))
    }

    @Test
    fun `a missing or unsafe ID gives no address, so the key cannot reach another server`() {
        assertNull(OnlineProvider.ALIBABA.baseUrlFor(null))
        assertNull(OnlineProvider.ALIBABA.baseUrlFor(""))
        listOf("evil.example.com/", "a.b", "x@evil.com", "a/../b", "a b", "a?b", "a#b").forEach {
            assertNull(it, OnlineProvider.ALIBABA.baseUrlFor(it))
            assertNull(it, OnlineProvider.CLOUDFLARE.baseUrlFor(it))
        }
    }

    @Test
    fun `every service has a privacy page and an address on https`() {
        OnlineProvider.entries.forEach {
            assertTrue(it.name, it.terms.privacyUrl.startsWith("https://"))
            assertTrue(it.name, it.baseUrl.startsWith("https://"))
            assertEquals(it.name, it.terms.idField != null, "{id}" in it.baseUrl)
        }
    }

    @Test
    fun `OpenRouter asks for zero data retention, the other services send no routing`() {
        val server = MockWebServer()
        server.start()
        try {
            val client = OnlineLlmClient(OkHttpClient(), Json { ignoreUnknownKeys = true }, Dispatchers.IO)
            repeat(2) { server.enqueue(sseResponse("ok")) }
            runBlocking {
                client.stream(server.url("/v1").toString(), "k", "m", "ciao", sendTemperature = false, zeroDataRetention = true).toList()
                client.stream(server.url("/v1").toString(), "k", "m", "ciao", sendTemperature = false).toList()
            }
            val withZdr = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertTrue(withZdr.getValue("provider").jsonObject.getValue("zdr").jsonPrimitive.boolean)
            val without = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertFalse("provider" in without)
        } finally {
            server.shutdown()
        }
    }
}
