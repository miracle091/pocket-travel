package com.pockettravel.feature.map

import com.pockettravel.core.data.RealtimeFeed
import com.pockettravel.core.data.RealtimeTrip
import com.pockettravel.core.data.TransitAlert
import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.data.TransitDeparture
import com.pockettravel.core.data.TransitFeedInfo
import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.data.TransitText
import com.pockettravel.core.data.TransitTranslation
import com.pockettravel.feature.map.testing.FakeConnectivityChecker
import com.pockettravel.feature.map.testing.FakeTransitRealtimeClient
import com.pockettravel.feature.map.testing.MutableClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TransitRealtimeLoaderTest {
    private val client = FakeTransitRealtimeClient()
    private val connectivity = FakeConnectivityChecker()
    private val clock = MutableClock()
    private val loader = TransitRealtimeLoader(client, connectivity, clock)

    private val small = TransitFeedInfo("renfe", "Renfe", "Renfe", realtimeUrl = "https://rt.example/renfe.pb", realtimeSizeBytes = 30_000)
    private val smallUrl = "https://rt.example/renfe.pb"
    private val large = TransitFeedInfo("vbb", "VBB", "VBB", realtimeUrl = "https://rt.example/vbb.pb", realtimeSizeBytes = 2_250_000)
    private val feed = RealtimeFeed(null, emptyMap())

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

    @Test
    fun `un download annullato che OkHttp segnala come IOException non e' un fallimento`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val okHttpLike = object : TransitRealtimeClient {
            override suspend fun fetch(url: String): RealtimeDownload {
                calls++
                try {
                    gate.await()
                } catch (e: CancellationException) {
                    // Come OkHttp: la chiamata annullata arriva come IOException.
                    throw IOException("Canceled", e)
                }
                return RealtimeDownload(feed, 30_000)
            }
        }
        val loader = TransitRealtimeLoader(okHttpLike, connectivity, clock)
        val job = launch { loader.refresh(listOf(small), tapped = false) }
        runCurrent()

        job.cancel()
        runCurrent()

        assertFalse(loader.ui(listOf(small), loading = false)?.failed ?: false)
        gate.complete(Unit)
        loader.refresh(listOf(small), tapped = false)
        assertEquals(2, calls)
    }

    @Test
    fun `un feed con l'header vecchio non vale, e l'ora mostrata e' quella dell'header`() = runTest {
        client.feeds[smallUrl] = RealtimeFeed(clock.now - Duration.ofMinutes(15), emptyMap())
        loader.refresh(listOf(small), tapped = false)
        assertTrue(loader.usable(listOf(small)).isEmpty())
        assertNull(checkNotNull(loader.ui(listOf(small), loading = false)).updatedAt)
        // Header di 3 minuti fa: vale, e "aggiornato alle" e' l'ora dei dati.
        later(Duration.ofSeconds(30))
        val dataTime = clock.now - Duration.ofMinutes(3)
        client.feeds[smallUrl] = RealtimeFeed(dataTime, emptyMap())
        loader.refresh(listOf(small), tapped = true)
        assertEquals(dataTime, checkNotNull(loader.ui(listOf(small), loading = false)).updatedAt)
        // Un header nel futuro (orologio dell'ente avanti) non conta: vale l'ora del download.
        client.feeds[smallUrl] = RealtimeFeed(clock.now + Duration.ofMinutes(5), emptyMap())
        later(Duration.ofSeconds(30))
        loader.refresh(listOf(small), tapped = true)
        assertEquals(clock.now, checkNotNull(loader.ui(listOf(small), loading = false)).updatedAt)
    }

    @Test
    fun `senza il peso nel catalogo su rete a consumo il feed aspetta un tocco, poi vale quello misurato`() = runTest {
        val unknown = small.copy(realtimeSizeBytes = null)
        connectivity.metered = true
        loader.refresh(listOf(unknown), tapped = false)
        assertTrue(client.requests.isEmpty())
        assertEquals(0L, checkNotNull(loader.ui(listOf(unknown), loading = false)).tapSizeBytes)
        loader.refresh(listOf(unknown), tapped = true)
        assertEquals(1, client.requests.size)
        // Misurati 30 KB: da sola si riscarica (dopo 2 minuti su rete a consumo).
        later(Duration.ofMinutes(3))
        loader.refresh(listOf(unknown), tapped = false)
        assertEquals(2, client.requests.size)
        // Su rete senza limiti il peso ignoto non ferma il primo download.
        val other = TransitRealtimeLoader(client, FakeConnectivityChecker(metered = false), clock)
        other.refresh(listOf(unknown), tapped = false)
        assertEquals(3, client.requests.size)
    }

    @Test
    fun `onDownload parte solo se c'e' qualcosa da scaricare`() = runTest {
        var started = 0
        loader.refresh(listOf(small), tapped = false) { started++ }
        loader.refresh(listOf(small), tapped = false) { started++ }
        assertEquals(1, started)
        later(Duration.ofSeconds(30))
        loader.refresh(listOf(small), tapped = true) { started++ }
        assertEquals(2, started)
        loader.refresh(listOf(small), tapped = true) { started++ }
        assertEquals(2, started)
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

    // Avvisi: un avviso solo con l'intestazione basta a riconoscerlo nei test.
    private fun alert(title: String) = TransitAlert(
        emptyList(), emptyList(), null, null, null, null, TransitText(listOf(TransitTranslation(title, "en"))), null,
    )

    private val alertsOnly = TransitFeedInfo("adl", "Adelaide", "Adelaide", alertsUrl = "https://rt.example/adl-alerts.pb", alertsSizeBytes = 5_000)
    private val shared = TransitFeedInfo(
        "tls", "Tolosa", "Tisseo", realtimeUrl = "https://rt.example/tls.pb", realtimeSizeBytes = 30_000,
        alertsUrl = "https://rt.example/tls.pb", alertsSizeBytes = 30_000,
    )
    private val separate = TransitFeedInfo(
        "mbta", "MBTA", "MBTA", realtimeUrl = "https://rt.example/mbta.pb", realtimeSizeBytes = 30_000,
        alertsUrl = "https://rt.example/mbta-alerts.pb", alertsSizeBytes = 5_000,
    )

    @Test
    fun `una rete con soli avvisi li scarica e li consegna`() = runTest {
        client.feeds[alertsOnly.alertsUrl!!] = RealtimeFeed(null, emptyMap(), alerts = listOf(alert("Stop chiusa")))
        loader.refresh(listOf(alertsOnly), tapped = false)
        assertEquals(listOf(alertsOnly.alertsUrl), client.requests)
        assertEquals(listOf(alert("Stop chiusa")), loader.usable(listOf(alertsOnly)).getValue("adl").alerts)
        assertTrue(loader.usable(listOf(alertsOnly)).getValue("adl").trips.isEmpty())
        assertEquals(clock.now, checkNotNull(loader.ui(listOf(alertsOnly), loading = false)).updatedAt)
    }

    @Test
    fun `con lo stesso indirizzo corse e avvisi vengono da un solo download`() = runTest {
        client.feeds[shared.realtimeUrl!!] = RealtimeFeed(null, emptyMap(), alerts = listOf(alert("Sciopero")))
        loader.refresh(listOf(shared), tapped = false)
        assertEquals(listOf(shared.realtimeUrl), client.requests)
        assertEquals(listOf(alert("Sciopero")), loader.usable(listOf(shared)).getValue("tls").alerts)
    }

    @Test
    fun `con indirizzi diversi si scaricano due feed e si uniscono`() = runTest {
        client.feeds[separate.realtimeUrl!!] = RealtimeFeed(null, emptyMap(), alerts = listOf(alert("Dal feed delle corse")))
        client.feeds[separate.alertsUrl!!] = RealtimeFeed(null, emptyMap(), alerts = listOf(alert("Dal feed degli avvisi")))
        loader.refresh(listOf(separate), tapped = false)
        assertEquals(setOf(separate.realtimeUrl, separate.alertsUrl), client.requests.toSet())
        assertEquals(2, client.requests.size)
        assertEquals(listOf(alert("Dal feed degli avvisi")), loader.usable(listOf(separate)).getValue("mbta").alerts)
    }

    @Test
    fun `un feed degli avvisi scaduto non porta avvisi, uno delle corse scaduto non porta corse`() = runTest {
        val trip = RealtimeTrip("t1", null, false, 120, emptyList())
        client.feeds[separate.realtimeUrl!!] = RealtimeFeed(null, mapOf("t1" to listOf(trip)))
        client.feeds[separate.alertsUrl!!] = RealtimeFeed(null, emptyMap(), alerts = listOf(alert("Avviso")))
        loader.refresh(listOf(separate), tapped = false)
        // Solo gli avvisi si riscaricano: dopo 11 minuti le corse sono scadute, gli avvisi nuovi no.
        later(Duration.ofMinutes(11))
        client.feeds.remove(separate.realtimeUrl!!)
        loader.touch()
        loader.refresh(listOf(separate), tapped = false)
        val merged = loader.usable(listOf(separate)).getValue("mbta")
        assertTrue(merged.trips.isEmpty())
        assertEquals(listOf(alert("Avviso")), merged.alerts)
        // Al contrario: gli avvisi non si riscaricano e scadono, le corse arrivano.
        client.feeds[separate.realtimeUrl!!] = RealtimeFeed(null, mapOf("t1" to listOf(trip)))
        client.feeds.remove(separate.alertsUrl!!)
        later(Duration.ofMinutes(11))
        loader.touch()
        loader.refresh(listOf(separate), tapped = false)
        val tripsOnly = loader.usable(listOf(separate)).getValue("mbta")
        assertEquals(setOf("t1"), tripsOnly.trips.keys)
        assertTrue(tripsOnly.alerts.isEmpty())
    }

    @Test
    fun `su rete a consumo un feed di avvisi grande aspetta un tocco, uno piccolo no`() = runTest {
        val bigAlerts = separate.copy(alertsSizeBytes = 2_000_000)
        client.feeds[bigAlerts.alertsUrl!!] = RealtimeFeed(null, emptyMap(), alerts = listOf(alert("Grande")))
        connectivity.metered = true
        loader.refresh(listOf(bigAlerts), tapped = false)
        assertEquals(listOf(bigAlerts.realtimeUrl), client.requests)
        assertEquals(2_000_000L, loader.ui(listOf(bigAlerts), loading = false)?.tapSizeBytes)
        later(Duration.ofSeconds(30))
        loader.refresh(listOf(bigAlerts), tapped = true)
        assertEquals(listOf(alert("Grande")), loader.usable(listOf(bigAlerts)).getValue("mbta").alerts)
        assertNull(loader.ui(listOf(bigAlerts), loading = false)?.tapSizeBytes)
    }

    @Test
    fun `gli avvisi si riscaricano da soli al piu' ogni cinque minuti, anche senza partenze vicine`() = runTest {
        client.feeds[alertsOnly.alertsUrl!!] = RealtimeFeed(null, emptyMap())
        loader.refresh(emptyList(), tapped = false, alertNetworks = listOf(alertsOnly))
        later(Duration.ofMinutes(2))
        loader.refresh(emptyList(), tapped = false, alertNetworks = listOf(alertsOnly))
        assertEquals(1, client.requests.size)
        later(Duration.ofMinutes(3))
        loader.refresh(emptyList(), tapped = false, alertNetworks = listOf(alertsOnly))
        assertEquals(2, client.requests.size)
        // Le corse di una rete lontana nel tempo non si scaricano: solo gli avvisi, e con lo stesso indirizzo il suo feed.
        client.feeds[shared.realtimeUrl!!] = RealtimeFeed(null, emptyMap())
        loader.refresh(emptyList(), tapped = false, alertNetworks = listOf(shared))
        assertEquals(listOf(shared.realtimeUrl), client.requests.drop(2))
        later(Duration.ofMinutes(2))
        loader.refresh(emptyList(), tapped = false, alertNetworks = listOf(shared))
        assertEquals(3, client.requests.size)
    }

    @Test
    fun `realtimeNetworks comprende le reti con i soli avvisi, realtimeNetworksDue no`() {
        val board = TransitBoard.Departures(
            listOf(departure("adl", 5), departure("renfe", 5)), LocalDate.parse("2026-11-01"), 10, listOf(alertsOnly, small),
        )
        assertEquals(listOf(alertsOnly, small), realtimeNetworks(board))
        assertEquals(listOf(small), realtimeNetworksDue(board))
    }
}
