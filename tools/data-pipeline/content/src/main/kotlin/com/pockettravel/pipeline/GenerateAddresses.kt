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
import kotlin.math.floor
import kotlin.math.hypot
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

/**
 * Civici gia' come punti, una riga "lat<TAB>lon<TAB>numero" (fonte di riserva Overpass di
 * build-address-cell.sh, per le celle troppo grandi da estrarre dalle z15), tenendo solo quelli nel bbox.
 */
fun readAddressPoints(points: File, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): List<Address> =
    points.readLines().mapNotNull { line ->
        val fields = line.split('\t')
        val lat = fields.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
        val lon = fields.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
        val number = fields.getOrNull(2)?.trim().orEmpty()
        if (number.isEmpty() || lon !in minLon..maxLon || lat !in minLat..maxLat) return@mapNotNull null
        Address((lat * 1e6).roundToInt(), (lon * 1e6).roundToInt(), number)
    }

/**
 * Punto Overture del tema addresses (griglia adattiva, vedi address-grid-plan.md): stessa forma di
 * [Address] piu' il dataset di provenienza (sources[1].dataset della query DuckDB), che serve solo a
 * chi genera i punti (lista bianca in overture-address-sources.tsv) - qui non e' piu' necessario,
 * separato da Address per non confondere le due fonti nella deduplica.
 */
data class OvertureAddress(val latE6: Int, val lonE6: Int, val number: String, val dataset: String)

/**
 * Punti Overture, una riga "lat<TAB>lon<TAB>numero<TAB>dataset" (query DuckDB del tema addresses,
 * vedi scripts/overture_addresses.py), tenendo solo quelli nel bbox.
 */
fun readOvertureAddressPoints(points: File, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): List<OvertureAddress> =
    points.readLines().mapNotNull { line ->
        val fields = line.split('\t')
        val lat = fields.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
        val lon = fields.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
        val number = fields.getOrNull(2)?.trim().orEmpty()
        val dataset = fields.getOrNull(3)?.trim().orEmpty()
        if (number.isEmpty() || lon !in minLon..maxLon || lat !in minLat..maxLat) return@mapNotNull null
        OvertureAddress((lat * 1e6).roundToInt(), (lon * 1e6).roundToInt(), number, dataset)
    }

private fun normalizedNumber(number: String): String = number.trim().lowercase().filterNot { it.isWhitespace() }

// Chiave della deduplica (a): punto arrotondato a ~1 m (latE6/lonE6 sono gia' a ~0,11 m, arrotondare
// alla decina li porta a ~1,1 m) + numero normalizzato - vedi overture-and-map-diff-research.md.
private fun roundedPointKey(latE6: Int, lonE6: Int, number: String): Triple<Int, Int, String> =
    Triple((latE6 / 10) * 10, (lonE6 / 10) * 10, normalizedNumber(number))

private const val DEDUP_RADIUS_M = 30.0
private const val DEDUP_GRID_CELL_M = 30.0

private fun metersPerDegree(latE6: Int): Pair<Double, Double> {
    val kx = 111_320.0 * cos(Math.toRadians(latE6 / 1e6))
    val ky = 110_540.0
    return kx to ky
}

private fun dedupGridKey(latE6: Int, lonE6: Int): Pair<Int, Int> {
    val (kx, ky) = metersPerDegree(latE6)
    return floor(lonE6 / 1e6 * kx / DEDUP_GRID_CELL_M).toInt() to floor(latE6 / 1e6 * ky / DEDUP_GRID_CELL_M).toInt()
}

/**
 * Deduplica indirizzi OSM + Overture (vedi address-grid-plan.md, passo 1 della pipeline):
 * (a) distinct per fonte su punto arrotondato a ~1 m + numero normalizzato (toglie i doppioni
 * interni, es. il catasto portoghese con piu' righe sullo stesso punto);
 * (b) un punto Overture si scarta se entro 30 m c'e' un civico OSM con lo stesso numero normalizzato
 * (priorita' a OSM, unica fonte con licenza ODbL gia' accettata per l'intera mappa).
 * Non filtra per cella: quello e' un passo successivo (vedi [addressCellContains]).
 */
fun dedupeWithOverture(osm: List<Address>, overture: List<OvertureAddress>): List<Address> {
    val osmDistinct = osm.distinctBy { roundedPointKey(it.latE6, it.lonE6, it.number) }
    val overtureDistinct = overture.distinctBy { roundedPointKey(it.latE6, it.lonE6, it.number) }
    val osmGrid = osmDistinct.groupBy { dedupGridKey(it.latE6, it.lonE6) }
    val newOverture = overtureDistinct.filterNot { candidate ->
        val (gx, gy) = dedupGridKey(candidate.latE6, candidate.lonE6)
        val number = normalizedNumber(candidate.number)
        val (kx, ky) = metersPerDegree(candidate.latE6)
        (-1..1).any { dx ->
            (-1..1).any { dy ->
                osmGrid[(gx + dx) to (gy + dy)].orEmpty().any { osmAddress ->
                    normalizedNumber(osmAddress.number) == number &&
                        hypot(
                            (candidate.lonE6 - osmAddress.lonE6) / 1e6 * kx,
                            (candidate.latE6 - osmAddress.latE6) / 1e6 * ky,
                        ) <= DEDUP_RADIUS_M
                }
            }
        }
    }
    return osmDistinct + newOverture.map { Address(it.latE6, it.lonE6, it.number) }
}

