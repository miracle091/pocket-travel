package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

// Limite di lunghezza della domanda all'assistente: testo scritto, incollato o dettato.
class QuestionLimitTest {

    @Test
    fun `una domanda entro il limite resta com'e'`() {
        val question = "Ci sono allerte meteo in Puglia?"
        assertEquals(question, limitedQuestion(question))
        val atLimit = "a".repeat(MAX_QUESTION_CHARS)
        assertEquals(atLimit, limitedQuestion(atLimit))
    }

    @Test
    fun `un testo incollato piu' lungo si taglia al limite`() {
        assertEquals(MAX_QUESTION_CHARS, limitedQuestion("a".repeat(MAX_QUESTION_CHARS * 3)).length)
    }

    @Test
    fun `il taglio non spezza un'emoji a cavallo del limite`() {
        val text = "a".repeat(MAX_QUESTION_CHARS - 1) + "🌍" + "altro"
        assertEquals("a".repeat(MAX_QUESTION_CHARS - 1), limitedQuestion(text))
    }
}
