package com.pockettravel.pipeline

import com.onthegomap.planetiler.VectorTile
import com.onthegomap.planetiler.pmtiles.ReadablePmtiles
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import java.io.ByteArrayInputStream
import java.io.File
import java.sql.DriverManager
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Indice di ricerca dei civici per via (addresses-search.db, uno per cella della griglia): una riga
 * per civico che ha una via, cosi' l'app trova "via Roma 12" senza leggere il pmtiles. Il formato
 * (versione 2) e' descritto su [writeAddressSearchDb]. I civici senza via restano solo nel pmtiles.
 */

/**
 * Chiave di ricerca di una via: minuscole (Locale.ROOT), NFD senza segni combinanti (categorie
 * Unicode Mn, Mc, Me), ogni sequenza di caratteri che non sono lettere o cifre
 * (Character.isLetterOrDigit) diventa un solo spazio, niente spazi ai bordi. Il lato app deve
 * applicare lo stesso algoritmo alla query: "Via Dell'Univérsità, 3" -> "via dell universita 3".
 */
fun streetKey(street: String): String {
    val decomposed = Normalizer.normalize(street.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val key = StringBuilder()
    var pendingSpace = false
    var index = 0
    while (index < decomposed.length) {
        val codePoint = decomposed.codePointAt(index)
        index += Character.charCount(codePoint)
        when (Character.getType(codePoint).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> Unit
            else -> if (Character.isLetterOrDigit(codePoint)) {
                if (pendingSpace && key.isNotEmpty()) key.append(' ')
                pendingSpace = false
                key.appendCodePoint(codePoint)
            } else {
                pendingSpace = true
            }
        }
    }
    return key.toString()
}

/** Tratto di strada con nome (una polilinea del layer "roads" delle z15 Protomaps, in microgradi). */
class Road(val name: String, val points: List<Pair<Int, Int>>)

// Distanza massima (m) tra un civico senza via e la strada con nome da cui prende il nome.
const val STREET_MAX_DISTANCE_M = 50.0

// Lato (m) dei riquadri dell'indice spaziale: il doppio della distanza massima, cosi' i 9 riquadri
// attorno a un punto coprono sempre il raggio anche con la scala in longitudine un po' sbagliata.
private const val ROAD_BUCKET_M = 100.0

private class RoadSegment(val name: String, val lat1E6: Int, val lon1E6: Int, val lat2E6: Int, val lon2E6: Int)

/** Strade con nome in una griglia di riquadri, per trovare la piu' vicina a un punto senza scorrerle tutte. */
class RoadIndex(roads: List<Road>) {
    private val buckets = HashMap<Pair<Int, Int>, MutableList<RoadSegment>>()
    private val bucketLatE6 = ROAD_BUCKET_M / 110_540.0 * 1e6
    private var bucketLonE6 = 0.0

    init {
        val referenceLatE6 = roads.firstOrNull()?.points?.firstOrNull()?.first
        if (referenceLatE6 != null) {
            bucketLonE6 = ROAD_BUCKET_M / (111_320.0 * cos(Math.toRadians(referenceLatE6 / 1e6))) * 1e6
            roads.forEach { road ->
                road.points.zipWithNext { a, b ->
                    val segment = RoadSegment(road.name, a.first, a.second, b.first, b.second)
                    val (row1, col1) = bucketOf(min(a.first, b.first), min(a.second, b.second))
                    val (row2, col2) = bucketOf(max(a.first, b.first), max(a.second, b.second))
                    for (row in row1..row2) for (col in col1..col2) buckets.getOrPut(row to col) { mutableListOf() } += segment
                }
            }
        }
    }

    private fun bucketOf(latE6: Int, lonE6: Int): Pair<Int, Int> =
        floor(latE6 / bucketLatE6).toInt() to floor(lonE6 / bucketLonE6).toInt()

    /** Nome della strada piu' vicina a (latE6, lonE6) entro [maxMeters], o null. */
    fun nearestName(latE6: Int, lonE6: Int, maxMeters: Double = STREET_MAX_DISTANCE_M): String? {
        if (buckets.isEmpty()) return null
        val kx = 111_320.0 * cos(Math.toRadians(latE6 / 1e6))
        val ky = 110_540.0
        val (row, col) = bucketOf(latE6, lonE6)
        var bestName: String? = null
        var bestDistance = maxMeters
        for (dr in -1..1) for (dc in -1..1) {
            buckets[(row + dr) to (col + dc)]?.forEach { segment ->
                val distance = distanceToSegmentMeters(latE6, lonE6, segment, kx, ky)
                if (distance <= bestDistance) {
                    bestDistance = distance
                    bestName = segment.name
                }
            }
        }
        return bestName
    }
}

// Distanza punto-segmento in metri, su un piano locale (kx, ky = metri per grado): per qualche
// decina di metri l'errore rispetto alla sfera e' trascurabile.
private fun distanceToSegmentMeters(latE6: Int, lonE6: Int, segment: RoadSegment, kx: Double, ky: Double): Double {
    val ax = (segment.lon1E6 - lonE6) / 1e6 * kx
    val ay = (segment.lat1E6 - latE6) / 1e6 * ky
    val bx = (segment.lon2E6 - lonE6) / 1e6 * kx
    val by = (segment.lat2E6 - latE6) / 1e6 * ky
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    // Proiezione del punto (l'origine) sul segmento, limitata agli estremi.
    val t = if (lengthSquared == 0.0) 0.0 else ((-ax * dx - ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
    return hypot(ax + t * dx, ay + t * dy)
}

/**
 * Via di ogni civico: quella della fonte se c'e', altrimenti il nome della strada piu' vicina entro
 * [STREET_MAX_DISTANCE_M] (nessuna via se non ce n'e' una).
 */
fun assignStreets(addresses: List<Address>, roads: RoadIndex): List<Address> =
    addresses.map { address ->
        val source = address.street?.trim()?.takeIf { it.isNotEmpty() }
        val street = source ?: roads.nearestName(address.latE6, address.lonE6)
        if (street == address.street) address else address.copy(street = street)
    }

// Strade che non sono vie con civici (la ferrovia compare nel layer "roads" di Protomaps).
private val NON_STREET_KINDS = setOf("rail", "ferry")
private val NON_STREET_DETAILS = setOf("runway", "taxiway")

/**
 * Strade con nome delle tile z15 di un PMTiles locale (layer "roads"). Si usa solo l'attributo
 * "name" (la lingua locale): le traduzioni (name:xx) non servono, le vie restano come nei dati.
 */
fun extractRoads(pmtiles: File): List<Road> {
    val roads = mutableListOf<Road>()
    ReadablePmtiles.newReadFromFile(pmtiles.toPath()).use { archive ->
        archive.allTiles.use { tiles ->
            tiles.forEachRemaining { tile ->
                val coord = tile.coord()
                if (coord.z() != ROADS_ZOOM) return@forEachRemaining
                val bytes = tile.bytes()
                val raw = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                    GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
                } else {
                    bytes
                }
                VectorTile.decode(raw).forEach { feature ->
                    if (feature.layer() != "roads") return@forEach
                    val tags = feature.tags()
                    val name = tags["name"]?.toString()?.trim().orEmpty()
                    if (name.isEmpty() || tags["kind"]?.toString() in NON_STREET_KINDS || tags["kind_detail"]?.toString() in NON_STREET_DETAILS) {
                        return@forEach
                    }
                    lineStrings(feature.geometry().decode()).forEach { line ->
                        // decode() restituisce coordinate nella tile scalate su 0..256.
                        val points = (0 until line.numPoints).map { i ->
                            val p = line.getCoordinateN(i)
                            val lon = tileXToLon(coord.x() + p.x / 256.0, ROADS_ZOOM)
                            val lat = tileYToLat(coord.y() + p.y / 256.0, ROADS_ZOOM)
                            Math.round(lat * 1e6).toInt() to Math.round(lon * 1e6).toInt()
                        }
                        if (points.size >= 2) roads += Road(name, points)
                    }
                }
            }
        }
    }
    return roads
}

private const val ROADS_ZOOM = 15

private fun lineStrings(geometry: Geometry): List<LineString> =
    (0 until geometry.numGeometries).map { geometry.getGeometryN(it) }.filterIsInstance<LineString>()

private const val SEARCH_FORMAT = "2"

// Dimensione di pagina dell'indice: con pagine piccole l'xz comprime meglio e la ricerca legge poco.
private const val SEARCH_PAGE_SIZE = 1024

// Confronto dei civici come la collazione NOCASE di SQLite (maiuscole ASCII ripiegate in minuscole,
// byte per byte): l'ordine di inserimento coincide con quello della chiave primaria.
private fun nocase(number: String): String =
    buildString(number.length) { number.forEach { append(if (it in 'A'..'Z') it + 32 else it) } }

/**
 * Scrive in [output] (sostituendolo) l'indice di ricerca dei civici che hanno una via e restituisce
 * quante righe di address ha scritto. Formato 2, pensato per stare in poco spazio:
 *   meta(key, value): format = '2', origin_lat_e6 e origin_lon_e6 (minimi di latE6/lonE6 dei civici)
 *   street(id, name, key, city) con indice street_key: una riga per (via, citta') distinti, id da 1
 *     in ordine (key, name, city)
 *   address(street_id, number, dlat, dlon), WITHOUT ROWID, chiave primaria (street_id, number NOCASE,
 *     dlat, dlon) con dlat/dlon = scarto in microgradi dall'origine (interi piccoli, varint corti)
 * Le righe sono inserite in ordine di chiave e il file e' compattato (VACUUM): lo stesso contenuto da'
 * sempre gli stessi byte, cosi' una cella invariata si riconosce dall'hash.
 */
fun writeAddressSearchDb(addresses: List<Address>, output: File): Int {
    val rows = addresses.mapNotNull { address ->
        val street = address.street?.trim().orEmpty()
        val key = streetKey(street)
        if (key.isEmpty()) null else SearchRow(street, address.number, address.city?.trim()?.takeIf { it.isNotEmpty() }, address, key)
    }
    val streets = rows.map { Triple(it.key, it.street, it.city) }.distinct()
        .sortedWith(compareBy<Triple<String, String, String?>>({ it.first }, { it.second }).thenBy { it.third.orEmpty() })
    val streetIds = streets.withIndex().associate { (index, street) -> street to index + 1 }
    val originLat = rows.minOfOrNull { it.address.latE6 } ?: 0
    val originLon = rows.minOfOrNull { it.address.lonE6 } ?: 0
    // Stessa via, stesso civico (a meno delle maiuscole ASCII) e stessa posizione: una riga sola.
    val entries = rows.map {
        AddressEntry(streetIds.getValue(Triple(it.key, it.street, it.city)), it.number, it.address.latE6 - originLat, it.address.lonE6 - originLon)
    }.sortedWith(compareBy<AddressEntry>({ it.streetId }, { nocase(it.number) }, { it.dlat }, { it.dlon }).thenBy { it.number })
        .distinctBy { listOf(it.streetId, nocase(it.number), it.dlat, it.dlon) }

    output.delete()
    DriverManager.getConnection("jdbc:sqlite:${output.path}").use { conn ->
        // I PRAGMA vanno dati prima di creare le tabelle: la dimensione di pagina si fissa alla prima scrittura.
        conn.createStatement().use { statement ->
            statement.execute("PRAGMA page_size=$SEARCH_PAGE_SIZE")
            statement.execute("PRAGMA journal_mode=DELETE")
        }
        // Una sola transazione per le righe (un commit per riga costerebbe minuti su una cella grande);
        // il VACUUM, che non puo' stare in una transazione, viene dopo.
        conn.autoCommit = false
        conn.createStatement().use { statement ->
            statement.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID")
            statement.execute("INSERT INTO meta (key, value) VALUES ('format', '$SEARCH_FORMAT')")
            statement.execute("INSERT INTO meta (key, value) VALUES ('origin_lat_e6', '$originLat')")
            statement.execute("INSERT INTO meta (key, value) VALUES ('origin_lon_e6', '$originLon')")
            statement.execute("CREATE TABLE street (id INTEGER PRIMARY KEY, name TEXT NOT NULL, key TEXT NOT NULL, city TEXT)")
            statement.execute(
                "CREATE TABLE address (street_id INTEGER NOT NULL, number TEXT NOT NULL COLLATE NOCASE, " +
                    "dlat INTEGER NOT NULL, dlon INTEGER NOT NULL, PRIMARY KEY (street_id, number, dlat, dlon)) WITHOUT ROWID",
            )
        }
        conn.prepareStatement("INSERT INTO street (id, name, key, city) VALUES (?, ?, ?, ?)").use { insert ->
            streets.forEachIndexed { index, (key, name, city) ->
                insert.setInt(1, index + 1)
                insert.setString(2, name)
                insert.setString(3, key)
                insert.setString(4, city)
                insert.addBatch()
            }
            insert.executeBatch()
        }
        conn.createStatement().use { it.execute("CREATE INDEX street_key ON street(key)") }
        conn.prepareStatement("INSERT INTO address (street_id, number, dlat, dlon) VALUES (?, ?, ?, ?)").use { insert ->
            entries.forEachIndexed { index, entry ->
                insert.setInt(1, entry.streetId)
                insert.setString(2, entry.number)
                insert.setInt(3, entry.dlat)
                insert.setInt(4, entry.dlon)
                insert.addBatch()
                if ((index + 1) % 10_000 == 0) insert.executeBatch()
            }
            insert.executeBatch()
        }
        conn.commit()
        conn.autoCommit = true
        conn.createStatement().use { it.execute("VACUUM") }
    }
    return entries.size
}

private class SearchRow(val street: String, val number: String, val city: String?, val address: Address, val key: String)

private class AddressEntry(val streetId: Int, val number: String, val dlat: Int, val dlon: Int)
