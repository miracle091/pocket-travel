package com.pockettravel.pipeline

import com.onthegomap.planetiler.VectorTile
import com.onthegomap.planetiler.pmtiles.ReadablePmtiles
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sinh
import org.locationtech.jts.geom.Point

/**
 * Numeri civici della regione, estratti dalle sole tile z15 del basemap Protomaps (layer
 * "buildings", kind=address, attributo addr_housenumber: lo schema li include solo a z15). Si
 * tengono solo punto e numero, non le tile intere: per il Lussemburgo ~193 mila indirizzi pesano
 * pochi MB invece dei +31 MB che costerebbe portare tutta la mappa a z15. L'app li disegna sopra
 * la mappa da zoom 17.
 *
 * Coordinate in microgradi interi (latE6/lonE6): in SQLite occupano meno di due REAL e la
 * precisione (~11 cm) e' molto oltre quella che serve per un'etichetta.
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

fun writeAddressesDb(addresses: List<Address>, outputDb: File) {
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "addresses",
        createTableSql = "CREATE TABLE addresses (latE6 INTEGER NOT NULL, lonE6 INTEGER NOT NULL, number TEXT NOT NULL)",
        insertSql = "INSERT INTO addresses (latE6, lonE6, number) VALUES (?, ?, ?)",
        rows = addresses,
    ) { statement, address ->
        statement.setInt(1, address.latE6)
        statement.setInt(2, address.lonE6)
        statement.setString(3, address.number)
    }
}

fun main(args: Array<String>) {
    require(args.size >= 6) {
        "Uso: generateAddresses <output content.db> <minLon> <minLat> <maxLon> <maxLat> <z15-1.pmtiles> [z15-2.pmtiles ...]"
    }
    val outputDb = File(args[0])
    val (minLon, minLat, maxLon, maxLat) = args.slice(1..4).map { it.toDouble() }
    // build-region.sh estrae la regione a riquadri (un file per riquadro): un indirizzo sul
    // bordo tra due riquadri compare in entrambi, da qui il distinct.
    val addresses = args.drop(5).map(::File)
        .flatMap { extractAddresses(it, minLon, minLat, maxLon, maxLat) }
        .distinct()
    writeAddressesDb(addresses, outputDb)
    println("indirizzi: ${addresses.size} scritti in ${outputDb.path}")
}
