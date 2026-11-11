package com.pockettravel.feature.ai

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    fun `la domanda non inviata si ritrova nello stato salvato`() {
        assertEquals("Dove mangio a Lecce?", SavedStateHandle(mapOf("question" to "Dove mangio a Lecce?")).savedQuestion())
        assertEquals("", SavedStateHandle().savedQuestion())
    }

    @Test
    fun `ogni cambio della domanda finisce nello stato salvato`() = runTest {
        val handle = SavedStateHandle()
        val state = MutableStateFlow(
            AiUiState(mode = AiEngineMode.ONLINE, isDeviceCapable = true, isModelDownloaded = false, isApiKeyConfigured = true),
        )
        handle.keepQuestion(backgroundScope, state)
        state.update { it.copy(question = "Orari del Colosseo?") }
        runCurrent()
        assertEquals("Orari del Colosseo?", handle.savedQuestion())
        state.update { it.copy(question = "") }
        runCurrent()
        assertEquals("", handle.savedQuestion())
    }

    @Test
    fun `una domanda salvata troppo lunga si taglia al limite`() {
        assertEquals(MAX_QUESTION_CHARS, SavedStateHandle(mapOf("question" to "a".repeat(MAX_QUESTION_CHARS * 2))).savedQuestion().length)
    }

    @Test
    fun `il taglio non spezza un'emoji a cavallo del limite`() {
        val text = "a".repeat(MAX_QUESTION_CHARS - 1) + "🌍" + "altro"
        assertEquals("a".repeat(MAX_QUESTION_CHARS - 1), limitedQuestion(text))
    }
}
