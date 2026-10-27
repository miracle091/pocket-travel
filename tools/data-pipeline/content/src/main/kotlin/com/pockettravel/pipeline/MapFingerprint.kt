package com.pockettravel.pipeline

import com.onthegomap.planetiler.pmtiles.Pmtiles
import com.pockettravel.core.tiles.tileIdBlocks
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPInputStream

/**
 * Impronta delle tile di un rettangolo in un archivio PMTiles (la build Protomaps da cui l'app estrae
 * la mappa): SHA-256 di id e lunghezza di ogni tile z[minZoom]-z[maxZoom] che interseca il rettangolo.
 * Si leggono solo intestazione e indici (poche richieste HTTP a intervalli), non le tile: se una
 * tile cambia, cambia quasi sempre anche la sua lunghezza compressa. build-region.sh la confronta
 * con quella pubblicata per dare una versione nuova alla mappa solo quando e' davvero cambiata.
 */
fun interface RangeReader {
    fun read(offset: Long, length: Int): ByteArray
}

fun httpRangeReader(url: String): RangeReader {
    val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    return RangeReader { offset, length ->
        val request = HttpRequest.newBuilder(URI(url))
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .header("User-Agent", "PocketTravelDataPipeline/1.0 (https://github.com/miracle091/pocket-travel)")
            .timeout(Duration.ofSeconds(120))
            .build()
        // Fino a 3 tentativi: senza timeout ne' ritentativi una connessione bloccata fermerebbe
        // build-region.sh per sempre, e un errore passeggero darebbe un'impronta vuota.
        withRetries(attempts = 3) {
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            // Solo 206: un 200 e' il file intero (il server ha ignorato il Range), non i byte chiesti.
            check(response.statusCode() == 206) {
                "HTTP ${response.statusCode()} leggendo $url: attesa risposta 206 (Partial Content) alla richiesta Range"
            }
            response.body()
        }
    }
}

/** Ritenta [block] su errori di rete (IOException, compresi i timeout) o risposte inattese, con attese di 2, 4... s. */
private fun <T> withRetries(attempts: Int, block: () -> T): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            if ((e !is IOException && e !is IllegalStateException) || attempt >= attempts) throw e
            Thread.sleep(2000L * attempt)
            attempt++
        }
    }
}

fun fileRangeReader(file: File): RangeReader = RangeReader { offset, length ->
    RandomAccessFile(file, "r").use { raf ->
        val bytes = ByteArray(minOf(length.toLong(), raf.length() - offset).toInt())
        raf.seek(offset)
        raf.readFully(bytes)
        bytes
    }
}

data class MapFingerprintResult(val sha256: String, val tiles: Int)

fun mapFingerprint(
    reader: RangeReader,
    minLon: Double, minLat: Double, maxLon: Double, maxLat: Double,
    minZoom: Int, maxZoom: Int,
): MapFingerprintResult {
    val header = Pmtiles.Header.fromBytes(reader.read(0, 127))
    val needed = TileIdCursor(tileIdBlocks(minLon, minLat, maxLon, maxLat, minZoom, maxZoom).iterator())
    val digest = MessageDigest.getInstance("SHA-256")
    var tiles = 0

    fun directory(offset: Long, length: Long): List<Pmtiles.Entry> {
        val bytes = reader.read(offset, length.toInt())
        val raw = if (header.internalCompression() == Pmtiles.Compression.GZIP) GZIPInputStream(bytes.inputStream()).readBytes() else bytes
        return Pmtiles.directoryFromBytes(raw)
    }

    // Le voci si visitano in ordine di id crescente, come richiede il cursore.
    fun walk(entries: List<Pmtiles.Entry>, end: Long) {
        entries.forEachIndexed { index, entry ->
            val next = entries.getOrNull(index + 1)?.tileId() ?: end
            if (entry.runLength() == 0) {
                // Indice foglia: si scarica solo se copre almeno una tile del rettangolo.
                if ((needed.peekAtLeast(entry.tileId()) ?: Long.MAX_VALUE) < next) {
                    walk(directory(header.leafDirectoriesOffset() + entry.offset(), entry.length().toLong()), next)
                }
            } else {
                // Tile cercate nell'intervallo [tileId, tileId + runLength) della voce.
                val runEnd = entry.tileId() + entry.runLength()
                while (true) {
                    val id = needed.peekAtLeast(entry.tileId())?.takeIf { it < runEnd } ?: break
                    digest.update(longBytes(id))
                    digest.update(longBytes(entry.length().toLong()))
                    tiles++
                    needed.advance()
                }
            }
        }
    }

    walk(directory(header.rootDirOffset(), header.rootDirLength()), Long.MAX_VALUE)
    return MapFingerprintResult(digest.digest().joinToString("") { "%02x".format(it) }, tiles)
}

/**
 * Lettore in avanti degli id cercati, che tiene in memoria un blocco alla volta. Le richieste devono avere
 * [peekAtLeast] non decrescente: gli id minori vengono scartati.
 */
private class TileIdCursor(private val blocks: Iterator<LongArray>) {
    private var block = LongArray(0)
    private var position = 0

    /** Primo id cercato >= [from], senza consumarlo; null se sono finiti. */
    fun peekAtLeast(from: Long): Long? {
        while (true) {
            while (position < block.size && block[position] < from) position++
            if (position < block.size) return block[position]
            if (!blocks.hasNext()) return null
            block = blocks.next()
            position = 0
        }
    }

    fun advance() {
        position++
    }
}

private fun longBytes(value: Long) = ByteArray(8) { (value shr (56 - 8 * it)).toByte() }

fun main(args: Array<String>) {
    require(args.size == 7) { "Uso: mapFingerprint <url o file .pmtiles> <minLon> <minLat> <maxLon> <maxLat> <minZoom> <maxZoom>" }
    val source = args[0]
    val reader = if (source.startsWith("http")) httpRangeReader(source) else fileRangeReader(File(source))
    val (minLon, minLat, maxLon, maxLat) = args.slice(1..4).map { it.toDouble() }
    val result = mapFingerprint(reader, minLon, minLat, maxLon, maxLat, args[5].toInt(), args[6].toInt())
    // Unica riga su stdout, letta da build-region.sh.
    println("impronta ${result.sha256} ${result.tiles}")
}
