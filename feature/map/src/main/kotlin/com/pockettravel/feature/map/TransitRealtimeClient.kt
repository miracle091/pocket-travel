package com.pockettravel.feature.map

import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.RealtimeFeed
import com.pockettravel.core.data.decodeTripUpdates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject

/** Un feed letto e i byte scaricati per averlo (compressi, se l'ente usa gzip): il peso vero di un aggiornamento. */
data class RealtimeDownload(val feed: RealtimeFeed, val bytes: Long)

/** Scarica e legge un feed GTFS-RT (TripUpdates o avvisi) di una rete, dall'ente (TransitFeedInfo.realtimeUrl, alertsUrl). */
interface TransitRealtimeClient {
    /** Lancia un'eccezione se il feed non arriva, supera [MAX_REALTIME_BYTES] decompresso o non si legge. */
    suspend fun fetch(url: String): RealtimeDownload
}

/** [TransitRealtimeClient] con OkHttp: niente cache su disco, il feed vale solo adesso. */
class HttpTransitRealtimeClient @Inject constructor(
    okHttpClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TransitRealtimeClient {
    // Tetto per chiamata: con una rete lenta il tabellone non aspetta un feed di minuti fa.
    private val client = okHttpClient.newBuilder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    override suspend fun fetch(url: String): RealtimeDownload = withContext(ioDispatcher) {
        // Accept: Open Data Hub (STA) risponde in JSON senza application/x-protobuf, VBB risponde 406 senza */*. User-Agent
        // con il contatto: VBB lo chiede a chi legge il feed. Accept-Encoding esplicito: OkHttp non decomprime da solo e
        // si contano i byte arrivati davvero (Berlino: 2,25 MB invece di 9), che decidono ogni quanto riscaricare.
        val request = Request.Builder().url(url)
            .header("Accept", REALTIME_ACCEPT)
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", REALTIME_USER_AGENT)
            .build()
        val call = client.newCall(request)
        // execute() e le letture bloccano e non vedono la cancellazione: annullando la coroutine si annulla la chiamata, che
        // fa fallire la lettura in corso (la sospensione registra il gestore subito, prima del blocco).
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<Unit> { it.invokeOnCancellation { call.cancel() } }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val counted = CountingInputStream(response.body.byteStream())
                val gzip = response.header("Content-Encoding").equals("gzip", ignoreCase = true)
                val bytes = (if (gzip) GZIPInputStream(counted) else counted).use { readAtMost(this, it, MAX_REALTIME_BYTES) }
                RealtimeDownload(decodeTripUpdates(unwrapBase64Body(bytes)), counted.count)
            }
        } finally {
            canceller.cancel()
        }
    }
}

// Conta i byte letti dallo stream della risposta, prima della decompressione.
private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
}

// Lettura con un tetto: un feed piu' grande del previsto (o una risposta sbagliata) non riempie la memoria.
private fun readAtMost(scope: CoroutineScope, input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
        scope.ensureActive()
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > limit) throw IOException("feed del tempo reale oltre $limit byte")
    }
    return out.toByteArray()
}

/** Berlino, il feed piu' grande in catalogo, e' circa 10 MB decompresso: il tetto lascia un margine, non di piu'. */
internal const val MAX_REALTIME_BYTES = 16 * 1024 * 1024
private const val BUFFER_BYTES = 64 * 1024
private const val REALTIME_ACCEPT = "application/x-protobuf, application/octet-stream, */*"
private const val REALTIME_USER_AGENT = "PocketTravel (https://github.com/miracle091/pocket-travel)"
private const val CALL_TIMEOUT_SECONDS = 30L

/**
 * Adelaide serve il protobuf come testo base64 (Content-Type JSON, a volte tra virgolette o a capo): se il corpo e' solo
 * base64 lo decodifica, se no lo lascia com'e'. Non guarda il Content-Type (Eurostar manda `application/java`): un
 * FeedMessage vero comincia con 0x0A (campo 1, header), e basta a non scambiarlo per testo.
 */
internal fun unwrapBase64Body(bytes: ByteArray): ByteArray {
    val payload = bytes.takeUnless { it.isEmpty() || it[0] == PROTOBUF_FIRST_BYTE }?.let(::base64Payload) ?: return bytes
    return try {
        Base64.getDecoder().decode(payload)
    } catch (_: IllegalArgumentException) {
        bytes
    }
}

// Il corpo senza spazi ne' virgolette attorno; null se non e' solo base64 (o e' vuoto).
private fun base64Payload(bytes: ByteArray): ByteArray? {
    val clean = ByteArrayOutputStream(bytes.size)
    for (i in unquotedRange(bytes)) {
        val b = bytes[i]
        if (isSpace(b)) continue
        if (!isBase64Char(b.toInt())) return null
        clean.write(b.toInt())
    }
    return clean.toByteArray().takeIf { it.isNotEmpty() }
}

// Gli indici del corpo senza spazi ai bordi e, se ci sono, senza la coppia di virgolette.
private fun unquotedRange(bytes: ByteArray): IntRange {
    var start = 0
    var end = bytes.size
    while (start < end && isSpace(bytes[start])) start++
    while (end > start && isSpace(bytes[end - 1])) end--
    if (end - start >= 2 && bytes[start] == QUOTE && bytes[end - 1] == QUOTE) {
        start++
        end--
    }
    return start until end
}

private fun isSpace(b: Byte) = b in 0..SPACE.toInt()

private fun isBase64Char(c: Int) = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
    c == '+'.code || c == '/'.code || c == '='.code

private const val PROTOBUF_FIRST_BYTE: Byte = 0x0A
private const val SPACE: Byte = 0x20
private const val QUOTE: Byte = '"'.code.toByte()
