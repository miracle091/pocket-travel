package com.pockettravel.pipeline

import com.onthegomap.planetiler.geo.TileCoord
import com.onthegomap.planetiler.pmtiles.Pmtiles
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Impronta delle tile di un riquadro in un archivio PMTiles (la build Protomaps da cui l'app estrae
 * la mappa): SHA-256 di id e lunghezza di ogni tile z[minZoom]-z[maxZoom] che interseca il riquadro.
 * Si leggono solo intestazione e indici (poche richieste HTTP a intervalli), non le tile: se una
 * tile cambia, cambia quasi sempre anche la sua lunghezza compressa. build-region.sh la confronta
 * con quella pubblicata per dare una versione nuova alla mappa solo quando e' davvero cambiata.
 */
fun interface RangeReader {
    fun read(offset: Long, length: Int): ByteArray
}

fun httpRangeReader(url: String): RangeReader {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    return RangeReader { offset, length ->
        val request = HttpRequest.newBuilder(URI(url))
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .header("User-Agent", "PocketTravelDataPipeline/1.0 (https://github.com/miracle091/pocket-travel)")
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() == 206 || response.statusCode() == 200) { "HTTP ${response.statusCode()} leggendo $url" }
        response.body()
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
    val needed = tileIdsIn(minLon, minLat, maxLon, maxLat, minZoom, maxZoom)
    val digest = MessageDigest.getInstance("SHA-256")
    var tiles = 0

    fun directory(offset: Long, length: Long): List<Pmtiles.Entry> {
        val bytes = reader.read(offset, length.toInt())
        val raw = if (header.internalCompression() == Pmtiles.Compression.GZIP) GZIPInputStream(bytes.inputStream()).readBytes() else bytes
        return Pmtiles.directoryFromBytes(raw)
    }

    // Tile nell'intervallo [from, to) di una voce: quelle cercate, in ordine di id.
    fun neededIn(from: Long, to: Long): IntRange {
        val start = needed.binarySearch(from).let { if (it < 0) -it - 1 else it }
        val end = needed.binarySearch(to).let { if (it < 0) -it - 1 else it }
        return start until end
    }

    fun walk(entries: List<Pmtiles.Entry>, end: Long) {
        entries.forEachIndexed { index, entry ->
            val next = entries.getOrNull(index + 1)?.tileId() ?: end
            if (entry.runLength() == 0) {
                // Indice foglia: si scarica solo se copre almeno una tile del riquadro.
                if (!neededIn(entry.tileId(), next).isEmpty()) {
                    walk(directory(header.leafDirectoriesOffset() + entry.offset(), entry.length().toLong()), next)
                }
            } else {
                for (i in neededIn(entry.tileId(), entry.tileId() + entry.runLength())) {
                    digest.update(longBytes(needed[i]))
                    digest.update(longBytes(entry.length().toLong()))
                    tiles++
                }
            }
        }
    }

    walk(directory(header.rootDirOffset(), header.rootDirLength()), Long.MAX_VALUE)
    return MapFingerprintResult(digest.digest().joinToString("") { "%02x".format(it) }, tiles)
}

/** Id (curva di Hilbert, come negli indici PMTiles) delle tile che intersecano il riquadro, ordinati. */
internal fun tileIdsIn(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, minZoom: Int, maxZoom: Int): LongArray {
    // Riempito senza boxing: allo z14 un riquadro come il Nunavut ha milioni di tile.
    val ranges = (minZoom..maxZoom).map { z ->
        val n = 1 shl z
        intArrayOf(z, lonToTile(minLon, n), lonToTile(maxLon, n), latToTile(maxLat, n), latToTile(minLat, n))
    }
    val ids = LongArray(ranges.sumOf { (_, x0, x1, y0, y1) -> (x1 - x0 + 1).toLong() * (y1 - y0 + 1) }.toInt())
    var i = 0
    for ((z, x0, x1, y0, y1) in ranges) for (x in x0..x1) for (y in y0..y1) ids[i++] = TileCoord.ofXYZ(x, y, z).hilbertEncoded()
    return ids.also { it.sort() }
}

private fun lonToTile(lon: Double, n: Int): Int = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)

private fun latToTile(lat: Double, n: Int): Int {
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
