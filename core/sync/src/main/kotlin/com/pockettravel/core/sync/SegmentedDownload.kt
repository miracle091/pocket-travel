package com.pockettravel.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Errore che ritentare non risolve: risposta del server incoerente con il catalogo. */
class SegmentedDownloadException(message: String) : Exception(message)

/**
 * Scarica un file grande a segmenti di [segmentBytes] su [parallelism] connessioni separate (HTTP/1.1: con HTTP/2 le
 * richieste allo stesso host dividerebbero una sola connessione TCP). Il file parziale ([fetch]) e' preallocato e
 * accanto ha una mappa dei segmenti finiti, cosi' una ripresa salta quelli gia' scaricati. Non verifica lo SHA-256:
 * lo fa il chiamante sul file completo.
 */
class SegmentedDownload(
    okHttpClient: OkHttpClient,
    private val segmentBytes: Long = DEFAULT_SEGMENT_BYTES,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val minBytes: Long = DEFAULT_MIN_BYTES,
) {
    private val client = okHttpClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(parallelism, 1, TimeUnit.MINUTES))
        .build()

    /**
     * Riempie [part] (lungo [sizeBytes]) da [url]. false, senza toccare i file, se il server non regge le richieste
     * a intervalli o il file e' piccolo: il chiamante usa allora il suo scarico a una connessione. Lancia
     * [SegmentedDownloadException] (e scarta lo stato di ripresa) se il server risponde in modo incoerente, [IOException]
     * per i guasti di rete, da ritentare: al tentativo dopo la mappa fa saltare i segmenti finiti.
     */
    suspend fun fetch(
        url: String,
        part: File,
        sizeBytes: Long,
        sha256: String,
        progressStepBytes: Long,
        onProgress: suspend (bytesDownloaded: Long) -> Unit,
    ): Boolean {
        val resolved = (if (sizeBytes < minBytes) null else resolve(url.toHttpUrlOrThrow(), sizeBytes)) ?: return false
        val map = mapFile(part)
        val segments = ((sizeBytes + segmentBytes - 1) / segmentBytes).toInt()
        val done = openMap(map, part, sizeBytes, sha256, segments)
        val run = Run(url, resolved, part, map, sizeBytes, progressStepBytes, onProgress)
        run.progress.set(done.sumOf { run.lengthOf(it) })
        onProgress(run.progress.get())
        try {
            val queue = Channel<Int>(Channel.UNLIMITED)
            for (index in 0 until segments) if (index !in done) queue.trySend(index)
            queue.close()
            RandomAccessFile(part, "rw").use { file ->
                coroutineScope {
                    repeat(parallelism.coerceAtMost(segments)) { launch { for (index in queue) run.fetchSegment(index, file.channel) } }
                }
            }
        } catch (e: SegmentedDownloadException) {
            part.delete()
            map.delete()
            throw e
        }
        // Finito: senza mappa un .part della lunghezza giusta e' completo e passa alla verifica.
        map.delete()
        onProgress(sizeBytes)
        return true
    }

    // Prima richiesta (segue i redirect): Range 0-0 deve dare 206 e la dimensione del catalogo. null = niente segmenti
    // (server senza Range). Errori temporanei e risposte che non sono il file (captive portal) sono IOException: il
    // chiamante ritenta senza buttare i segmenti gia' scaricati.
    private suspend fun resolve(url: HttpUrl, sizeBytes: Long): HttpUrl? =
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).runCancellable { response ->
            val code = response.code
            when {
                isRetryableHttpStatus(code) -> throw IOException("Sonda del download fallita: HTTP $code")
                code == HTTP_PARTIAL_CONTENT ->
                    response.takeIf { response.header("Content-Range")?.let(::parseContentRange) == Triple(0L, 0L, sizeBytes) }?.request?.url
                code == HTTP_OK -> when {
                    response.header("Content-Type")?.startsWith("text/html", ignoreCase = true) == true ->
                        throw IOException("La sonda del download ha ricevuto una pagina HTML")
                    response.body.contentLength() != sizeBytes ->
                        throw IOException("La sonda del download ha ricevuto ${response.body.contentLength()} byte, attesi $sizeBytes")
                    else -> null
                }
                else -> null
            }
        }

    // Segmenti finiti dalla mappa; la mappa o il .part non coerenti con file e catalogo si scartano e si riparte da zero.
    private fun openMap(map: File, part: File, sizeBytes: Long, sha256: String, segments: Int): Set<Int> {
        val header = "v1 $sizeBytes $segmentBytes ${sha256.lowercase()}"
        val lines = if (map.isFile && part.length() == sizeBytes) map.readLines() else emptyList()
        if (lines.firstOrNull() == header) return lines.drop(1).mapNotNull { it.toIntOrNull() }.filter { it in 0 until segments }.toSet()
        // Prima la mappa, poi la lunghezza: un crash in mezzo non lascia un .part lungo quanto il file e senza mappa.
        part.delete()
        FileOutputStream(map).use { it.write("$header\n".toByteArray()); it.fd.sync() }
        RandomAccessFile(part, "rw").use { it.setLength(sizeBytes) }
        return emptySet()
    }

    private inner class Run(
        private val originalUrl: String,
        @Volatile private var url: HttpUrl,
        private val part: File,
        private val map: File,
        private val sizeBytes: Long,
        private val progressStepBytes: Long,
        private val onProgress: suspend (Long) -> Unit,
    ) {
        val progress = AtomicLong()
        private val lastReported = AtomicLong()
        private val reportLock = Mutex()
        private val mapLock = Mutex()
        private val refreshLock = Mutex()
        private val refreshes = AtomicInteger()

        fun lengthOf(index: Int): Long = minOf(segmentBytes, sizeBytes - index * segmentBytes)

        suspend fun fetchSegment(index: Int, channel: FileChannel) {
            val first = index * segmentBytes
            while (true) {
                val used = url
                var counted = 0L
                var finished = false
                try {
                    finished = readSegment(used, first, lengthOf(index), channel) { counted += it; addProgress(it) }
                } finally {
                    if (!finished) progress.addAndGet(-counted)
                }
                if (finished) {
                    // Un indirizzo firmato dura circa un'ora: un segmento finito col nuovo indirizzo ne rinnova il diritto.
                    if (used == url) refreshes.set(0)
                    break
                }
                refresh(used)
            }
            // I dati sul disco prima della mappa: una voce in mappa e' sempre un segmento intero.
            channel.force(false)
            mapLock.withLock { FileOutputStream(map, true).use { it.write("$index\n".toByteArray()); it.fd.sync() } }
        }

        // URL firmato scaduto (401/403): si risolve di nuovo dall'URL del catalogo, una volta sola finche' nessun
        // segmento finisce col nuovo indirizzo: se e' rifiutato subito, il rifiuto e' definitivo.
        private suspend fun refresh(expired: HttpUrl) = refreshLock.withLock {
            if (url != expired) return@withLock
            if (refreshes.getAndIncrement() > 0) throw SegmentedDownloadException("Indirizzo di download rifiutato (401/403)")
            url = resolve(originalUrl.toHttpUrlOrThrow(), sizeBytes) ?: throw SegmentedDownloadException("Il server non risponde piu' agli intervalli")
        }

        // false se l'indirizzo e' stato rifiutato (401/403).
        private suspend fun readSegment(from: HttpUrl, first: Long, length: Long, channel: FileChannel, onBytes: suspend (Int) -> Unit): Boolean {
            val last = first + length - 1
            val call = client.newCall(Request.Builder().url(from).header("Range", "bytes=$first-$last").build())
            return call.runCancellable { response ->
                if (response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN) return@runCancellable false
                responseError(response, first, last)?.let { throw it }
                val position = response.body.byteStream().use { copySegment(it, channel, first, last, onBytes) }
                if (position != last + 1) throw IOException("Segmento interrotto: ${position - first} byte di $length")
                true
            }
        }

        // Scrive [input] nel file da [first]; la posizione dopo l'ultimo byte scritto. Oltre [last] e' un errore del server.
        private suspend fun copySegment(input: InputStream, channel: FileChannel, first: Long, last: Long, onBytes: suspend (Int) -> Unit): Long {
            var position = first
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                currentCoroutineContext().ensureActive()
                if (position + read > last + 1) throw SegmentedDownloadException("Il segmento supera i ${last - first + 1} byte attesi")
                val chunk = ByteBuffer.wrap(buffer, 0, read)
                while (chunk.hasRemaining()) position += channel.write(chunk, position)
                onBytes(read)
            }
            return position
        }

        // L'errore di una risposta a un segmento: da ritentare (IOException) o no; null se e' il 206 atteso.
        private fun responseError(response: Response, first: Long, last: Long): Exception? {
            val code = response.code
            val range = response.header("Content-Range")?.let(::parseContentRange)
            return when {
                !response.isSuccessful && code in HTTP_CLIENT_ERRORS && !isRetryableHttpStatus(code) ->
                    SegmentedDownloadException("Download fallito: HTTP $code")
                !response.isSuccessful -> IOException("Download fallito: HTTP $code")
                code != HTTP_PARTIAL_CONTENT || range != Triple(first, last, sizeBytes) ->
                    SegmentedDownloadException("Risposta a intervalli non valida: HTTP $code, Content-Range ${response.header("Content-Range")}")
                else -> null
            }
        }

        // Una sola coroutine alla volta chiama onProgress, che il chiamante non deve rendere rientrante.
        private suspend fun addProgress(bytes: Int) {
            val now = progress.addAndGet(bytes.toLong())
            if (now - lastReported.get() >= progressStepBytes && reportLock.tryLock()) {
                try {
                    lastReported.set(now)
                    onProgress(now)
                } finally {
                    reportLock.unlock()
                }
            }
        }
    }

    private fun String.toHttpUrlOrThrow(): HttpUrl =
        toHttpUrlOrNull() ?: throw SegmentedDownloadException("Indirizzo non valido: $this")

    companion object {
        const val DEFAULT_SEGMENT_BYTES = 16L * 1024 * 1024
        const val DEFAULT_PARALLELISM = 4
        const val DEFAULT_MIN_BYTES = 32L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

        /** Mappa dei segmenti finiti accanto al file parziale. */
        fun mapFile(part: File): File = File(part.parentFile, "${part.name}.map")

        // (primo, ultimo, totale), o null se l'intestazione non e' nella forma attesa.
        private fun parseContentRange(value: String): Triple<Long, Long, Long>? =
            CONTENT_RANGE.matchEntire(value.trim())?.destructured?.let { (a, b, c) -> Triple(a.toLong(), b.toLong(), c.toLong()) }
    }
}

// execute() e le letture bloccano il thread: annullando la coroutine si annulla la chiamata, che le fa fallire subito.
internal suspend fun <T> Call.runCancellable(block: suspend (Response) -> T): T = coroutineScope {
    val canceller = launch(Dispatchers.Unconfined) {
        try {
            awaitCancellation()
        } finally {
            this@runCancellable.cancel()
        }
    }
    try {
        execute().use { block(it) }
    } catch (e: IOException) {
        // La chiamata annullata fallisce con "Socket closed": e' l'annullamento, non un errore da riportare.
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        canceller.cancel()
    }
}
