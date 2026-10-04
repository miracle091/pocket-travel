package com.pockettravel.feature.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StreamingTextTest {

    // Orologio finto: ogni lettura restituisce il tempo successivo della lista (l'ultimo si ripete).
    private fun clock(vararg times: Long): () -> Long {
        var i = 0
        return { times[minOf(i++, times.lastIndex)] }
    }

    @Test
    fun `senza intervallo ogni token produce il testo accumulato`() = runBlocking {
        val out = flowOf("Ci", "ao", "!").accumulated(0L, clock(1, 2, 3)).toList()
        assertEquals(listOf("Ci", "Ciao", "Ciao!"), out)
    }

    @Test
    fun `i token ravvicinati si fondono e l'ultimo testo e' sempre completo`() = runBlocking {
        // t=0 emette, 10 e 20 dentro l'intervallo di 80, 100 emette, 110 resta in sospeso e esce alla fine.
        val out = flowOf("a", "b", "c", "d", "e").accumulated(80L, clock(0, 10, 20, 100, 110)).toList()
        assertEquals(listOf("a", "abcd", "abcde"), out)
    }

    @Test
    fun `il primo token esce subito anche con l'orologio a zero`() = runBlocking {
        val out = flowOf("a", "b").accumulated(80L, clock(0, 0)).toList()
        assertEquals(listOf("a", "ab"), out)
    }

    @Test
    fun `nessun token nessuna emissione`() = runBlocking {
        assertTrue(emptyFlow<String>().accumulated(80L, clock(0)).toList().isEmpty())
    }

    @Test
    fun `l'errore del flusso a monte passa invariato`() = runBlocking {
        val upstream: Flow<String> = flow {
            emit("a")
            error("modello fermo")
        }
        val seen = mutableListOf<String>()
        var failure: Throwable? = null
        upstream.accumulated(0L, clock(0, 1)).catch { failure = it }.collect { seen += it }
        assertEquals(listOf("a"), seen)
        assertEquals("modello fermo", failure?.message)
    }

    @Test
    fun `la cancellazione non viene inghiottita`() = runBlocking {
        val upstream: Flow<String> = flow {
            emit("a")
            throw CancellationException("annullato")
        }
        try {
            upstream.accumulated(0L, clock(0, 1)).toList()
            fail("la CancellationException doveva uscire")
        } catch (e: CancellationException) {
            assertEquals("annullato", e.message)
        }
    }
}
