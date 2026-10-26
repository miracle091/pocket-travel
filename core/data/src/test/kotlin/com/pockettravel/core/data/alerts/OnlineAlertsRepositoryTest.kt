package com.pockettravel.core.data.alerts

import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.testing.FakeRegionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

    private val requested = mutableListOf<String>()
    private var online = true

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("alerts/$name")).readText()

    private val client = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            val url = chain.request().url.toString()
            requested += url
            if (!online) throw IOException("niente rete")
            val body = when {
                "gdacs" in url -> fixture("gdacs_events.json")
                "usgs" in url -> fixture("usgs_events.json")
                "/api/v1/warnings/" in url -> fixture("meteoalarm_cap.xml")
                "meteoalarm" in url -> fixture("meteoalarm_events.xml")
                else -> error("indirizzo inatteso: $url")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body.toResponseBody()).build()
        },
    ).build()

    private fun repository(): OnlineAlertsRepository {
        val regions = File(tempFolder.root, "regions")
        writeMapHeader(File(regions, "italia/map.pmtiles"), minLon = 6.6, minLat = 35.5, maxLon = 18.5, maxLat = 47.1)
        val storage = RegionStorage(regions, File(tempFolder.root, "staging").apply { mkdirs() })
        val regionRepository = FakeRegionRepository(
            listOf(InstalledRegion("italia", "Italia", "it", "1", null, "1", null, null, null, null, 0)),
        )
        return OnlineAlertsRepository(File(tempFolder.root, "alerts"), storage, regionRepository, client, Dispatchers.IO, clock)
    }

    // Header PMTiles v3 di 127 byte con il solo rettangolo (gradi x 10^7, little-endian dal byte 102).
    private fun writeMapHeader(file: File, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) {
        val header = ByteArray(127)
        ByteBuffer.wrap(header, 102, 16).order(ByteOrder.LITTLE_ENDIAN).apply {
            listOf(minLon, minLat, maxLon, maxLat).forEach { putInt((it * 10_000_000).toInt()) }
        }
        file.parentFile.mkdirs()
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
        // Sei richieste: due a GDACS, due a USGS, Meteoalarm per l'Italia e un suo messaggio CAP; nessuna con coordinate.
        assertEquals(6, requested.size)
        assertTrue(requested.any { it.endsWith("meteoalarm-legacy-atom-italy") })
        // Il nome del servizio nazionale viene dal messaggio CAP: il feed Atom non lo ha.
        assertEquals("Italian Air Force National Meteorological Service", result.meteoalarmSender)
        assertTrue(result.alerts.filter { it.source == AlertSource.METEOALARM }.all { it.issuedAt != null })
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
    fun nothingWithoutNetworkAndWithoutSavedAlerts() = runBlocking {
        online = false
        assertNull(repository().alerts("italia"))
    }
}
