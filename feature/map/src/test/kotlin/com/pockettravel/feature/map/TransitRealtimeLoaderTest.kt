package com.pockettravel.feature.map

import com.pockettravel.core.data.RealtimeFeed
import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.data.TransitFeedInfo
import com.pockettravel.feature.map.testing.FakeConnectivityChecker
import com.pockettravel.feature.map.testing.FakeTransitRealtimeClient
import com.pockettravel.feature.map.testing.MutableClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class TransitRealtimeLoaderTest {
    private val client = FakeTransitRealtimeClient()
    private val connectivity = FakeConnectivityChecker()
    private val clock = MutableClock()
    private val loader = TransitRealtimeLoader(client, connectivity, clock)

    private val small = TransitFeedInfo("renfe", "Renfe", "Renfe", realtimeUrl = "https://rt.example/renfe.pb", realtimeSizeBytes = 30_000)
    private val large = TransitFeedInfo("vbb", "VBB", "VBB", realtimeUrl = "https://rt.example/vbb.pb", realtimeSizeBytes = 2_200_000)
    private val feed = RealtimeFeed(Instant.parse("2026-10-22T07:59:40Z"), emptyMap())

    init {
        client.feeds["https://rt.example/renfe.pb"] = feed
        client.feeds["https://rt.example/vbb.pb"] = feed
    }

    @Test
    fun `senza rete niente download e niente tempo reale`() = runTest {
        loader.refresh(listOf(small), tapped = false)
        connectivity.online = false
        loader.refresh(listOf(small), tapped = true)
        assertEquals(1, client.requests.size)
        // Feed scaricato prima, ma ora offline: il tabellone resta l'orario.
        assertEquals(emptyMap<String, RealtimeFeed>(), loader.usable(listOf(small)))
        assertNull(loader.ui(listOf(small), loading = false))
    }

    @Test
    fun `i feed piccoli si scaricano da soli, i grandi su rete a consumo solo con un tocco`() = runTest {
        connectivity.metered = true
        loader.refresh(listOf(small, large), tapped = false)
        assertEquals(listOf(small.realtimeUrl), client.requests)
        val waiting = checkNotNull(loader.ui(listOf(small, large), loading = false))
        assertEquals(2_200_000L, waiting.tapSizeBytes)
        // Senza il feed grande l'ora dell'aggiornamento e' quella del piccolo.
        assertEquals(clock.now, waiting.updatedAt)

        clock.now += Duration.ofSeconds(30)
        loader.refresh(listOf(small, large), tapped = true)
        assertEquals(listOf(small.realtimeUrl, small.realtimeUrl, large.realtimeUrl), client.requests)
        assertNull(loader.ui(listOf(small, large), loading = false)?.tapSizeBytes)
        assertEquals(setOf("renfe", "vbb"), loader.usable(listOf(small, large)).keys)

        // In Wi-Fi anche il grande si scarica da solo.
        connectivity.metered = false
        clock.now += Duration.ofSeconds(30)
        loader.refresh(listOf(large), tapped = false)
        assertEquals(4, client.requests.size)
    }

    @Test
    fun `tocchi ravvicinati non riscaricano, un feed vecchio non vale piu'`() = runTest {
        loader.refresh(listOf(small), tapped = false)
        clock.now += Duration.ofSeconds(5)
        loader.refresh(listOf(small), tapped = true)
        assertEquals(1, client.requests.size)

        clock.now += Duration.ofMinutes(11)
        assertTrue(loader.usable(listOf(small)).isEmpty())
        assertNull(checkNotNull(loader.ui(listOf(small), loading = false)).updatedAt)
    }

    @Test
    fun `un ente che non risponde segna l'errore e lascia l'ultimo feed valido`() = runTest {
        loader.refresh(listOf(small), tapped = false)
        client.feeds.clear()
        clock.now += Duration.ofMinutes(1)
        loader.refresh(listOf(small), tapped = false)
        val ui = checkNotNull(loader.ui(listOf(small), loading = false))
        assertTrue(ui.failed)
        assertEquals(setOf("renfe"), loader.usable(listOf(small)).keys)
    }

    @Test
    fun `un download interrotto si riprova subito`() = runTest {
        val cancelling = object : TransitRealtimeClient {
            var calls = 0
            override suspend fun fetch(url: String): RealtimeFeed {
                calls++
                if (calls == 1) throw kotlinx.coroutines.CancellationException("riquadro chiuso")
                return feed
            }
        }
        val loader = TransitRealtimeLoader(cancelling, connectivity, clock)
        runCatching { loader.refresh(listOf(small), tapped = false) }
        loader.refresh(listOf(small), tapped = false)
        assertEquals(2, cancelling.calls)
        assertEquals(setOf("renfe"), loader.usable(listOf(small)).keys)
    }

    @Test
    fun `solo le reti del tabellone con l'indirizzo del tempo reale, una volta sola`() {
        val plain = TransitFeedInfo("riga", "Riga", "Rigas satiksme")
        val board = TransitBoard.Departures(emptyList(), LocalDate.parse("2026-11-01"), 10, listOf(small, plain, small))
        assertEquals(listOf(small), realtimeNetworks(board))
        assertEquals(emptyList<TransitFeedInfo>(), realtimeNetworks(TransitBoard.NoStops))
        assertEquals(emptyList<TransitFeedInfo>(), realtimeNetworks(null))
    }
}
