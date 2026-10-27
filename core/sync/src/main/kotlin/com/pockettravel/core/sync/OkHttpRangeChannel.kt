package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.UrlFileChannel
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** La build remota e' cambiata durante l'estrazione (ETag diverso): le tile gia' lette non sono piu' coerenti con le prossime. Non si ritenta. */
internal class SourceChangedIOException : IOException("La build della mappa e' cambiata durante l'estrazione")

/** La lettura e' stata annullata dal chiamante: non si ritenta. */
internal class RangeReadCancelledException : IOException("Lettura della mappa annullata")

/** Risposta HTTP che non e' l'intervallo richiesto; [retryable] per gli errori che possono passare da soli (5xx, 408, 429). */
internal class RangeResponseException(message: String, val retryable: Boolean) : IOException(message)

/**
 * Lettura a intervalli (HTTP range) di un file remoto come `FileChannel` di sola lettura per
 * ch.poole.geo.pmtiles.Reader, sopra l'[OkHttpClient] dell'app. Al posto di HttpUrlConnectionChannel della
 * libreria, che non ha timeout, non controlla il codice di stato e non si puo' interrompere:
 * - timeout dal client (connessione, lettura): una connessione ferma non blocca per sempre l'estrazione;
 * - [isCancelled] e' controllato durante l'attesa e, se vero, interrompe la richiesta in corso con `Call.cancel()`;
 * - la risposta deve essere 206 con un `Content-Range` che parte dal byte richiesto: un 200 (server che ignora
 *   `Range`) darebbe i primi byte del file al posto della tile;
 * - ogni richiesta si ritenta dopo ciascuna attesa di [retryDelaysMs] per errori di rete e 5xx; non per [SourceChangedIOException],
 *   annullamento e risposte che non sono l'intervallo richiesto.
 *
 * Bloccante: va usato fuori dal main thread.
 */
internal class OkHttpRangeChannel(
    private val client: OkHttpClient,
    private val url: String,
    private val retryDelaysMs: List<Long>,
    private val isCancelled: () -> Boolean,
) : UrlFileChannel() {
    private val savedETag = AtomicReference<String?>(null)

    override fun read(dst: ByteBuffer, pos: Long): Int {
        val length = dst.capacity()
        if (length == 0) return 0
        var attempt = 0
        while (true) {
            if (isCancelled()) throw RangeReadCancelledException()
            try {
                return readOnce(dst, pos, length)
            } catch (error: IOException) {
                if (!isRetryable(error) || attempt >= retryDelaysMs.size) throw error
                sleepUnlessCancelled(retryDelaysMs[attempt++])
            }
        }
    }

    private fun isRetryable(error: IOException) = when (error) {
        is SourceChangedIOException, is RangeReadCancelledException -> false
        is RangeResponseException -> error.retryable
        else -> true
    }

    private fun readOnce(dst: ByteBuffer, pos: Long, length: Int): Int {
        val request = Request.Builder().url(url).header("Range", "bytes=$pos-${pos + length - 1}").build()
        val result = CompletableFuture<Int>()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                result.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    result.complete(response.use { readBody(it, dst, pos, length) })
                } catch (error: IOException) {
                    result.completeExceptionally(error)
                }
            }
        })
        return await(result, call)
    }

    /** Aspetta la risposta controllando ogni [POLL_MS] l'annullamento; se arriva (o il thread e' interrotto) interrompe la richiesta. */
    private fun await(result: CompletableFuture<Int>, call: Call): Int {
        while (true) {
            var interrupted = false
            try {
                return result.get(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // ancora in corso
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                interrupted = true
            }
            if (interrupted || isCancelled()) {
                call.cancel()
                throw RangeReadCancelledException()
            }
        }
    }

    /** Il numero di byte che la risposta dichiara di avere, dopo aver controllato che sia l'intervallo richiesto da [pos]. */
    private fun checkedLength(response: Response, pos: Long, length: Int): Int {
        if (response.code != HTTP_PARTIAL_CONTENT) {
            val retryable = response.code >= HTTP_SERVER_ERROR || response.code == HTTP_REQUEST_TIMEOUT || response.code == HTTP_TOO_MANY_REQUESTS
            throw RangeResponseException("Il server ha risposto HTTP ${response.code} alla richiesta dell'intervallo da $pos", retryable)
        }
        val range = CONTENT_RANGE.find(response.header("Content-Range").orEmpty())
        val first = range?.groupValues?.get(1)?.toLong()
        val last = range?.groupValues?.get(2)?.toLong()
        if (first != pos || last == null || last > pos + length - 1) {
            throw RangeResponseException("Content-Range assente o diverso dall'intervallo richiesto da $pos", retryable = false)
        }
        return (last - first + 1).toInt()
    }

    private fun readBody(response: Response, dst: ByteBuffer, pos: Long, length: Int): Int {
        val expected = checkedLength(response, pos, length)
        response.header("ETag")?.let { eTag ->
            val previous = savedETag.getAndSet(eTag)
            if (previous != null && previous != eTag) throw SourceChangedIOException()
        }
        dst.rewind()
        val stream = response.body.byteStream()
        var done = 0
        while (done < expected) {
            val count = stream.read(dst.array(), dst.arrayOffset() + done, expected - done)
            if (count < 0) break
            done += count
        }
        if (done < expected) throw IOException("Risposta troncata: $done byte di $expected")
        return done
    }

    private fun sleepUnlessCancelled(millis: Long) {
        var remaining = millis
        while (remaining > 0) {
            if (isCancelled()) throw RangeReadCancelledException()
            val step = minOf(remaining, POLL_MS)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RangeReadCancelledException()
            }
            remaining -= step
        }
    }

    private companion object {
        // Ogni quanto si controlla l'annullamento mentre si aspetta la risposta.
        const val POLL_MS = 100L
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
        val CONTENT_RANGE = Regex("^bytes ([0-9]+)-([0-9]+)/")
    }
}
