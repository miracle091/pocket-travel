package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.UrlFileChannel
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** La build remota e' cambiata durante l'estrazione (ETag diverso): le tile gia' lette non sono piu' coerenti con le prossime. Non si ritenta. */
internal class SourceChangedIOException : IOException("La build della mappa e' cambiata durante l'estrazione")

/** La lettura e' stata annullata dal chiamante: non si ritenta. */
internal class RangeReadCancelledException : IOException("Lettura della mappa annullata")

/** Risposta HTTP che non e' l'intervallo richiesto; [retryable] per gli errori che possono passare da soli (5xx, 408, 429). */
internal class RangeResponseException(message: String, val retryable: Boolean) : IOException(message)

private const val KIBIBYTE = 1024L
private const val MEBIBYTE = KIBIBYTE * KIBIBYTE
private const val QUARTER_MEBIBYTE = MEBIBYTE / 4
private const val SAMPLE_MIN_BYTES = 64 * KIBIBYTE
private const val MAX_BATCH_BYTES = 16 * MEBIBYTE
private const val STALL_FLOOR_BYTES_PER_SECOND = 2 * KIBIBYTE

/** Richiesta interrotta perche' la velocita' e' crollata rispetto a quella recente: si ritenta su una connessione nuova. */
internal class StalledReadException(bytesPerSecond: Long) :
    IOException("Trasferimento della mappa fermo: $bytesPerSecond byte al secondo")

/**
 * Soglie della lettura adattiva. Con la velocita' media recente (media mobile esponenziale delle richieste
 * concluse) il blocco successivo e' dimensionato per durare circa [targetBatchMs], tra [minBatchBytes] e
 * [maxBatchBytes]; si parte da [initialBatchBytes]. Una richiesta e' ferma se per [stallWindowMs] va sotto
 * [stallFloorBytesPerSecond] oppure, con almeno [minSamplesForRelative] misure, sotto [stallRatio] volte la media.
 */
internal data class RangeTuning(
    val initialBatchBytes: Long = MEBIBYTE,
    val minBatchBytes: Long = QUARTER_MEBIBYTE,
    val maxBatchBytes: Long = MAX_BATCH_BYTES,
    val targetBatchMs: Long = 6_000L,
    // Le letture piu' piccole (intestazione, directory) sono dominate dalla latenza e non misurano la banda.
    val minSampleBytes: Long = SAMPLE_MIN_BYTES,
    val averageWeight: Double = 0.3,
    val stallWindowMs: Long = 15_000L,
    val stallFloorBytesPerSecond: Long = STALL_FLOOR_BYTES_PER_SECOND,
    val stallRatio: Double = 0.1,
    val minSamplesForRelative: Int = 3,
)

/** Velocita' media delle richieste concluse, da cui la dimensione del blocco successivo e la soglia di fermo. Thread-safe. */
internal class ThroughputEstimator(private val tuning: RangeTuning) {
    private var average = 0.0
    private var hasAverage = false
    private var samples = 0

    /** Registra una richiesta conclusa; ignora quelle troppo piccole per misurare la banda. */
    @Synchronized
    fun record(bytes: Long, elapsedNanos: Long) {
        if (bytes < tuning.minSampleBytes || elapsedNanos <= 0) return
        val rate = bytes * NANOS_PER_SECOND / elapsedNanos
        average = if (samples == 0) rate else tuning.averageWeight * rate + (1 - tuning.averageWeight) * average
        hasAverage = true
        samples++
    }

    /** Dopo un fermo la media precedente non vale piu': riparte dalla velocita' osservata e torna significativa con nuove misure. */
    @Synchronized
    fun reset(observedBytesPerSecond: Double) {
        average = observedBytesPerSecond
        hasAverage = true
        samples = 0
    }

    /** Dimensione del prossimo blocco in byte. */
    @Synchronized
    fun batchBytes(): Long {
        val bytes = if (!hasAverage) tuning.initialBatchBytes else (average * tuning.targetBatchMs / MILLIS_PER_SECOND).toLong()
        return bytes.coerceIn(tuning.minBatchBytes, tuning.maxBatchBytes)
    }

    /** Sotto questa velocita' (byte al secondo) una richiesta in corso e' ferma. */
    @Synchronized
    fun stallThreshold(): Double {
        val relative = if (samples >= tuning.minSamplesForRelative) average * tuning.stallRatio else 0.0
        return maxOf(tuning.stallFloorBytesPerSecond.toDouble(), relative)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MILLIS_PER_SECOND = 1_000.0
    }
}

/**
 * Lettura a intervalli (HTTP range) di un file remoto come `FileChannel` di sola lettura per
 * ch.poole.geo.pmtiles.Reader, sopra l'[OkHttpClient] dell'app. Al posto di HttpUrlConnectionChannel della
 * libreria, che non ha timeout, non controlla il codice di stato e non si puo' interrompere:
 * - timeout dal client (connessione, lettura): una connessione ferma non blocca per sempre l'estrazione;
 * - [isCancelled] e' controllato durante l'attesa e, se vero, interrompe la richiesta in corso con `Call.cancel()`;
 * - la risposta deve essere 206 con un `Content-Range` che parte dal byte richiesto: un 200 (server che ignora
 *   `Range`) darebbe i primi byte del file al posto della tile;
 * - ogni richiesta si ritenta dopo ciascuna attesa di [retryDelaysMs] per errori di rete e 5xx; non per [SourceChangedIOException],
 *   annullamento e risposte che non sono l'intervallo richiesto;
 * - [nextBatchBytes] dice quanto leggere con la richiesta successiva in base alla velocita' delle precedenti;
 * - una richiesta la cui velocita' resta per [RangeTuning.stallWindowMs] molto sotto la media recente (i byte arrivano
 *   a goccia, quindi il timeout di lettura non scatta) si interrompe e si ritenta su una connessione nuova, come un errore di rete.
 *
 * Bloccante: va usato fuori dal main thread.
 */
