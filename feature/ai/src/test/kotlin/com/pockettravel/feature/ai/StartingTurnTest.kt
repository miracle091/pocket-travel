package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartingTurnTest {

    private fun state(
        question: String = "",
        askedQuestion: String? = null,
        answer: AssistantAnswer? = null,
        emergencyCard: EmergencyCardState? = null,
        readOnlyRegion: String? = null,
    ) = AiUiState(
        mode = AiEngineMode.ON_DEVICE,
        isDeviceCapable = true,
        isModelDownloaded = true,
        isApiKeyConfigured = false,
        question = question,
        askedQuestion = askedQuestion,
        answer = answer,
        emergencyCard = emergencyCard,
        readOnlyRegion = readOnlyRegion,
    )

    @Test
    fun `una seconda domanda non parte mentre la prima e' in corso`() {
        val first = requireNotNull(state(question = "Ci sono allerte per un vulcano?").startingTurn("Ci sono allerte per un vulcano?"))
        assertTrue(first.isThinking)
        assertEquals("", first.question)

        assertNull(first.copy(question = "E le frane?").startingTurn("E le frane?"))
    }

    @Test
    fun `il turno concluso sale sopra la domanda nuova e il riquadro vecchio sparisce`() {
        val answer = AssistantAnswer("Si'", emptyList(), false)
        val state = state(
            askedQuestion = "Serve il visto?",
            answer = answer,
            emergencyCard = EmergencyCardState(entries = emptyList()),
        )

        val next = requireNotNull(state.startingTurn("Per quanto tempo?"))

        assertEquals(listOf(ShownTurn("Serve il visto?", answer)), next.earlierTurns)
        assertEquals("Per quanto tempo?", next.askedQuestion)
        assertNull(next.answer)
        assertNull(next.emergencyCard)
    }

    @Test
    fun `il turno concluso tiene il suo modello, la domanda nuova prende quello scelto`() {
        val answer = AssistantAnswer("Si'", emptyList(), false)
        val old = ModelCredit("Qwen3.5 2B", "https://huggingface.co/Qwen/Qwen3.5-2B")
        val state = state(askedQuestion = "Serve il visto?", answer = answer)
            .copy(answerCredit = old, selectedModelId = "pt-qwen3.5-0.8b")

        val next = requireNotNull(state.startingTurn("Per quanto tempo?"))

        assertEquals(old, next.earlierTurns.single().credit)
        assertEquals(
            ModelCredit("Pocket Travel 0.8B (Qwen3.5)", "https://huggingface.co/pockettravel/qwen3.5-0.8b-travel-it-GGUF"),
            next.answerCredit,
        )
    }

    @Test
    fun `un modello ufficiale porta alla pagina dell'autore, non al repository del file GGUF`() {
        assertEquals(ModelCredit("Qwen3.5 0.8B", "https://huggingface.co/Qwen/Qwen3.5-0.8B"), onDeviceModelCredit("qwen3.5-0.8b"))
        assertEquals(ModelCredit("Qwen3.5 2B", "https://huggingface.co/Qwen/Qwen3.5-2B"), onDeviceModelCredit("qwen3.5-2b"))
        assertEquals(
            ModelCredit("Qwen3 4B Instruct 2507", "https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507"),
            onDeviceModelCredit("qwen3-4b-instruct-2507"),
        )
    }

    @Test
    fun `online il nome e' quello del modello in uso e il link la pagina dei modelli del servizio`() {
        val online = state().copy(
            mode = AiEngineMode.ONLINE,
            onlineProvider = OnlineProvider.MISTRAL,
            onlineModels = mapOf(OnlineProvider.MISTRAL to "mistral-small-latest"),
        )

        assertEquals(ModelCredit("mistral-small-latest", OnlineProvider.MISTRAL.modelsPageUrl), online.currentModelCredit())
        assertNull(state().copy(selectedModelId = "rimosso").currentModelCredit())
    }

    @Test
    fun `un riquadro in ritardo non sostituisce quello di un'altra domanda`() {
        val late = EmergencyCardState(entries = emptyList())
        val current = state(askedQuestion = "Seconda domanda")

        assertEquals(late, current.withEmergencyCard("Seconda domanda", late).emergencyCard)
        assertNull(current.withEmergencyCard("Prima domanda", late).emergencyCard)
        assertNull(state(askedQuestion = null).withEmergencyCard("Prima domanda", late).emergencyCard)
    }

    @Test
    fun `domanda vuota o conversazione in sola lettura non partono`() {
        assertNull(state().startingTurn("  "))
        assertNull(state(readOnlyRegion = "Giappone").startingTurn("Serve il visto?"))
    }

    @Test
    fun `la foto resta sopra la sua domanda anche quando il turno sale`() {
        val answer = AssistantAnswer("E' il menu del giorno", emptyList(), false)
        val photo = AttachedPhoto(byteArrayOf(1, 2, 3), question = "Cosa dice il cartello?")
        val state = state(askedQuestion = "Cosa dice il cartello?", answer = answer).copy(askedPhoto = photo)

        val next = requireNotNull(state.startingTurn("Quanto costa?"))
        assertEquals(photo, next.earlierTurns.single().photo)
        // Il seguito non mostra di nuovo la foto, che resta quella della conversazione per l'invio.
        assertNull(next.lastQuestionPhoto)
        assertEquals(photo, next.askedPhoto)

        val third = requireNotNull(next.copy(answer = answer, isThinking = false).startingTurn("E il dolce?"))
        assertNull(third.earlierTurns.last().photo)
    }
}
