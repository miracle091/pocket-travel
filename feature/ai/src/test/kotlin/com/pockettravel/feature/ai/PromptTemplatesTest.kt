package com.pockettravel.feature.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesTest {

    @Test
    fun `on-device prompt embeds context and question and constrains the answer`() {
        val prompt = PromptTemplates.onDevicePrompt(
            context = "Le dogane giapponesi richiedono la dichiarazione di importi elevati.",
            question = "Posso portare farmaci da banco in Giappone?",
        )

        assertTrue(prompt.contains("CONTESTO: Le dogane giapponesi"))
        assertTrue(prompt.contains("DOMANDA: Posso portare farmaci da banco in Giappone?"))
        assertTrue(prompt.contains("massimo 3 frasi"))
        assertTrue(prompt.contains("in italiano"))
    }
}
