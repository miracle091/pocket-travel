package com.pockettravel.feature.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlinePromptTemplatesTest {

    @Test
    fun `online prompt embeds the question and defers to official sources on uncertainty`() {
        val prompt = OnlinePromptTemplates.onlinePrompt("Serve il visto per il Perù?")

        assertTrue(prompt.contains("DOMANDA: Serve il visto per il Perù?"))
        assertTrue(prompt.contains("fonte ufficiale"))
        assertFalse(prompt.contains("CONTESTO"))
    }
}
