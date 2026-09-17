package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmBenchmarkTest {

    private val prompts = listOf(
        BenchmarkPrompt(prompt = "domanda 1", expectedKeywords = listOf("passaporto"), minAnswerLength = 10),
        BenchmarkPrompt(prompt = "domanda 2", expectedKeywords = listOf("fuso", "ora"), minAnswerLength = 10),
    )

    @Test
    fun `punteggio massimo quando tutte le parole chiave e le lunghezze sono soddisfatte`() {
        val answers = listOf(
            "Serve sempre un passaporto valido per la frontiera",
            "Il fuso orario cambia l'ora locale di destinazione",
        )

        val result = scoreBenchmarkAnswers("model-a", prompts, answers, elapsedMs = 1000L)

        assertEquals(100, result.qualityScore)
        assertEquals("model-a", result.modelId)
    }

    @Test
    fun `punteggio zero quando nessuna parola chiave e' presente e le risposte sono troppo corte`() {
        val answers = listOf("no", "no")

        val result = scoreBenchmarkAnswers("model-b", prompts, answers, elapsedMs = 1000L)

        assertEquals(0, result.qualityScore)
    }

    @Test
    fun `wordsPerSecond conta le parole totali diviso i secondi trascorsi`() {
        val answers = listOf("una due tre quattro", "cinque sei")

        val result = scoreBenchmarkAnswers("model-c", prompts, answers, elapsedMs = 2000L)

        assertEquals(3f, result.wordsPerSecond, 0.01f)
    }

    @Test
    fun `un elapsedMs a zero non causa una divisione per zero`() {
        val answers = listOf("risposta", "risposta")

        val result = scoreBenchmarkAnswers("model-d", prompts, answers, elapsedMs = 0L)

        assertEquals(1L, result.totalLatencyMs)
    }

    @Test
    fun `richiede lo stesso numero di prompt e risposte`() {
        try {
            scoreBenchmarkAnswers("model-e", prompts, listOf("solo una risposta"), elapsedMs = 1000L)
            org.junit.Assert.fail("deve lanciare per liste di dimensione diversa")
        } catch (_: IllegalArgumentException) {
            // atteso
        }
    }
}
