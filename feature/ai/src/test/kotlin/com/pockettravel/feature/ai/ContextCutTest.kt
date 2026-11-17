package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextCutTest {

    @Test
    fun `cuts context after the last whole sentence, else at a word boundary`() {
        // Stessi dati e stessi risultati di test_eval_retrieval.py (test_taglio_all_ultima_frase_intera).
        val text = "Il museo apre alle 9. Chiude alle 18! I biglietti costano 10 euro"

        assertEquals("Il museo apre alle 9. Chiude alle 18!", truncateContext(text, maxChars = 50))
        assertEquals("Prima riga", truncateContext("Prima riga\nseconda riga lunga", maxChars = 20))
        assertEquals("A. parola parola parola parola parola", truncateContext("A. " + "parola ".repeat(10), maxChars = 40))
        assertEquals("a".repeat(20), truncateContext("a".repeat(30), maxChars = 20))
        assertEquals("Il museo apre alle 9. Chiude alle 18!", relevantParagraphs(text, emptySet(), budget = 50))
    }
}
