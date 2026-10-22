package com.pockettravel.feature.map

import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.RealtimeFeed
import com.pockettravel.core.data.decodeTripUpdates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Scarica e legge il feed GTFS-RT TripUpdates di una rete, dall'ente (TransitFeedInfo.realtimeUrl). */
interface TransitRealtimeClient {
    /** Lancia un'eccezione se il feed non arriva, supera [MAX_REALTIME_BYTES] o non si legge. */
    suspend fun fetch(url: String): RealtimeFeed
}

/** [TransitRealtimeClient] con OkHttp: niente cache su disco, il feed vale solo adesso. */
class HttpTransitRealtimeClient @Inject constructor(
    okHttpClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TransitRealtimeClient {
    // Tetto per chiamata: con una rete lenta il tabellone non aspetta un feed di minuti fa.
    private val client = okHttpClient.newBuilder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    override suspend fun fetch(url: String): RealtimeFeed = withContext(ioDispatcher) {
        // Accept: Open Data Hub (STA) risponde in JSON senza application/x-protobuf, VBB risponde 406 senza */*. User-Agent
        // con il contatto: VBB lo chiede a chi legge il feed.
        val request = Request.Builder().url(url)
            .header("Accept", REALTIME_ACCEPT)
            .header("User-Agent", REALTIME_USER_AGENT)
            .build()
        val bytes = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            readAtMost(response.body.byteStream(), MAX_REALTIME_BYTES)
        }
        decodeTripUpdates(bytes)
    }
}

// Lettura con un tetto: un feed piu' grande del previsto (o una risposta sbagliata) non riempie la memoria.
private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > limit) throw IOException("feed del tempo reale oltre $limit byte")
    }
    return out.toByteArray()
}

/** Il feed piu' grande misurato e' Praga, 3,8 MB compressi (circa il quadruplo decompresso). */
internal const val MAX_REALTIME_BYTES = 32 * 1024 * 1024
private const val BUFFER_BYTES = 64 * 1024
private const val REALTIME_ACCEPT = "application/x-protobuf, application/octet-stream, */*"
private const val REALTIME_USER_AGENT = "PocketTravel (https://github.com/miracle091/pocket-travel)"
private const val CALL_TIMEOUT_SECONDS = 30L
