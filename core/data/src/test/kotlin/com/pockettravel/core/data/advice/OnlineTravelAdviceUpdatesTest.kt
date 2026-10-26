package com.pockettravel.core.data.advice

import com.pockettravel.core.data.WEATHER_MIN_REFRESH_MILLIS
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// OnlineTravelAdviceUpdates con un sito finto della GOV.UK Content API.
class OnlineTravelAdviceUpdatesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var now = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val requested = mutableListOf<String>()
    private var online = true
    private var code = 200
    private var body = """{"base_path":"/foreign-travel-advice/spain","public_updated_at":"2026-10-20T09:15:00.000+01:00"}"""

    private val client = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            requested += chain.request().url.toString()
            if (!online) throw IOException("niente rete")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("ok")
                .body(body.toResponseBody()).build()
        },
    ).build()

    private fun updates() = OnlineTravelAdviceUpdates(File(tempFolder.root, "advice"), client, Dispatchers.IO, clock)

    @Test
    fun readsPublicUpdatedAtAndBuildsThePageUrl() = runBlocking {
        val update = updates().latest("spain")

        assertEquals(Instant.parse("2026-10-20T08:15:00Z"), update?.updatedAt)
        assertEquals("https://www.gov.uk/foreign-travel-advice/spain", update?.webUrl)
        assertEquals(listOf("https://www.gov.uk/api/content/foreign-travel-advice/spain"), requested)
    }

    @Test
    fun asksOnceEvery10MinutesPerCountry() = runBlocking {
        val updates = updates()
        updates.latest("spain")
        now = now.plusMillis(WEATHER_MIN_REFRESH_MILLIS - 1)
        updates.latest("spain")
        assertEquals(1, requested.size)

        now = now.plusMillis(1)
        updates.latest("spain")
        updates.latest("france")
        assertEquals(3, requested.size)
    }

    @Test
    fun keepsTheLastAnswerWhenOffline() = runBlocking {
        updates().latest("spain")
        online = false
        now = now.plusMillis(WEATHER_MIN_REFRESH_MILLIS)

        assertEquals(Instant.parse("2026-10-20T08:15:00Z"), updates().latest("spain")?.updatedAt)
    }

    @Test
    fun doesNotRetryAFailedRequestBeforeTheLimit() = runBlocking {
        online = false
        val updates = updates()

        assertNull(updates.latest("spain"))
        assertNull(updates.latest("spain"))
        assertEquals(1, requested.size)
    }

    @Test
    fun unreadableAnswerDoesNotReplaceTheSavedOne() = runBlocking {
        updates().latest("spain")
        now = now.plusMillis(WEATHER_MIN_REFRESH_MILLIS)
        body = "<html>errore</html>"

        assertEquals(Instant.parse("2026-10-20T08:15:00Z"), updates().latest("spain")?.updatedAt)
    }

    @Test
    fun errorStatusGivesNothingWithoutASavedAnswer() = runBlocking {
        code = 404

        assertNull(updates().latest("spain"))
    }

    @Test
    fun rejectsSlugsThatAreNotPlainNames() = runBlocking {
        assertNull(updates().latest("../etc"))
        assertTrue(requested.isEmpty())
    }

    @Test
    fun comparesTheUtcDayWithThePackageDay() {
        val update = TravelAdviceUpdate(Instant.parse("2026-10-20T23:30:00Z"), "https://www.gov.uk/foreign-travel-advice/spain")

        assertTrue(update.isAfter(LocalDate.of(2026, 10, 19)))
        assertFalse(update.isAfter(LocalDate.of(2026, 10, 20)))
    }
}
