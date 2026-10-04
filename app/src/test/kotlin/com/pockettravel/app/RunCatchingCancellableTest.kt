package com.pockettravel.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RunCatchingCancellableTest {

    @Test
    fun `restituisce il valore`() = runBlocking {
        assertEquals(3, runCatchingCancellable { 3 }.getOrNull())
    }

    @Test
    fun `un errore diventa un risultato fallito`() = runBlocking {
        val result = runCatchingCancellable<Int> { error("rete assente") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `la cancellazione si rilancia`() = runBlocking {
        try {
            runCatchingCancellable<Int> { throw CancellationException("annullata") }
            fail("la cancellazione doveva essere rilanciata")
        } catch (_: CancellationException) {
            // atteso
        }
    }
}