/** Id di una cella della griglia adattiva (vedi address-grid-plan.md): nodo z/x/y del quadtree Web Mercator. */
data class CellId(val z: Int, val x: Int, val y: Int) {
    override fun toString(): String = "$z/$x/$y"
}

fun parseCellId(spec: String): CellId {
    val parts = spec.split('/')
    require(parts.size == 3) { "id di cella non valido: $spec" }
    return CellId(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
}

/** true se la tile z14 di [address] (la stessa griglia del pmtiles scritto da [writeAddressesPmtiles]) e' discendente di [cell]. */
internal fun addressCellContains(address: Address, cell: CellId): Boolean {
    val (x, y) = worldPixel(address)
    val shift = OUTPUT_ZOOM - cell.z
    return (x.toInt() shr shift) == cell.x && (y.toInt() shr shift) == cell.y
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
    // worldPixel calcolato una volta per civico: serve sia per la tile sia per il punto dentro la tile.
    val byTile = addresses.map { it to worldPixel(it) }.groupBy { (_, pixel) -> tileOf(pixel) }
    output.delete()
    WriteablePmtiles.newWriteToFile(output.toPath()).use { archive ->
        archive.initialize()
        archive.newTileWriter().use { writer ->
            // Il writer PMTiles vuole le tile nell'ordine della sua curva (Hilbert).
            byTile.entries.sortedBy { archive.tileOrder().encode(it.key) }.forEach { (coord, points) ->
                val features = points.mapIndexed { index, (address, pixel) ->
                    VectorTile.Feature(LAYER, index.toLong(), VectorTile.encodeGeometry(pointInTile(pixel, coord)), mapOf("number" to address.number))
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

private fun tileOf(pixel: Pair<Double, Double>): TileCoord {
    val (x, y) = pixel
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

private val geometryFactory = GeometryFactory()

// Punto nelle coordinate della tile 0..256, le stesse che VectorTile.encodeGeometry si aspetta.
private fun pointInTile(pixel: Pair<Double, Double>, coord: TileCoord): Point {
    val (x, y) = pixel
    return geometryFactory.createPoint(Coordinate((x - coord.x()) * 256.0, (y - coord.y()) * 256.0))
}

private fun gzip(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

fun main(args: Array<String>) {
    require(args.size >= 6) {
        "Uso: generateAddresses <output.pmtiles> <minLon> <minLat> <maxLon> <maxLat> <z15-1.pmtiles | punti.tsv> [...] " +
            "--cell <z/x/y> [--overture <overture.tsv>]"
    }
    val output = File(args[0])
    val (minLon, minLat, maxLon, maxLat) = args.slice(1..4).map { it.toDouble() }
    var overtureFile: File? = null
    var cell: CellId? = null
    val inputPaths = mutableListOf<String>()
    var i = 5
    while (i < args.size) {
        when (args[i]) {
            "--overture" -> { overtureFile = File(args[i + 1]); i += 2 }
            "--cell" -> { cell = parseCellId(args[i + 1]); i += 2 }
            else -> { inputPaths += args[i]; i += 1 }
        }
    }
    val cellId = requireNotNull(cell) { "--cell <z/x/y> obbligatorio: ogni build scrive i civici di una cella della griglia (vedi address-grid-plan.md)" }
    // Con piu' estratti (riquadri adiacenti) un indirizzo sul bordo compare in entrambi.
    val osmAddresses = inputPaths.map(::File)
        .flatMap {
            if (it.name.endsWith(".tsv")) readAddressPoints(it, minLon, minLat, maxLon, maxLat) else extractAddresses(it, minLon, minLat, maxLon, maxLat)
        }
        .distinct()
    val overtureAddresses = overtureFile?.let { readOvertureAddressPoints(it, minLon, minLat, maxLon, maxLat) }.orEmpty()
    val deduped = if (overtureAddresses.isEmpty()) osmAddresses else dedupeWithOverture(osmAddresses, overtureAddresses)
    val addresses = deduped.filter { address -> addressCellContains(address, cellId) }
    writeAddressesPmtiles(addresses, output, minLon, minLat, maxLon, maxLat)
    println(
        "indirizzi: ${addresses.size} scritti in ${output.path} " +
            "(osm=${osmAddresses.size}, overture=${overtureAddresses.size}, dopo deduplica=${deduped.size})",
    )
}
