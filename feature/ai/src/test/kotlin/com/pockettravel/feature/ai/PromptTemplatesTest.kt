package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
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

    @Test
    fun `english prompt when the app is in English`() {
        val prompt = PromptTemplates.onDevicePrompt(context = "Customs rules.", question = "Can I bring medicines?", language = "en")
        assertTrue(prompt.contains("CONTEXT: Customs rules."))
        assertTrue(prompt.contains("QUESTION: Can I bring medicines?"))
        assertTrue(prompt.contains("in English"))
    }

    @Test
    fun `photo prompt asks to read the photo, in Italian only for the Italian prompt`() {
        val it = PromptTemplates.photoPrompt("Cosa dice questo cartello?")
        assertTrue(it.startsWith("Cosa dice questo cartello?\n\n"))
        assertTrue(it.contains("in italiano, in massimo 3 frasi, guardando la foto"))
        val en = PromptTemplates.photoPrompt("What does this sign say?", language = "en")
        assertTrue(en.startsWith("What does this sign say?\n\n"))
        assertTrue(en.contains("Read what is written in the photo"))
    }

    // Il prompt italiano e' quello dei dati di addestramento dei modelli addestrati sulle guide: deve restare identico.
    @Test
    fun `italian prompt is exactly the training one`() {
        assertEquals(
            "Sei una guida turistica offline.\nRispondi in massimo 3 frasi, in italiano, usando solo le informazioni nel CONTESTO.\n" +
                "Se il contesto non basta, dillo esplicitamente.\n\nCONTESTO: c\n\nDOMANDA: q",
            PromptTemplates.onDevicePrompt(context = "c", question = "q"),
        )
    }

    // Il contesto vero ha piu' righe (sezioni, paragrafi, nota): il rientro del sorgente non deve finire nel prompt.
    @Test
    fun `multi-line context keeps the training prompt without indentation`() {
        val context = "prima riga\n\nseconda sezione\nNota personale: x"
        assertEquals(
            "Sei una guida turistica offline.\nRispondi in massimo 3 frasi, in italiano, usando solo le informazioni nel CONTESTO.\n" +
                "Se il contesto non basta, dillo esplicitamente.\n\nCONTESTO: $context\n\nDOMANDA: q",
            PromptTemplates.onDevicePrompt(context = context, question = "q"),
        )
        assertTrue(PromptTemplates.onDevicePrompt(context = context, question = "q", language = "en").startsWith("You are an offline travel guide.\n"))
        assertTrue(OnlinePromptTemplates.systemPrompt("riga 1\nriga 2").startsWith("Sei un assistente di viaggio.\n"))
    }
}
