package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportsTemperatureTest {

    @Test
    fun `i modelli GPT-5 non accettano la temperatura, anche con maiuscole e spazi`() {
        assertFalse(supportsTemperature("gpt-5"))
        assertFalse(supportsTemperature("gpt-5.6-terra"))
        assertFalse(supportsTemperature("GPT-5-mini"))
        assertFalse(supportsTemperature("  gpt-5  "))
    }

    @Test
    fun `i modelli o di OpenAI non accettano la temperatura`() {
        assertFalse(supportsTemperature("o1"))
        assertFalse(supportsTemperature("o3-mini"))
        assertFalse(supportsTemperature("O4-mini"))
    }

    @Test
    fun `gli altri modelli accettano la temperatura`() {
        assertTrue(supportsTemperature("gpt-4o-mini"))
        assertTrue(supportsTemperature("gpt-4.1"))
        assertTrue(supportsTemperature("mistral-small-latest"))
        assertTrue(supportsTemperature("gemini-flash-latest"))
        assertTrue(supportsTemperature("claude-haiku-4-5"))
    }

    @Test
    fun `una o seguita da una lettera o un nome con prefisso non e' un modello o`() {
        assertTrue(supportsTemperature("omni-moderation-latest"))
        assertTrue(supportsTemperature("openai/o3"))
        assertTrue(supportsTemperature("my-gpt-5"))
        assertTrue(supportsTemperature(""))
    }

    @Test
    fun `il modello automatico di ogni servizio accetta la temperatura, quello capace di ChatGPT no`() {
        OnlineProvider.entries.forEach { assertTrue(it.name, supportsTemperature(it.autoModel)) }
        assertEquals(false, supportsTemperature(OnlineProvider.CHATGPT.capableModel))
    }
}
