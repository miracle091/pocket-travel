package com.pockettravel.feature.map.testing

import com.pockettravel.core.data.RealtimeFeed
import com.pockettravel.feature.map.ConnectivityChecker
import com.pockettravel.feature.map.RealtimeDownload
import com.pockettravel.feature.map.TransitRealtimeClient
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** [TransitRealtimeClient] in memoria: risponde con [feeds] per indirizzo (IOException se manca), pesanti [bytes] (30 KB se non indicato), e registra [requests]. */
class FakeTransitRealtimeClient : TransitRealtimeClient {
    val feeds = mutableMapOf<String, RealtimeFeed>()
    val bytes = mutableMapOf<String, Long>()
    val requests = mutableListOf<String>()

    override suspend fun fetch(url: String): RealtimeDownload {
        requests += url
        val feed = feeds[url] ?: throw IOException("nessun feed per $url")
        return RealtimeDownload(feed, bytes[url] ?: 30_000L)
    }
}

/** [ConnectivityChecker] pilotabile. */
class FakeConnectivityChecker(var online: Boolean = true, var metered: Boolean = false) : ConnectivityChecker {
    override fun isOnline() = online

    override fun isMetered() = metered
}

/** Orologio fermo su [now], da spostare a mano. */
class MutableClock(var now: Instant = Instant.parse("2026-10-22T08:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}
