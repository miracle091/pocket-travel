package com.pockettravel.feature.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineKeyRejectedTest {

    @Test
    fun `401 e' sempre la chiave`() {
        assertTrue(isApiKeyRejected(401, ""))
    }

    @Test
    fun `Gemini risponde 400 a una chiave sbagliata`() {
        // Corpo vero dell'endpoint compatibile OpenAI di Gemini (2026-11-13) e quello dell'API nativa.
        val openAiCompatible = """[{"error":{"code":400,"message":"Please pass a valid API key","status":"INVALID_ARGUMENT"}}]"""
        val native = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT",""" +
            """"details":[{"reason":"API_KEY_INVALID"}]}}"""
        assertTrue(isApiKeyRejected(400, openAiCompatible))
        assertTrue(isApiKeyRejected(400, native))
    }

    @Test
    fun `un 400 qualunque non e' la chiave`() {
        assertFalse(isApiKeyRejected(400, """{"error":{"message":"Invalid value for temperature","type":"invalid_request_error"}}"""))
        assertFalse(isApiKeyRejected(400, ""))
    }
}
