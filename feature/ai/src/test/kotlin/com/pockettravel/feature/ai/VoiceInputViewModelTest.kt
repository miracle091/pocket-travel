package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Riconoscitore finto: gli eventi li manda il test; la frase finisce con Final o Error, come nel servizio vero. */
private class FakeVoiceInput(override val isAvailable: Boolean = true) : VoiceInput {
    val events = Channel<VoiceInputEvent>(Channel.UNLIMITED)
    var isListening = false
    var listenCount = 0

    override fun listen(language: String): Flow<VoiceInputEvent> = flow {
        listenCount++
        isListening = true
        try {
            for (event in events) {
                emit(event)
                if (event !is VoiceInputEvent.Partial) break
            }
        } finally {
            isListening = false
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `il testo riconosciuto va nel campo dopo quello gia' scritto, senza inviarlo`() = runTest(dispatcher) {
        val voiceInput = FakeVoiceInput()
        val viewModel = VoiceInputViewModel(voiceInput)

        viewModel.start("Lisbona: ")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isListening)

        voiceInput.events.send(VoiceInputEvent.Partial("portami"))
        advanceUntilIdle()
        assertEquals("Lisbona: portami", viewModel.text.first())

        voiceInput.events.send(VoiceInputEvent.Final("portami alla stazione"))
        advanceUntilIdle()
        assertEquals("Lisbona: portami alla stazione", viewModel.text.first())
        assertFalse(viewModel.uiState.value.isListening)
        assertFalse(voiceInput.isListening)
    }

    @Test
    fun `con il campo vuoto resta solo la frase detta`() = runTest(dispatcher) {
        val voiceInput = FakeVoiceInput()
        val viewModel = VoiceInputViewModel(voiceInput)

        viewModel.start("  ")
        voiceInput.events.send(VoiceInputEvent.Final("Take me to the train station"))
        advanceUntilIdle()

        assertEquals("Take me to the train station", viewModel.text.first())
    }

    @Test
    fun `smettere di ascoltare chiude il microfono`() = runTest(dispatcher) {
        val voiceInput = FakeVoiceInput()
        val viewModel = VoiceInputViewModel(voiceInput)
        viewModel.start("")
        advanceUntilIdle()
        assertTrue(voiceInput.isListening)

        viewModel.stop()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isListening)
        assertFalse(voiceInput.isListening)
    }

    @Test
    fun `un secondo tocco durante l'ascolto non apre un'altra sessione`() = runTest(dispatcher) {
        val voiceInput = FakeVoiceInput()
        val viewModel = VoiceInputViewModel(voiceInput)

        viewModel.start("")
        advanceUntilIdle()
        viewModel.start("")
        advanceUntilIdle()

        assertEquals(1, voiceInput.listenCount)
    }

    @Test
    fun `gli errori diventano un messaggio e l'ascolto finisce`() = runTest(dispatcher) {
        val expected = mapOf(
            VoiceInputError.NO_SPEECH to R.string.ai_voice_no_speech,
            VoiceInputError.LANGUAGE_DOWNLOADING to R.string.ai_voice_language_downloading,
            VoiceInputError.LANGUAGE_UNAVAILABLE to R.string.ai_voice_language_unavailable,
            VoiceInputError.OTHER to R.string.ai_voice_error,
        )
        for ((error, message) in expected) {
            val voiceInput = FakeVoiceInput()
            val viewModel = VoiceInputViewModel(voiceInput)
            viewModel.start("")
            voiceInput.events.send(VoiceInputEvent.Error(error))
            advanceUntilIdle()

            assertEquals(message, viewModel.uiState.value.message)
            assertFalse(viewModel.uiState.value.isListening)
        }
    }

    @Test
    fun `permesso negato e messaggio tolto quando si scrive`() = runTest(dispatcher) {
        val viewModel = VoiceInputViewModel(FakeVoiceInput())

        viewModel.onPermissionDenied()
        assertEquals(R.string.ai_voice_permission, viewModel.uiState.value.message)

        viewModel.clearMessage()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `senza riconoscitore sul dispositivo il microfono non c'e' e non ascolta`() = runTest(dispatcher) {
        val voiceInput = FakeVoiceInput(isAvailable = false)
        val viewModel = VoiceInputViewModel(voiceInput)

        viewModel.start("")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isAvailable)
        assertFalse(viewModel.uiState.value.isListening)
        assertEquals(0, voiceInput.listenCount)
    }
}
