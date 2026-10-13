package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.llamacpp.ContextUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Stato del motore visto da fuori (LlmRuntimeStats) mentre una generazione raccoglie i frammenti.
class CollectTimedTest {

    private fun usage(generated: Int = 3) =
        ContextUsage(promptTokens = 100, generatedTokens = generated, usedTokens = 100 + generated, contextSize = 4096)

    private fun stats() = MutableStateFlow(LlmRuntimeStats(loadedModelId = "qwen-0.8b", loadTimeMs = 1200L))

    @Test
    fun `i frammenti arrivano in ordine e il testo e' la loro somma`() = runBlocking<Unit> {
        val seen = mutableListOf<String>()

        val text = stats().collectTimed(flowOf("Ci", "ao", "!"), { usage() }) { seen.add(it) }

        assertEquals("Ciao!", text)
        assertEquals(listOf("Ci", "ao", "!"), seen)
    }

    @Test
    fun `durante la generazione lo stato dice che sta generando, alla fine no`() = runBlocking<Unit> {
        val state = stats()
        val generatingWhilePieces = mutableListOf<Boolean>()

        state.collectTimed(flowOf("a", "b"), { usage() }) { generatingWhilePieces.add(state.value.isGenerating) }

        assertEquals(listOf(true, true), generatingWhilePieces)
        assertFalse(state.value.isGenerating)
    }

    @Test
    fun `a fine turno registra modello, contesto e tempi dell'ultima generazione`() = runBlocking<Unit> {
        val state = stats()

        state.collectTimed(flowOf("a", "b", "c"), { usage(generated = 3) }) {}

        val last = state.value.lastGeneration
        assertNotNull(last)
        assertEquals("qwen-0.8b", last?.modelId)
        assertEquals(usage(generated = 3), last?.context)
        assertNotNull(last?.timeToFirstTokenMs)
        assertTrue((last?.totalMs ?: -1L) >= (last?.timeToFirstTokenMs ?: Long.MAX_VALUE))
        assertTrue((last?.endedAtMs ?: 0L) > 0L)
        assertEquals(listOf(last), state.value.history)
        // Il modello caricato e il suo tempo di caricamento restano quelli di prima.
        assertEquals("qwen-0.8b", state.value.loadedModelId)
        assertEquals(1200L, state.value.loadTimeMs)
    }

    @Test
    fun `senza frammenti manca il tempo al primo token`() = runBlocking<Unit> {
        val state = stats()

        val text = state.collectTimed(emptyFlow(), { null }) {}

        assertEquals("", text)
        assertNull(state.value.lastGeneration?.timeToFirstTokenMs)
        assertNull(state.value.lastGeneration?.context)
    }

    @Test
    fun `un errore del flusso si propaga ma la generazione e' registrata`() = runBlocking<Unit> {
        val state = stats()

        try {
            state.collectTimed(flow { emit("a"); error("motore fallito") }, { usage() }) {}
            fail("era atteso l'errore del flusso")
        } catch (error: IllegalStateException) {
            assertEquals("motore fallito", error.message)
        }

        assertFalse(state.value.isGenerating)
        assertEquals(1, state.value.history.size)
        assertNotNull(state.value.lastGeneration?.timeToFirstTokenMs)
    }

    @Test
    fun `una generazione annullata lascia lo stato a riposo e la registra`() = runBlocking<Unit> {
        val state = stats()
        val firstPiece = CompletableDeferred<Unit>()

        val job = launch {
            state.collectTimed(flow { emit("a"); awaitCancellation() }, { usage(generated = 1) }) { firstPiece.complete(Unit) }
        }
        firstPiece.await()
        assertTrue(state.value.isGenerating)
        job.cancelAndJoin()

        assertFalse(state.value.isGenerating)
        assertEquals(1, state.value.history.size)
        assertEquals(1, state.value.lastGeneration?.context?.generatedTokens)
    }

    @Test
    fun `lo storico tiene le ultime cento generazioni`() = runBlocking<Unit> {
        val state = stats()

        (1..105).forEach { n -> state.collectTimed(emptyFlow(), { usage(generated = n) }) {} }

        assertEquals(100, state.value.history.size)
        assertEquals(6, state.value.history.first().context?.generatedTokens)
        assertEquals(105, state.value.history.last().context?.generatedTokens)
        assertEquals(state.value.history.last(), state.value.lastGeneration)
    }

    @Test
    fun `le velocita' di una generazione vengono dai conteggi del contesto`() {
        val generation = GenerationStats(modelId = "m", context = usage(generated = 11), timeToFirstTokenMs = 1000L, totalMs = 3000L)

        assertEquals(5f, generation.tokensPerSecond ?: -1f, 0.01f)
        assertEquals(100f, generation.promptTokensPerSecond ?: -1f, 0.01f)
    }

    @Test
    fun `senza conteggi del contesto le velocita' sono sconosciute`() {
        val generation = GenerationStats(modelId = "m", context = null, timeToFirstTokenMs = 1000L, totalMs = 3000L)

        assertNull(generation.tokensPerSecond)
        assertNull(generation.promptTokensPerSecond)
    }

    @Test
    fun `il nome della variante CPU viene dal file della libreria`() {
        assertEquals("android_armv8.6_1", cpuVariantName("libggml-cpu-android_armv8.6_1.so"))
        assertEquals("x64", cpuVariantName("libggml-cpu-x64.so"))
        assertEquals("cpu", cpuVariantName("libggml-cpu.so"))
    }
}
