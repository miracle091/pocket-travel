package com.pockettravel.feature.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Il riconoscimento di "modello non disponibile" dal solo codice e corpo HTTP, senza rete.
class OnlineModelNotFoundTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `404 e' sempre un modello sconosciuto, qualunque sia il corpo`() {
        assertTrue(isModelNotFound(404, "", json))
        assertTrue(isModelNotFound(404, "<html>not found</html>", json))
    }

    @Test
    fun `400 vale solo con model_not_found nel codice o nel tipo dell'errore`() {
        assertTrue(isModelNotFound(400, """{"error":{"code":"model_not_found"}}""", json))
        assertTrue(isModelNotFound(400, """{"error":{"type":"model_not_found"}}""", json))
        assertFalse(isModelNotFound(400, """{"error":{"code":"invalid_request_error","type":"invalid_request_error"}}""", json))
    }

    @Test
    fun `400 con un corpo che non ha la forma attesa non e' un modello sconosciuto`() {
        assertFalse(isModelNotFound(400, "", json))
        assertFalse(isModelNotFound(400, "model_not_found", json))
        assertFalse(isModelNotFound(400, """{"error":"model_not_found"}""", json))
        assertFalse(isModelNotFound(400, """{"detail":{"code":"model_not_found"}}""", json))
        assertFalse(isModelNotFound(400, "[]", json))
    }

    @Test
    fun `gli altri codici non sono mai un modello sconosciuto`() {
        assertFalse(isModelNotFound(200, """{"error":{"code":"model_not_found"}}""", json))
        assertFalse(isModelNotFound(401, """{"error":{"code":"model_not_found"}}""", json))
        assertFalse(isModelNotFound(500, "", json))
    }
}
