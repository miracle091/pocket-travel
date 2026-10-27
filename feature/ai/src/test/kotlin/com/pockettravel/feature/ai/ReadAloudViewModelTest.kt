package com.pockettravel.feature.ai

import com.pockettravel.core.speech.ReadAloud
import com.pockettravel.core.speech.ReadAloudResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Voce finta: la lettura finisce quando il test completa [result]; annullarla la ferma. */
private class FakeReadAloud : ReadAloud {
    var result = CompletableDeferred<ReadAloudResult>()
    val spoken = mutableListOf<String>()
    var isSpeaking = false

    override suspend fun speak(text: String, language: String): ReadAloudResult {
        spoken += text
        isSpeaking = true
        try {
            return result.await()
        } finally {
            isSpeaking = false
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReadAloudViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `Ascolta legge la risposta, Ferma sulla stessa la ferma`() = runTest(dispatcher) {
        val voice = FakeReadAloud()
        val viewModel = ReadAloudViewModel(voice)

        viewModel.toggle("Il museo apre alle 9.")
        advanceUntilIdle()
        assertEquals("Il museo apre alle 9.", viewModel.uiState.value.speakingText)

        viewModel.toggle("Il museo apre alle 9.")
        advanceUntilIdle()
        assertFalse(voice.isSpeaking)
        assertNull(viewModel.uiState.value.speakingText)
    }

    @Test
    fun `un'altra risposta ferma quella in lettura e parte`() = runTest(dispatcher) {
        val voice = FakeReadAloud()
        val viewModel = ReadAloudViewModel(voice)

        viewModel.toggle("Prima.")
        advanceUntilIdle()
        viewModel.toggle("Seconda.")
        advanceUntilIdle()
        assertEquals(listOf("Prima.", "Seconda."), voice.spoken)
        assertEquals("Seconda.", viewModel.uiState.value.speakingText)
    }

    @Test
    fun `a lettura finita torna Ascolta, senza la voce della lingua compare il motivo sotto quella risposta`() = runTest(dispatcher) {
        val voice = FakeReadAloud()
        val viewModel = ReadAloudViewModel(voice)

        viewModel.toggle("Uno.")
        advanceUntilIdle()
        voice.result.complete(ReadAloudResult.FINISHED)
        advanceUntilIdle()
        assertEquals(ReadAloudUiState(), viewModel.uiState.value)

        voice.result = CompletableDeferred(ReadAloudResult.LANGUAGE_UNAVAILABLE)
        viewModel.toggle("Due.")
        advanceUntilIdle()
        assertEquals(
            ReadAloudUiState(failedText = "Due.", message = R.string.ai_read_aloud_language_unavailable),
            viewModel.uiState.value,
        )
    }
}
