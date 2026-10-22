package com.pockettravel.feature.map

import com.pockettravel.core.data.RealtimeFeed
import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.data.TransitDeparture
import com.pockettravel.core.data.TransitFeedInfo
import com.pockettravel.core.data.TransitMode
import com.pockettravel.feature.map.testing.FakeConnectivityChecker
import com.pockettravel.feature.map.testing.FakeTransitRealtimeClient
import com.pockettravel.feature.map.testing.MutableClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val large = TransitFeedInfo("vbb", "VBB", "VBB", realtimeUrl = "https://rt.example/vbb.pb", realtimeSizeBytes = 2_250_000)
    private val feed = RealtimeFeed(Instant.parse("2026-10-22T07:59:40Z"), emptyMap())

    init {
        client.feeds["https://rt.example/renfe.pb"] = feed
        client.feeds["https://rt.example/vbb.pb"] = feed
        client.bytes["https://rt.example/vbb.pb"] = 2_250_000
    }

    private fun later(duration: Duration) {
        clock.now += duration
    }

    @Test
    fun `intervallo dell'aggiornamento automatico in base al peso del feed`() {
        assertEquals(Duration.ofMinutes(1), realtimeInterval(23_000, metered = false))
        assertEquals(Duration.ofSeconds(270), realtimeInterval(2_250_000, metered = false))
        assertEquals(Duration.ofSeconds(150), realtimeInterval(1_250_000, metered = false))
        // Su rete a consumo il doppio (Autolinee Toscane, 0,87 MB), mai oltre 7 minuti.
        assertEquals(Duration.ofSeconds(208), realtimeInterval(870_000, metered = true))
        assertEquals(Duration.ofMinutes(7), realtimeInterval(9_000_000, metered = false))
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
    fun `ogni rete si riscarica da sola secondo il suo peso, un tocco subito`() = runTest {
        loader.refresh(listOf(small, large), tapped = false)
        assertEquals(2, client.requests.size)
        later(Duration.ofSeconds(61))
        loader.refresh(listOf(small, large), tapped = false)
        // Dopo un minuto solo il feed piccolo; quello da 2,25 MB aspetta 4,5 minuti.
        assertEquals(listOf(small.realtimeUrl), client.requests.drop(2))
        later(Duration.ofSeconds(30))
        loader.refresh(listOf(small, large), tapped = true)
        assertEquals(listOf(small.realtimeUrl, small.realtimeUrl, large.realtimeUrl), client.requests.drop(2))
        later(Duration.ofSeconds(5))
        loader.refresh(listOf(small, large), tapped = true)
        assertEquals(5, client.requests.size)
    }

    @Test
    fun `conta il peso misurato dell'ultimo download, non la stima del catalogo`() = runTest {
        // Il catalogo dice 30 KB (misurato di notte), il download pesa 2,25 MB: si aspettano 4,5 minuti.
        client.bytes["https://rt.example/renfe.pb"] = 2_250_000
        loader.refresh(listOf(small), tapped = false)
        later(Duration.ofMinutes(4))
        loader.refresh(listOf(small), tapped = false)
        assertEquals(1, client.requests.size)
        later(Duration.ofSeconds(31))
        loader.refresh(listOf(small), tapped = false)
        assertEquals(2, client.requests.size)
        // Su rete a consumo ora aspetta un tocco, con il peso vero sul pulsante.
        connectivity.metered = true
        later(Duration.ofMinutes(11))
        loader.refresh(listOf(small), tapped = false)
        assertEquals(2, client.requests.size)
        assertEquals(2_250_000L, loader.ui(listOf(small), loading = false)?.tapSizeBytes)
    }

    @Test
    fun `i feed grandi su rete a consumo solo con un tocco`() = runTest {
        connectivity.metered = true
        loader.refresh(listOf(small, large), tapped = false)
        assertEquals(listOf(small.realtimeUrl), client.requests)
        val waiting = checkNotNull(loader.ui(listOf(small, large), loading = false))
        assertEquals(2_250_000L, waiting.tapSizeBytes)
        assertEquals(clock.now, waiting.updatedAt)
        later(Duration.ofSeconds(30))
        loader.refresh(listOf(small, large), tapped = true)
        assertEquals(setOf("renfe", "vbb"), loader.usable(listOf(small, large)).keys)
        assertNull(loader.ui(listOf(small, large), loading = false)?.tapSizeBytes)
    }

    @Test
    fun `dopo un quarto d'ora senza tocchi l'aggiornamento automatico si ferma`() = runTest {
        loader.refresh(listOf(small), tapped = false)
        later(Duration.ofMinutes(16))
        loader.refresh(listOf(small), tapped = false)
        assertEquals(1, client.requests.size)
        assertTrue(checkNotNull(loader.ui(listOf(small), loading = false)).paused)
        // Un tocco scarica e fa ripartire l'aggiornamento automatico.
        loader.refresh(listOf(small), tapped = true)
        assertEquals(2, client.requests.size)
        assertFalse(checkNotNull(loader.ui(listOf(small), loading = false)).paused)
        later(Duration.ofSeconds(61))
        loader.refresh(listOf(small), tapped = false)
        assertEquals(3, client.requests.size)
        // Anche riaprire un riquadro lo fa ripartire.
        later(Duration.ofMinutes(16))
        loader.touch()
        loader.refresh(listOf(small), tapped = false)
        assertEquals(4, client.requests.size)
    }

    @Test
    fun `un feed vecchio non vale piu', un ente che non risponde lascia l'ultimo valido`() = runTest {
        loader.refresh(listOf(small), tapped = false)
        client.feeds.clear()
        later(Duration.ofMinutes(1))
        loader.refresh(listOf(small), tapped = false)
        val ui = checkNotNull(loader.ui(listOf(small), loading = false))
        assertTrue(ui.failed)
        assertEquals(setOf("renfe"), loader.usable(listOf(small)).keys)
        later(Duration.ofMinutes(10))
        assertTrue(loader.usable(listOf(small)).isEmpty())
    }

    @Test
    fun `un download interrotto si riprova subito`() = runTest {
        val cancelling = object : TransitRealtimeClient {
            var calls = 0
            override suspend fun fetch(url: String): RealtimeDownload {
                calls++
                if (calls == 1) throw kotlinx.coroutines.CancellationException("riquadro chiuso")
                return RealtimeDownload(feed, 30_000)
            }
        }
        val loader = TransitRealtimeLoader(cancelling, connectivity, clock)
        runCatching { loader.refresh(listOf(small), tapped = false) }
        loader.refresh(listOf(small), tapped = false)
        assertEquals(2, cancelling.calls)
        assertEquals(setOf("renfe"), loader.usable(listOf(small)).keys)
    }

    private fun departure(network: String, inMinutes: Int) =
        TransitDeparture("1", TransitMode.BUS, null, null, null, 600, inMinutes, network = network)

    @Test
    fun `da sole scaricano solo le reti con una partenza nell'ora successiva`() {
        val plain = TransitFeedInfo("riga", "Riga", "Rigas satiksme")
        val board = TransitBoard.Departures(
            listOf(departure("renfe", 90).copy(later = listOf(departure("renfe", 140))), departure("vbb", 5), departure("riga", 1)),
            LocalDate.parse("2026-11-01"), 10, listOf(small, plain, large, small),
        )
        assertEquals(listOf(small, large), realtimeNetworks(board))
        assertEquals(listOf(large), realtimeNetworksDue(board))
        // Anche una partenza successiva nell'ora conta.
        val withLater = board.copy(items = listOf(departure("renfe", 90).copy(later = listOf(departure("renfe", 40)))))
        assertEquals(listOf(small), realtimeNetworksDue(withLater))
        assertEquals(emptyList<TransitFeedInfo>(), realtimeNetworks(TransitBoard.NoStops))
        assertEquals(emptyList<TransitFeedInfo>(), realtimeNetworksDue(null))
    }
}
