package com.pockettravel.core.data.alerts

import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.testing.FakeRegionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// OnlineAlertsRepository con un sito finto che risponde con le risposte vere salvate, e una mappa finta dell'Italia.
class OnlineAlertsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var now = Instant.parse("2026-10-24T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    // Le fonti si leggono in parallelo: raccolte sicure per piu' thread.
    private val requested = Collections.synchronizedList(mutableListOf<String>())
    private val userAgents = Collections.synchronizedMap(mutableMapOf<String, String?>())
    private var online = true
    private var failing: String? = null
    private var hanging: String? = null
    private var slowMillis = 0L

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("alerts/$name")).readText()

    private val client = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            val url = chain.request().url.toString()
            requested += url
            userAgents[url] = chain.request().header("User-Agent")
            if (slowMillis > 0) Thread.sleep(slowMillis)
            if (!online) throw IOException("niente rete")
            if (failing?.let { it in url } == true) throw IOException("timeout di $failing")
            if (hanging?.let { it in url } == true) {
                // Fonte che non risponde: si ferma solo quando la chiamata viene annullata.
                val deadline = System.nanoTime() + 30_000_000_000L
                while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(10)
                throw IOException("annullata")
            }
            val body = when {
                "gdacs" in url -> fixture("gdacs_events.json")
                "usgs" in url -> fixture("usgs_events.json")
                "/api/v1/warnings/" in url -> fixture("meteoalarm_cap.xml")
                "meteoalarm" in url -> fixture("meteoalarm_events.xml")
                "tsunami.gov" in url -> fixture("tsunami_empty.xml")
                "api.weather.gov" in url -> fixture("nws_events.json")
                else -> error("indirizzo inatteso: $url")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body.toResponseBody()).build()
        },
    ).build()

    private fun repository(): OnlineAlertsRepository {
        val regions = File(tempFolder.root, "regions")
        writeMapHeader(File(regions, "italia/map.pmtiles"), minLon = 6.6, minLat = 35.5, maxLon = 18.5, maxLat = 47.1)
        writeMapHeader(File(regions, "stati-uniti-colorado/map.pmtiles"), minLon = -109.1, minLat = 36.9, maxLon = -102.0, maxLat = 41.0)
        val storage = RegionStorage(regions, File(tempFolder.root, "staging").apply { mkdirs() })
        val regionRepository = FakeRegionRepository(
            listOf(
                InstalledRegion("italia", "Italia", "it", "1", null, "1", null, null, null, null, 0),
                InstalledRegion("stati-uniti-colorado", "Colorado", "us", "1", null, "1", null, null, null, null, 0),
            ),
        )
        return OnlineAlertsRepository(File(tempFolder.root, "alerts"), storage, regionRepository, client, Dispatchers.IO, clock)
    }

    // Header PMTiles v3 di 127 byte con il solo rettangolo (gradi x 10^7, little-endian dal byte 102).
    private fun writeMapHeader(file: File, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) {
        val header = ByteArray(127)
        ByteBuffer.wrap(header, 102, 16).order(ByteOrder.LITTLE_ENDIAN).apply {
            listOf(minLon, minLat, maxLon, maxLat).forEach { putInt((it * 10_000_000).toInt()) }
        }
        file.parentFile?.mkdirs()
        file.writeBytes(header)
    }

    @Test
    fun alertsOfTheRegionFromAllSources() = runBlocking {
        val result = checkNotNull(repository().alerts("italia"))
        assertTrue(result.fresh)
        assertEquals(now.toEpochMilli(), result.updatedAtMillis)
        // Terremoti vicino a Malfa (magnitudo 4,7 e 5,2) e allerte meteo italiane; niente eventi fuori dall'Italia.
        assertTrue(result.alerts.any { it.source == AlertSource.USGS })
        assertTrue(result.alerts.any { it.source == AlertSource.METEOALARM })
        assertTrue(result.alerts.all { it.area == null || it.area.intersects(AlertBounds(6.6, 35.5, 18.5, 47.1)) })
        // Otto richieste: due a GDACS, due a USGS, due ai centri tsunami, Meteoalarm per l'Italia e un suo messaggio CAP;
        // nessuna con coordinate.
        assertEquals(8, requested.size)
        assertTrue(requested.any { it.endsWith("meteoalarm-legacy-atom-italy") })
        // Il nome del servizio nazionale viene dal messaggio CAP: il feed Atom non lo ha.
        assertEquals("Italian Air Force National Meteorological Service", result.meteoalarmSender)
        assertTrue(result.alerts.filter { it.source == AlertSource.METEOALARM }.all { it.issuedAt != null })
    }

    @Test
    fun nwsAlertsOfTheStateWithTheUserAgent() = runBlocking {
        val result = checkNotNull(repository().alerts("stati-uniti-colorado"))
        val nws = requested.single { "api.weather.gov" in it }
        assertTrue(nws.endsWith("area=CO"))
        assertTrue(userAgents[nws].orEmpty().contains("github.com/miracle091/pocket-travel"))
        // Il Flood Warning ha un poligono in Colorado; le allerte senza poligono valgono per tutto lo stato richiesto.
        assertTrue(result.alerts.any { it.source == AlertSource.NWS && it.title == "Flood Warning" })
    }

    @Test
    fun noNewRequestsWithinTheLimitAndSavedAlertsOffline() = runBlocking {
        val repository = repository()
        repository.alerts("italia")
        requested.clear()
        now = now.plusSeconds(5 * 60)
        repository.alerts("italia")
        assertEquals(emptyList<String>(), requested)

        // Dopo il limite e senza rete: le allerte salvate, segnate come non aggiornate.
        now = now.plusSeconds(20 * 60)
        online = false
        val offline = checkNotNull(repository.alerts("italia"))
        assertFalse(offline.fresh)
        assertTrue(offline.alerts.isNotEmpty())
    }

    @Test
    fun oneSourceDownStillCountsAsUpdated() = runBlocking {
        val repository = repository()
        repository.alerts("italia")
        now = now.plusSeconds(20 * 60)
        failing = "gdacs"
        val result = checkNotNull(repository.alerts("italia"))
        // GDACS non risponde: restano le sue allerte salvate, ma le altre fonti sono aggiornate adesso.
        assertTrue(result.fresh)
        assertEquals(now.toEpochMilli(), result.updatedAtMillis)
    }

    @Test
    fun nothingWithoutNetworkAndWithoutSavedAlerts() = runBlocking {
        online = false
        assertNull(repository().alerts("italia"))
    }

    @Test
    fun aSourceThatDoesNotAnswerIsInterruptedByTheCallersTimeout() = runBlocking {
        hanging = "gdacs"
        val started = System.nanoTime()
        val result = withTimeoutOrNull(1_000) { repository().alerts("italia") }
        assertNull(result)
        // La lettura bloccata si interrompe con il timeout, senza aspettare il tetto della chiamata.
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
    }

    @Test
    fun theSourcesAreReadInParallel() = runBlocking {
        hanging = "gdacs"
        // Con GDACS bloccato le altre fonti rispondono comunque: la richiesta si annulla ma sono state tutte fatte.
        withTimeoutOrNull(1_000) { repository().alerts("italia") }
        assertTrue(requested.any { "usgs" in it })
        assertTrue(requested.any { "tsunami.gov" in it })
    }

    @Test
    fun concurrentCallsForTheSameRegionShareOneDownload() = runBlocking {
        slowMillis = 200
        val repository = repository()
        val results = listOf(async { repository.alerts("italia") }, async { repository.alerts("italia") }).awaitAll()
        assertTrue(results.all { it != null })
        // Una sola lettura di tutte le fonti (otto richieste), non una per chiamante.
        assertEquals(8, requested.size)
    }

    @Test
    fun cancellingOneCallerDoesNotStopTheOther() = runBlocking {
        slowMillis = 200
        val repository = repository()
        val first = async { repository.alerts("italia") }
        val second = async { repository.alerts("italia") }
        delay(100)
        first.cancel()
        assertTrue(second.await() != null)
        assertEquals(8, requested.size)
    }

    @Test
    fun aFailedDownloadIsNotKeptForTheNextCall() = runBlocking {
        val repository = repository()
        online = false
        assertNull(repository.alerts("italia"))
        online = true
        assertTrue(repository.alerts("italia") != null)
    }
}
