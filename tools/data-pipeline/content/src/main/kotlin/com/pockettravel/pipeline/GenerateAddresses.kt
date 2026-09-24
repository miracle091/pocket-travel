package com.pockettravel.pipeline

import com.onthegomap.planetiler.VectorTile
import com.onthegomap.planetiler.archive.TileArchiveMetadata
import com.onthegomap.planetiler.archive.TileCompression
import com.onthegomap.planetiler.archive.TileEncodingResult
import com.onthegomap.planetiler.archive.TileFormat
import com.onthegomap.planetiler.geo.TileCoord
import com.onthegomap.planetiler.pmtiles.ReadablePmtiles
import com.onthegomap.planetiler.pmtiles.WriteablePmtiles
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.OptionalLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point

/**
 * Numeri civici della regione, estratti dalle sole tile z15 del basemap Protomaps (layer
 * "buildings", kind=address, attributo addr_housenumber: lo schema li include solo a z15). Si
 * tengono solo punto e numero, non le tile intere: per il Lussemburgo ~193 mila indirizzi pesano
 * pochi MB invece dei +31 MB che costerebbe portare tutta la mappa a z15. L'app li disegna sopra
 * la mappa da zoom 17.
 *
 * Coordinate in microgradi interi (latE6/lonE6): la precisione (~11 cm) e' molto oltre quella che
 * serve per un'etichetta, e il confronto tra indirizzi duplicati resta esatto.
 */
data class Address(val latE6: Int, val lonE6: Int, val number: String)

private const val ADDRESS_ZOOM = 15

/** Estrae gli indirizzi da un archivio PMTiles locale (una o piu' tile z15), tenendo solo quelli nel bbox. */
fun extractAddresses(pmtiles: File, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): List<Address> {
    val addresses = mutableListOf<Address>()
    ReadablePmtiles.newReadFromFile(pmtiles.toPath()).use { archive ->
        archive.getAllTiles().use { tiles ->
            tiles.forEachRemaining { tile ->
                val coord = tile.coord()
                if (coord.z() != ADDRESS_ZOOM) return@forEachRemaining
                VectorTile.decode(gunzipIfNeeded(tile.bytes())).forEach { feature ->
                    if (feature.layer() != "buildings" || feature.attrs()["kind"] != "address") return@forEach
                    val number = feature.attrs()["addr_housenumber"]?.toString()?.trim().orEmpty()
                    if (number.isEmpty()) return@forEach
                    val point = feature.geometry().decode() as? Point ?: return@forEach
                    // decode() restituisce coordinate nella tile scalate su 0..256.
                    val lon = tileXToLon(coord.x() + point.x / 256.0, ADDRESS_ZOOM)
                    val lat = tileYToLat(coord.y() + point.y / 256.0, ADDRESS_ZOOM)
                    if (lon in minLon..maxLon && lat in minLat..maxLat) {
                        addresses += Address((lat * 1e6).roundToInt(), (lon * 1e6).roundToInt(), number)
                    }
                }
            }
        }
    }
    return addresses
}

private fun gunzipIfNeeded(bytes: ByteArray): ByteArray =
    if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    } else {
        bytes
    }

internal fun tileXToLon(x: Double, zoom: Int): Double = x / (1 shl zoom) * 360.0 - 180.0

internal fun tileYToLat(y: Double, zoom: Int): Double {
    val n = PI - 2.0 * PI * y / (1 shl zoom)
    return Math.toDegrees(atan(sinh(n)))
}

private const val OUTPUT_ZOOM = 14
private const val LAYER = "addresses"

/**
 * Scrive i civici in un PMTiles di soli punti, tutti a z[OUTPUT_ZOOM] (layer "addresses",
 * attributo "number", tile gzip): la mappa dell'app si ferma a z14 e MapLibre sovrazooma queste
 * tile fino allo zoom a cui mostra i civici, senza scaricare le z15 della mappa.
 */
fun writeAddressesPmtiles(addresses: List<Address>, output: File, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) {
    val byTile = addresses.groupBy { tileOf(it) }
    output.delete()
    WriteablePmtiles.newWriteToFile(output.toPath()).use { archive ->
        archive.initialize()
        archive.newTileWriter().use { writer ->
            // Il writer PMTiles vuole le tile nell'ordine della sua curva (Hilbert).
            byTile.entries.sortedBy { archive.tileOrder().encode(it.key) }.forEach { (coord, points) ->
                val features = points.mapIndexed { index, address ->
                    VectorTile.Feature(LAYER, index.toLong(), VectorTile.encodeGeometry(pointInTile(address, coord)), mapOf("number" to address.number))
                }
                val tile = VectorTile().addLayerFeatures(LAYER, features).encode()
                writer.write(TileEncodingResult(coord, gzip(tile), OptionalLong.empty()))
            }
        }
        archive.finish(
            TileArchiveMetadata(
                "Pocket Travel addresses", null, "© OpenStreetMap contributors", null, "overlay",
                TileFormat.MVT, Envelope(minLon, maxLon, minLat, maxLat), null, OUTPUT_ZOOM, OUTPUT_ZOOM,
                null, emptyMap(), TileCompression.GZIP,
            ),
        )
    }
}

private fun tileOf(address: Address): TileCoord {
    val (x, y) = worldPixel(address)
    return TileCoord.ofXYZ(x.toInt(), y.toInt(), OUTPUT_ZOOM)
}

// Coordinate della tile a z[OUTPUT_ZOOM] come numero reale (parte intera = tile, resto = posizione).
private fun worldPixel(address: Address): Pair<Double, Double> {
    val n = (1 shl OUTPUT_ZOOM).toDouble()
    val lon = address.lonE6 / 1e6
    val latRad = Math.toRadians(address.latE6 / 1e6)
    val x = (lon + 180.0) / 360.0 * n
    val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
    return x to y
}

// Punto nelle coordinate della tile 0..256, le stesse che VectorTile.encodeGeometry si aspetta.
private fun pointInTile(address: Address, coord: TileCoord): Point {
    val (x, y) = worldPixel(address)
    return GeometryFactory().createPoint(Coordinate((x - coord.x()) * 256.0, (y - coord.y()) * 256.0))
}

private fun gzip(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

fun main(args: Array<String>) {
    require(args.size >= 6) {
        "Uso: generateAddresses <output.pmtiles> <minLon> <minLat> <maxLon> <maxLat> <z15-1.pmtiles> [z15-2.pmtiles ...]"
    }
    val output = File(args[0])
    val (minLon, minLat, maxLon, maxLat) = args.slice(1..4).map { it.toDouble() }
    // Con piu' estratti (riquadri adiacenti) un indirizzo sul bordo compare in entrambi.
    val addresses = args.drop(5).map(::File)
        .flatMap { extractAddresses(it, minLon, minLat, maxLon, maxLat) }
        .distinct()
    writeAddressesPmtiles(addresses, output, minLon, minLat, maxLon, maxLat)
    println("indirizzi: ${addresses.size} scritti in ${output.path}")
}
