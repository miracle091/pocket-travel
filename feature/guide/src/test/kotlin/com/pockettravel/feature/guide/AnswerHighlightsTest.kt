package com.pockettravel.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerHighlightsTest {

    private val body = "Il Duomo è aperto tutti i giorni. L'ingresso è gratuito per i bambini sotto i 6 anni. " +
        "Il biglietto «Duomo Pass» costa 15 euro – sconti per gli studenti."

    private fun List<IntRange>.texts(source: String) = map { source.substring(it.first, it.last + 1) }

    @Test
    fun `la risposta uguale al testo evidenzia tutto il passaggio`() {
        val answer = "L'ingresso è gratuito per i bambini sotto i 6 anni."

        // L'intervallo finisce sull'ultima lettera: il punto finale resta fuori.
        assertEquals(listOf(answer.removeSuffix(".")), answerHighlights(body, answer).texts(body))
    }

    @Test
    fun `maiuscole, accenti, apici, virgolette e trattini diversi non impediscono la corrispondenza`() {
        val answer = "il biglietto \"Duomo Pass\" costa 15 euro - sconti per gli studenti"

        assertEquals(
            listOf("Il biglietto «Duomo Pass» costa 15 euro – sconti per gli studenti"),
            answerHighlights(body, answer).texts(body),
        )
        assertEquals(1, answerHighlights(body, "L’INGRESSO E GRATUITO per i bambini sotto i 6 anni").size)
    }

    @Test
    fun `una risposta riscritta evidenzia le frasi che riprende, dalla piu' lunga`() {
        val answer = "Buona visita! Il Duomo è aperto tutti i giorni. Per i bambini sotto i 6 anni l'ingresso è libero. " +
            "L'ingresso è gratuito per i bambini sotto i 6 anni."

        val found = answerHighlights(body, answer)

        assertEquals(
            listOf("Il Duomo è aperto tutti i giorni", "L'ingresso è gratuito per i bambini sotto i 6 anni"),
            found.texts(body),
        )
    }

    @Test
    fun `frasi corte o assenti dal testo non evidenziano niente`() {
        assertTrue(answerHighlights(body, "Il museo chiude alle cinque. Sì.").isEmpty())
        assertTrue(answerHighlights(body, "").isEmpty())
        assertTrue(answerHighlights("", "Il Duomo è aperto tutti i giorni.").isEmpty())
    }
}
