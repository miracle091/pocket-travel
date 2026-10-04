package com.pockettravel.core.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Il manifest riusato tra le richieste ravvicinate (ManifestClient.recentManifest): solo logica, orologio e rete finti. */
class RecentFetchTest {

    private var clock = 0L
    private val fetch = RecentFetch<Int>(now = { clock })
    private val loads = AtomicInteger()

    private suspend fun load(): Int = loads.incrementAndGet()

    @Test
    fun `una richiesta entro il limite riusa il valore senza ricaricarlo`() = runBlocking {
        assertEquals(1, fetch.get(maxAgeMillis = 1_000) { load() })
        clock = 999
        assertEquals(1, fetch.get(maxAgeMillis = 1_000) { load() })
        assertEquals(1, loads.get())
    }

    @Test
    fun `passato il limite il valore si ricarica`() = runBlocking {
        fetch.get(maxAgeMillis = 1_000) { load() }
        clock = 1_000
        assertEquals(2, fetch.get(maxAgeMillis = 1_000) { load() })
    }

    @Test
    fun `con limite zero si ricarica sempre e il valore nuovo serve alle richieste successive`() = runBlocking {
        fetch.get(maxAgeMillis = 1_000) { load() }
        assertEquals(2, fetch.get(maxAgeMillis = 0) { load() })
        assertEquals(2, fetch.get(maxAgeMillis = 1_000) { load() })
    }

    @Test
    fun `richieste insieme caricano una volta sola`() = runBlocking {
        val results = List(5) { async { fetch.get(maxAgeMillis = 1_000) { load() } } }.awaitAll()
        assertEquals(List(5) { 1 }, results)
        assertEquals(1, loads.get())
    }

    @Test
    fun `un errore non cambia il valore e la richiesta dopo riprova`() = runBlocking {
        fetch.get(maxAgeMillis = 1_000) { load() }
        clock = 5_000
        try {
            fetch.get(maxAgeMillis = 1_000) { throw IOException("offline") }
            fail("attesa IOException")
        } catch (expected: IOException) {
            // atteso
        }
        assertEquals(2, fetch.get(maxAgeMillis = 1_000) { load() })
    }
}