internal class OkHttpRangeChannel(
    client: OkHttpClient,
    private val url: String,
    private val retryDelaysMs: List<Long>,
    private val tuning: RangeTuning = RangeTuning(),
    private val isCancelled: () -> Boolean,
) : UrlFileChannel() {
    @Volatile private var client = client
    private val savedETag = AtomicReference<String?>(null)
    private val estimator = ThroughputEstimator(tuning)

    /** Byte da leggere con la prossima richiesta per durare circa [RangeTuning.targetBatchMs] alla velocita' recente. */
    fun nextBatchBytes(): Long = estimator.batchBytes()

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
        val received = AtomicLong()
        val headersAt = AtomicLong()
        val started = System.nanoTime()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                result.completeExceptionally(e)
            }

            @Suppress("TooGenericExceptionCaught") // anche Error: altrimenti chi aspetta resta bloccato
            override fun onResponse(call: Call, response: Response) {
                // OkHttp non chiama onFailure dopo onResponse: ogni eccezione deve completare il risultato.
                headersAt.set(System.nanoTime())
                try {
                    result.complete(response.use { readBody(it, dst, pos, length, received) })
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            }
        })
        val done = await(result, call, received, headersAt)
        estimator.record(done.toLong(), System.nanoTime() - started)
        return done
    }

    /**
     * Aspetta la risposta controllando ogni [POLL_MS] l'annullamento; se arriva (o il thread e' interrotto) interrompe la richiesta.
     * Controlla anche che i byte ricevuti ([received]) non vadano troppo piano (vedi [StallMonitor]).
     */
    private fun await(result: CompletableFuture<Int>, call: Call, received: AtomicLong, headersAt: AtomicLong): Int {
        val monitor = StallMonitor(received, headersAt)
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
            // Conclusa proprio adesso: il suo esito (anche SourceChangedIOException) lo legge il giro successivo.
            if (result.isDone) continue
            monitor.stalledBytesPerSecond()?.let { speed -> return abortStalled(result, call, speed) }
        }
    }

    /**
     * Finestre consecutive di [RangeTuning.stallWindowMs]: la richiesta e' ferma se in una finestra intera la velocita'
     * e' stata sotto [ThroughputEstimator.stallThreshold]. Una rete lenta fin dall'inizio non ha media e passa dalla sola soglia assoluta.
     * La prima finestra parte dalle intestazioni ([headersAt]): connessione e attesa della risposta restano ai timeout del client.
     */
    private inner class StallMonitor(private val received: AtomicLong, private val headersAt: AtomicLong) {
        private val windowNanos = tuning.stallWindowMs * NANOS_PER_MILLI
        private var windowStart = 0L
        private var windowBytes = 0L

        /** La velocita' nella finestra appena conclusa se e' sotto soglia, altrimenti null. */
        fun stalledBytesPerSecond(): Double? {
            if (windowStart == 0L) windowStart = headersAt.get()
            val now = System.nanoTime()
            val elapsed = now - windowStart
            if (windowStart == 0L || elapsed < windowNanos) return null
            val bytes = received.get()
            val speed = (bytes - windowBytes) * NANOS_PER_SECOND / elapsed
            windowStart = now
            windowBytes = bytes
            return speed.takeIf { it < estimator.stallThreshold() }
        }
    }

    /**
     * Chiude la richiesta ferma, aspetta che il thread di lettura abbia smesso di scrivere nel buffer (il ritentativo lo riusa)
     * passa a un pool di connessioni nuovo (la connessione lenta non e' piu' riutilizzata, anche se multiplexata) e lancia
     * [StalledReadException]. Se la richiesta si e' conclusa lo stesso ne restituisce l'esito: i dati completi, o
     * [SourceChangedIOException], che un ritentativo nasconderebbe (l'ETag nuovo e' gia' salvato).
     */
    private fun abortStalled(result: CompletableFuture<Int>, call: Call, speed: Double): Int {
        call.cancel()
        try {
            return result.get(CANCEL_JOIN_MS, TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) {
            // altrimenti atteso: la richiesta annullata fallisce
            (error.cause as? SourceChangedIOException)?.let { throw it }
        } catch (_: TimeoutException) {
            // il thread di lettura non si e' liberato in tempo
        } catch (_: InterruptedException) {
            // Con il flag ripristinato l'attesa prima del ritentativo (sleepUnlessCancelled) chiude come annullamento.
            Thread.currentThread().interrupt()
        }
        estimator.reset(speed)
        client = client.newBuilder().connectionPool(ConnectionPool()).build()
        throw StalledReadException(speed.toLong())
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

    private fun readBody(response: Response, dst: ByteBuffer, pos: Long, length: Int, received: AtomicLong): Int {
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
            received.set(done.toLong())
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
        // Attesa massima della fine del thread di lettura dopo l'annullamento di una richiesta ferma.
        const val CANCEL_JOIN_MS = 2_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
        val CONTENT_RANGE = Regex("^bytes ([0-9]+)-([0-9]+)/")
    }
}
