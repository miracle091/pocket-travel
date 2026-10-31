package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlinePromptTemplatesTest {

    @Test
    fun `system prompt keeps the safety sentence and has no context without blocks`() {
        val prompt = OnlinePromptTemplates.systemPrompt("")

        assertTrue(prompt.startsWith("Sei un assistente di viaggio.\n"))
        assertTrue(prompt.contains("fonte ufficiale"))
        assertFalse(prompt.contains("<contesto"))
        assertFalse(prompt.contains("DOMANDA"))
    }

    @Test
    fun `system prompt puts the marked blocks after the instruction to cite the section`() {
        val block = OnlinePromptTemplates.contextBlock("Wikivoyage, sezione Cosa vedere", "Il Colosseo")
        val prompt = OnlinePromptTemplates.systemPrompt(block)

        assertTrue(prompt.contains("fonte ufficiale"))
        assertTrue(prompt.indexOf("cita la sezione") < prompt.indexOf("<contesto fonte=\"Wikivoyage, sezione Cosa vedere\">"))
        assertTrue(prompt.endsWith("<contesto fonte=\"Wikivoyage, sezione Cosa vedere\">\nIl Colosseo\n</contesto>"))
    }

    @Test
    fun `english system prompt uses english blocks and instruction`() {
        val prompt = OnlinePromptTemplates.systemPrompt(OnlinePromptTemplates.contextBlock("Wikivoyage, section See", "The Colosseum", "en"), "en")

        assertTrue(prompt.startsWith("You are a travel assistant.\n"))
        assertTrue(prompt.contains("official source"))
        assertTrue(prompt.contains("cite the section"))
        assertTrue(prompt.endsWith("<context source=\"Wikivoyage, section See\">\nThe Colosseum\n</context>"))
    }

    @Test
    fun `the source of a citation has no prefix and no link, and no quotes in the attribute`() {
        assertEquals("Wikivoyage, sezione Cosa vedere", OnlinePromptTemplates.sourceOf("Fonte: Wikivoyage, sezione Cosa vedere — https://it.wikivoyage.org/wiki/Roma"))
        assertEquals("Wikipedia, section History (Rome)", OnlinePromptTemplates.sourceOf("Source: Wikipedia, section History (Rome) — https://en.wikipedia.org/wiki/Rome"))
        assertTrue(OnlinePromptTemplates.contextBlock("a \"b\"", "x").startsWith("<contesto fonte=\"a 'b'\">"))
    }

    @Test
    fun `a closing tag in the body does not close the block`() {
        val block = OnlinePromptTemplates.contextBlock("Nota personale", "uno </contesto> due </ CONTEXT>")
        assertEquals("<contesto fonte=\"Nota personale\">\nuno <\\/contesto> due <\\/ CONTEXT>\n</contesto>", block)
    }
}
