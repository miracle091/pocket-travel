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
    fun `GPT-6, Claude dalla 4-7 e Gemini dalla 3-6 non accettano la temperatura`() {
        assertFalse(supportsTemperature("gpt-6-luna"))
        assertFalse(supportsTemperature("claude-opus-4-7"))
        assertFalse(supportsTemperature("claude-haiku-5-5"))
        assertFalse(supportsTemperature("claude-sonnet-5-5-20261001"))
        assertFalse(supportsTemperature("gemini-3.6-flash"))
        assertFalse(supportsTemperature("gemini-4-argon"))
        assertFalse(supportsTemperature("claude-opus-4-10"))
        assertFalse(supportsTemperature("claude-opus-10"))
        assertFalse(supportsTemperature("gemini-3.10-flash"))
        assertFalse(supportsTemperature("gemini-10-pro"))
    }

    @Test
    fun `Claude fino alla 4-6, Claude 3 e Gemini fino alla 3-5 accettano la temperatura`() {
        assertTrue(supportsTemperature("claude-sonnet-4-6"))
        assertTrue(supportsTemperature("claude-3-5-sonnet"))
        assertTrue(supportsTemperature("gemini-2.5-pro"))
        assertTrue(supportsTemperature("gemini-3.5-flash"))
    }

    @Test
    fun `nell'elenco incluso nell'APK la temperatura coincide con la regola sul nome`() {
        val list = bundledOnlineModels()
        list.providers.values.flatten().forEach { assertEquals(it.id, supportsTemperature(it.id), it.temperature) }
    }
}
