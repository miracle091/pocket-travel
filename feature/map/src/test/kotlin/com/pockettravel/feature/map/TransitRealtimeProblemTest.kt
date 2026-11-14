package com.pockettravel.feature.map

import com.pockettravel.core.data.TransitFeedInfo
import com.pockettravel.feature.map.testing.FakeConnectivityChecker
import com.pockettravel.feature.map.testing.MutableClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TransitRealtimeProblemTest {
    private val normal = RealtimeDeviceState(online = true, metered = false, dataSaverRestricts = false, powerSave = false)
    private val dns = UnknownHostException("rt.example")

    private fun classify(error: Throwable, device: RealtimeDeviceState = normal) = classifyRealtimeFailure(error, device)

    @Test
    fun `errore di connessione senza limiti attivi e' dell'ente`() {
        assertEquals(RealtimeProblem.FeedUnreachable, classify(dns))
        assertEquals(RealtimeProblem.FeedUnreachable, classify(SocketTimeoutException("timeout")))
        assertEquals(RealtimeProblem.FeedUnreachable, classify(ConnectException("refused")))
    }

    @Test
    fun `una risposta HTTP o un feed illeggibile sono sempre dell'ente, anche con i risparmi accesi`() {
        val everything = RealtimeDeviceState(online = true, metered = true, dataSaverRestricts = true, powerSave = true)
        assertEquals(RealtimeProblem.FeedUnreachable, classify(RealtimeHttpException(403), everything))
        assertEquals(RealtimeProblem.FeedUnreachable, classify(RealtimeHttpException(503), everything))
        assertEquals(RealtimeProblem.FeedUnreachable, classify(IOException("feed oltre il tetto"), everything))
        assertEquals(RealtimeProblem.FeedUnreachable, classify(IllegalStateException("protobuf"), everything))
    }

    @Test
    fun `errore di connessione senza rete e' offline`() {
        assertEquals(RealtimeProblem.Offline, classify(dns, normal.copy(online = false, powerSave = true)))
    }

    @Test
    fun `il risparmio dati conta solo su rete a consumo`() {
        assertEquals(RealtimeProblem.DataSaver, classify(dns, normal.copy(metered = true, dataSaverRestricts = true)))
        assertEquals(RealtimeProblem.FeedUnreachable, classify(dns, normal.copy(metered = false, dataSaverRestricts = true)))
        assertEquals(RealtimeProblem.FeedUnreachable, classify(dns, normal.copy(metered = true, dataSaverRestricts = false)))
    }

    @Test
    fun `il risparmio energetico conta solo se acceso, e dopo il risparmio dati`() {
        assertEquals(RealtimeProblem.BatterySaver, classify(dns, normal.copy(powerSave = true)))
        assertEquals(RealtimeProblem.DataSaver, classify(dns, normal.copy(metered = true, dataSaverRestricts = true, powerSave = true)))
        assertEquals(RealtimeProblem.FeedUnreachable, classify(dns, normal.copy(powerSave = false)))
    }

    // Il loader valuta lo stato del telefono quando il download fallisce, non quando si disegna il riquadro.
    private val network = TransitFeedInfo("renfe", "Renfe", "Renfe", realtimeUrl = "https://rt.example/renfe.pb", realtimeSizeBytes = 30_000)

    private fun loaderFailingWith(error: Exception, connectivity: FakeConnectivityChecker) = TransitRealtimeLoader(
        object : TransitRealtimeClient {
            override suspend fun fetch(url: String): RealtimeDownload = throw error
        },
        connectivity, MutableClock(),
    )

    @Test
    fun `il loader riporta la causa valutata al momento del fallimento`() = runTest {
        val connectivity = FakeConnectivityChecker(powerSave = true)
        val loader = loaderFailingWith(dns, connectivity)
        loader.refresh(listOf(network), tapped = true)
        connectivity.powerSave = false
        assertEquals(RealtimeProblem.BatterySaver, loader.ui(listOf(network), loading = false)?.problem)
    }

    @Test
    fun `il loader non incolpa il telefono per un errore HTTP e non segnala nulla se tutto va`() = runTest {
        val connectivity = FakeConnectivityChecker(metered = true, dataSaver = true, powerSave = true)
        val loader = loaderFailingWith(RealtimeHttpException(403), connectivity)
        loader.refresh(listOf(network), tapped = true)
        val ui = checkNotNull(loader.ui(listOf(network), loading = false))
        assertEquals(RealtimeProblem.FeedUnreachable, ui.problem)

        // Nessun download ancora: nessun problema.
        val fresh = loaderFailingWith(dns, FakeConnectivityChecker(powerSave = true))
        assertNull(fresh.ui(listOf(network), loading = false)?.problem)
    }
}
