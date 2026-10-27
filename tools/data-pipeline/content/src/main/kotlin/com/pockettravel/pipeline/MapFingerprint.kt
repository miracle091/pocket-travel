package com.pockettravel.pipeline

import com.onthegomap.planetiler.geo.TileCoord
import com.onthegomap.planetiler.pmtiles.Pmtiles
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
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

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

// Livelli di zoom sotto una tile "sottoalbero": le sue 4^8 tile discendenti formano il blocco piu' grande.
private const val SUBTREE_LEVELS = 8

/** Dimensione dopo la quale un blocco di id viene consegnato; un blocco ne ha al piu' questi piu' 4^[SUBTREE_LEVELS]. */
internal const val TILE_BLOCK_TARGET = 16_384

/**
 * Id (curva di Hilbert, come negli indici PMTiles) delle tile che intersecano il rettangolo, a blocchi: in ordine
 * crescente nel complesso e dentro ogni blocco. Rispecchia forEachTileIdBlock di core/sync (TileIdBlocks.kt).
 * I discendenti di una tile sono contigui sulla curva: a ogni zoom il rettangolo si divide in sottoalberi di 8
 * livelli, visitati nell'ordine di Hilbert e ordinati al loro interno. Per rettangoli come la Siberia
 * (decine di milioni di tile) la lista intera non starebbe in memoria.
 */
internal fun tileIdBlocks(
    minLon: Double, minLat: Double, maxLon: Double, maxLat: Double,
    minZoom: Int, maxZoom: Int,
): Sequence<LongArray> = sequence {
    val buffer = LongArray(TILE_BLOCK_TARGET + (1 shl (2 * SUBTREE_LEVELS)))
    var count = 0
    for (zoom in minZoom..maxZoom) {
        val n = 1 shl zoom
        val minX = lonToTile(minLon, n)
        val maxX = lonToTile(maxLon, n)
        val minY = latToTile(maxLat, n)
        val maxY = latToTile(minLat, n)
        val levels = minOf(zoom, SUBTREE_LEVELS)
        val subtrees = ((minX shr levels)..(maxX shr levels)).flatMap { x ->
            ((minY shr levels)..(maxY shr levels)).map { y -> x to y }
        }.sortedBy { (x, y) -> TileCoord.ofXYZ(x, y, zoom - levels).hilbertEncoded() }
        for ((subtreeX, subtreeY) in subtrees) {
            val start = count
            for (x in maxOf(minX, subtreeX shl levels)..minOf(maxX, ((subtreeX + 1) shl levels) - 1)) {
                for (y in maxOf(minY, subtreeY shl levels)..minOf(maxY, ((subtreeY + 1) shl levels) - 1)) {
                    buffer[count++] = TileCoord.ofXYZ(x, y, zoom).hilbertEncoded()
                }
            }
            // Ogni sottoalbero e' un intervallo contiguo di id: va ordinato solo il suo tratto.
            buffer.sort(start, count)
            if (count >= TILE_BLOCK_TARGET) {
                yield(buffer.copyOf(count))
                count = 0
            }
        }
    }
    if (count > 0) yield(buffer.copyOf(count))
}

internal fun lonToTile(lon: Double, n: Int): Int = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)

internal fun latToTile(lat: Double, n: Int): Int {
    val rad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
    return floor((1.0 - ln(tan(rad) + 1.0 / kotlin.math.cos(rad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
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
