package com.pockettravel.pipeline

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.sql.DriverManager
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/**
 * Converte un feed GTFS (zip) in transit.db, il pacchetto degli orari di una rete per il tabellone
 * delle partenze dell'app. Schema "calendario" della ricerca GTFS del 2026-09-27 (circa il 14%
 * dello zip, compresso con xz): i giorni di servizio sono una maschera di bit su una finestra di
 * [windowDays] giorni da [windowStart], le corse si tengono una volta sola, non una riga per
 * partenza (lo schema "espanso" pesava anche dieci volte tanto).
 *
 * - meta(key, value): feed_id, format, timezone (agency_timezone: gli orari sono nell'ora locale
 *   della rete), window_start e valid_until (AAAAMMGG), window_days;
 * - stop(id, code, name, latE6, lonE6, parent): fermate e stazioni (location_type 0 e 1), parent =
 *   la stazione di una banchina, per raggruppare le partenze della stessa stazione;
 * - route(id, short_name, long_name, type, color, text_color): route_type GTFS (0 tram, 1 metro,
 *   2 treno, 3 bus, 4 traghetto...);
 * - headsign(id, text): destinazioni, condivise tra le corse;
 * - service(id, days): days = bit i (byte i/8, bit i%8) acceso se il servizio c'e' il giorno
 *   window_start + i;
 * - trip(id, route, service, headsign): solo le corse con almeno un giorno nella finestra;
 * - stop_time(stop, minute, trip), chiave (stop, minute, trip) senza rowid: minuti dalla mezzanotte
 *   del giorno di servizio (oltre 1440 dopo mezzanotte, come in GTFS). Solo le partenze: niente
 *   capolinea d'arrivo (ultima fermata della corsa) ne' fermate con pickup_type = 1.
 *
 * Non gestiti (assenti nei feed misurati): frequencies.txt, shapes.txt, tariffe.
 */
data class TransitStats(val stops: Int, val routes: Int, val trips: Int, val stopTimes: Int, val validUntil: LocalDate?, val bbox: DoubleArray?)

private val GTFS_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

fun generateTransit(gtfsZip: File, outputDb: File, feedId: String, windowStart: LocalDate, windowDays: Int): TransitStats {
    require(windowDays in 1..366) { "windowDays fuori intervallo: $windowDays" }
    outputDb.delete()
    ZipFile(gtfsZip).use { zip ->
        fun rows(name: String, onRow: (Map<String, String>) -> Unit): Boolean {
            // Alcuni feed mettono i file in una sottocartella dello zip.
            val entry = zip.getEntry(name) ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("/$name") } ?: return false
            BufferedReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8), 1 shl 16).use { reader -> readCsv(reader, onRow) }
            return true
        }

        // Servizi: giorni attivi nella finestra, da calendar.txt e calendar_dates.txt.
        val services = HashMap<String, BooleanArray>()
        rows("calendar.txt") { r ->
            val start = LocalDate.parse(r.getValue("start_date"), GTFS_DATE)
            val end = LocalDate.parse(r.getValue("end_date"), GTFS_DATE)
            val weekdays = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").map { r[it] == "1" }
            val days = services.getOrPut(r.getValue("service_id")) { BooleanArray(windowDays) }
            for (i in 0 until windowDays) {
                val day = windowStart.plusDays(i.toLong())
                if (!day.isBefore(start) && !day.isAfter(end) && weekdays[day.dayOfWeek.value - 1]) days[i] = true
            }
        }
        rows("calendar_dates.txt") { r ->
            val day = LocalDate.parse(r.getValue("date"), GTFS_DATE)
            val i = java.time.temporal.ChronoUnit.DAYS.between(windowStart, day)
            if (i !in 0 until windowDays) return@rows
            val days = services.getOrPut(r.getValue("service_id")) { BooleanArray(windowDays) }
            when (r["exception_type"]) {
                "1" -> days[i.toInt()] = true
                "2" -> days[i.toInt()] = false
            }
        }
        val activeServices = services.filterValues { days -> days.any { it } }
        val serviceIds = activeServices.keys.sorted().withIndex().associate { (i, id) -> id to i }
        val lastActiveDay = activeServices.values.maxOfOrNull { days -> days.indexOfLast { it } }?.takeIf { it >= 0 }

        var timezone = ""
        rows("agency.txt") { r -> if (timezone.isEmpty()) timezone = r["agency_timezone"].orEmpty() }

        data class Stop(val id: Int, val code: String?, val name: String, val lat: Double, val lon: Double, val parent: String?)
        val stops = LinkedHashMap<String, Stop>()
        rows("stops.txt") { r ->
            val type = r["location_type"].orEmpty().ifEmpty { "0" }
            if (type != "0" && type != "1") return@rows
            val lat = r["stop_lat"]?.toDoubleOrNull() ?: return@rows
            val lon = r["stop_lon"]?.toDoubleOrNull() ?: return@rows
            val id = r.getValue("stop_id")
            // Id ripetuto nel feed: vale la prima riga (con size calcolata prima del put due righe avrebbero lo stesso id).
            if (id in stops) return@rows
            stops[id] = Stop(stops.size, r["stop_code"]?.ifEmpty { null }, r["stop_name"].orEmpty(), lat, lon, r["parent_station"]?.ifEmpty { null })
        }

        data class Route(val id: Int, val shortName: String?, val longName: String?, val type: Int, val color: String?, val textColor: String?)
        val routes = LinkedHashMap<String, Route>()
        rows("routes.txt") { r ->
            if (r.getValue("route_id") in routes) return@rows
            routes[r.getValue("route_id")] = Route(
                routes.size, r["route_short_name"]?.ifEmpty { null }, r["route_long_name"]?.ifEmpty { null },
                r["route_type"]?.toIntOrNull() ?: 3, r["route_color"]?.ifEmpty { null }, r["route_text_color"]?.ifEmpty { null },
            )
        }

        data class Trip(val id: Int, val route: Int, val service: Int, var headsign: String?)
        val trips = HashMap<String, Trip>()
        rows("trips.txt") { r ->
            val service = serviceIds[r.getValue("service_id")] ?: return@rows
            val route = routes[r.getValue("route_id")]?.id ?: return@rows
            if (r.getValue("trip_id") in trips) return@rows
            trips[r.getValue("trip_id")] = Trip(trips.size, route, service, r["trip_headsign"]?.ifEmpty { null })
        }

        DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { s ->
                s.execute("PRAGMA page_size = 4096")
                s.execute("CREATE TABLE meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                s.execute("CREATE TABLE stop (id INTEGER PRIMARY KEY, code TEXT, name TEXT NOT NULL, latE6 INTEGER NOT NULL, lonE6 INTEGER NOT NULL, parent INTEGER)")
                s.execute("CREATE TABLE route (id INTEGER PRIMARY KEY, short_name TEXT, long_name TEXT, type INTEGER NOT NULL, color TEXT, text_color TEXT)")
                s.execute("CREATE TABLE headsign (id INTEGER PRIMARY KEY, text TEXT NOT NULL)")
                s.execute("CREATE TABLE service (id INTEGER PRIMARY KEY, days BLOB NOT NULL)")
                // Formato 2: le corse con le stesse fermate agli stessi intervalli condividono un "pattern"
                // (pattern_stop: fermata e minuti dalla partenza della corsa); ogni corsa ha solo pattern e
                // minuto di partenza. Svizzera: 15,3 milioni di righe stop_time del formato 1 contro 1,2
                // milioni di pattern_stop, 248 MB contro 45 (27 MB contro 6,8 compressi), stesse partenze.
                // Ordinata per (pattern, partenza): la tabella stessa fa da indice per le partenze di una fermata.
                s.execute("CREATE TABLE trip (pattern INTEGER NOT NULL, start INTEGER NOT NULL, id INTEGER NOT NULL, route INTEGER NOT NULL, service INTEGER NOT NULL, headsign INTEGER, PRIMARY KEY (pattern, start, id)) WITHOUT ROWID")
                // Temporanea: con la sequenza, per togliere l'ultima fermata di ogni corsa e ricavare la destinazione.
                s.execute("CREATE TEMP TABLE raw_time (trip INTEGER NOT NULL, seq INTEGER NOT NULL, stop INTEGER NOT NULL, minute INTEGER NOT NULL, pickup INTEGER NOT NULL)")
            }

            // stop_times.txt e' il file grande (milioni di righe): in streaming, a lotti.
            var pending = 0
            conn.prepareStatement("INSERT INTO raw_time (trip, seq, stop, minute, pickup) VALUES (?, ?, ?, ?, ?)").use { insert ->
                rows("stop_times.txt") { r ->
                    val trip = trips[r.getValue("trip_id")] ?: return@rows
                    val stop = stops[r.getValue("stop_id")] ?: return@rows
                    val minute = gtfsMinutes(r["departure_time"].orEmpty().ifEmpty { r["arrival_time"].orEmpty() }) ?: return@rows
                    insert.setInt(1, trip.id)
                    insert.setInt(2, r["stop_sequence"]?.toIntOrNull() ?: 0)
                    insert.setInt(3, stop.id)
                    insert.setInt(4, minute)
                    insert.setInt(5, if (r["pickup_type"] == "1") 0 else 1)
                    insert.addBatch()
                    if (++pending % 20_000 == 0) insert.executeBatch()
                }
                insert.executeBatch()
            }

            // Destinazione mancante: il nome dell'ultima fermata della corsa.
            val stopNames = stops.values.associate { it.id to it.name }
            val tripsById = trips.values.associateBy { it.id }
            conn.createStatement().use { s ->
                s.executeQuery(
                    "SELECT r.trip, r.stop FROM raw_time r JOIN (SELECT trip, MAX(seq) AS seq FROM raw_time GROUP BY trip) l ON r.trip = l.trip AND r.seq = l.seq",
                ).use { rs ->
                    while (rs.next()) {
                        val trip = tripsById.getValue(rs.getInt(1))
                        if (trip.headsign == null) trip.headsign = stopNames[rs.getInt(2)]
                    }
                }
            }
            val headsigns = trips.values.mapNotNull { it.headsign }.distinct().withIndex().associate { (i, text) -> text to i }

            fun <T> insertAll(sql: String, items: Collection<T>, bind: java.sql.PreparedStatement.(T) -> Unit) =
                conn.prepareStatement(sql).use { st ->
                    items.forEach { st.bind(it); st.addBatch() }
                    st.executeBatch()
                }
            insertAll("INSERT INTO stop VALUES (?, ?, ?, ?, ?, ?)", stops.values) { stop ->
                setInt(1, stop.id); setString(2, stop.code); setString(3, stop.name)
                setInt(4, (stop.lat * 1_000_000).roundToInt()); setInt(5, (stop.lon * 1_000_000).roundToInt())
                val parent = stop.parent?.let { stops[it]?.id }
                if (parent != null) setInt(6, parent) else setNull(6, java.sql.Types.INTEGER)
            }
            insertAll("INSERT INTO route VALUES (?, ?, ?, ?, ?, ?)", routes.values) { route ->
                setInt(1, route.id); setString(2, route.shortName); setString(3, route.longName); setInt(4, route.type)
                setString(5, route.color); setString(6, route.textColor)
            }
            insertAll("INSERT INTO headsign VALUES (?, ?)", headsigns.entries) { (text, id) -> setInt(1, id); setString(2, text) }
            insertAll("INSERT INTO service VALUES (?, ?)", serviceIds.entries) { (serviceId, id) -> setInt(1, id); setBytes(2, dayBits(activeServices.getValue(serviceId))) }
            // Pattern di ogni corsa: le fermate dove si sale (non il capolinea d'arrivo, non pickup_type=1)
            // con i minuti dalla prima; le corse senza fermate utili non danno partenze e non si scrivono.
            val patterns = HashMap<List<Int>, Int>()
            val tripPattern = HashMap<Int, IntArray>()
            var departures = 0
            conn.createStatement().use { s ->
                s.executeQuery(
                    """SELECT r.trip, r.stop, r.minute FROM raw_time r
                       JOIN (SELECT trip, MAX(seq) AS seq FROM raw_time GROUP BY trip) l ON r.trip = l.trip
                       WHERE r.seq < l.seq AND r.pickup = 1 ORDER BY r.trip, r.seq""",
                ).use { rs ->
                    var currentTrip = -1
                    val calls = ArrayList<Int>()
                    fun flush() {
                        if (currentTrip < 0 || calls.isEmpty()) return
                        val start = calls[1]
                        // Chiave: fermata e minuti dalla partenza, alternati; la stessa fermata allo stesso minuto conta una volta.
                        val key = calls.chunked(2).map { (stop, minute) -> stop to minute - start }.distinct().flatMap { listOf(it.first, it.second) }
                        departures += key.size / 2
                        tripPattern[currentTrip] = intArrayOf(patterns.getOrPut(key) { patterns.size }, start)
                    }
                    while (rs.next()) {
                        val trip = rs.getInt(1)
                        if (trip != currentTrip) { flush(); currentTrip = trip; calls.clear() }
                        calls += rs.getInt(2); calls += rs.getInt(3)
                    }
                    flush()
                }
                s.execute("DROP TABLE raw_time")
                s.execute("CREATE TABLE pattern_stop (stop INTEGER NOT NULL, pattern INTEGER NOT NULL, offset INTEGER NOT NULL, PRIMARY KEY (stop, pattern, offset)) WITHOUT ROWID")
            }
            conn.prepareStatement("INSERT INTO pattern_stop VALUES (?, ?, ?)").use { st ->
                var batch = 0
                patterns.forEach { (key, id) ->
                    for (i in key.indices step 2) {
                        st.setInt(1, key[i]); st.setInt(2, id); st.setInt(3, key[i + 1]); st.addBatch()
                        if (++batch % 20_000 == 0) st.executeBatch()
                    }
                }
                st.executeBatch()
            }
            insertAll("INSERT INTO trip VALUES (?, ?, ?, ?, ?, ?)", trips.values.filter { it.id in tripPattern }) { trip ->
                val (pattern, start) = tripPattern.getValue(trip.id)
                setInt(1, pattern); setInt(2, start); setInt(3, trip.id); setInt(4, trip.route); setInt(5, trip.service)
                val h = trip.headsign?.let { headsigns[it] }
                if (h != null) setInt(6, h) else setNull(6, java.sql.Types.INTEGER)
            }
            val validUntil = lastActiveDay?.let { windowStart.plusDays(it.toLong()) }
            insertAll(
                "INSERT INTO meta VALUES (?, ?)",
                listOfNotNull(
                    "feed_id" to feedId, "format" to TRANSIT_DB_FORMAT.toString(), "timezone" to timezone,
                    "window_start" to windowStart.format(GTFS_DATE), "window_days" to windowDays.toString(),
                    validUntil?.let { "valid_until" to it.format(GTFS_DATE) },
                ),
            ) { (k, v) -> setString(1, k); setString(2, v) }
            conn.commit()
            conn.autoCommit = true
            conn.createStatement().use { it.execute("VACUUM") }

            val used = stops.values
            val bbox = if (used.isEmpty()) null else doubleArrayOf(used.minOf { it.lon }, used.minOf { it.lat }, used.maxOf { it.lon }, used.maxOf { it.lat })
            return TransitStats(stops.size, routes.size, tripPattern.size, departures, validUntil, bbox)
        }
    }
}

/** Versione dello schema di transit.db: l'app rifiuta un formato piu' nuovo di quello che conosce. */
const val TRANSIT_DB_FORMAT = 2

/** Bit i acceso se [days][i]: byte i/8, bit i%8 (il meno significativo per primo). */
internal fun dayBits(days: BooleanArray): ByteArray {
    val bytes = ByteArray((days.size + 7) / 8)
    days.forEachIndexed { i, on -> if (on) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte() }
    return bytes
}

/** "HH:MM:SS" GTFS (ore oltre 24 dopo mezzanotte) in minuti; null se vuoto o non valido. */
internal fun gtfsMinutes(time: String): Int? {
    val parts = time.trim().split(':')
    if (parts.size < 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return h * 60 + m
}

/** CSV RFC 4180 (virgolette, virgole e a capo nei campi, BOM iniziale): una mappa per riga, chiavi dall'intestazione. */
internal fun readCsv(reader: BufferedReader, onRow: (Map<String, String>) -> Unit) {
    var header: List<String>? = null
    val fields = ArrayList<String>()
    val field = StringBuilder()
    var inQuotes = false
    var first = true
    fun endRow() {
        fields += field.toString(); field.setLength(0)
        val h = header
        if (h == null) {
            header = fields.map { it.trim() }
        } else if (!(fields.size == 1 && fields[0].isEmpty())) {
            onRow(h.indices.associate { i -> h[i] to fields.getOrElse(i) { "" }.trim() })
        }
        fields.clear()
    }
    while (true) {
        val c = reader.read()
        if (c == -1) break
        val ch = c.toChar()
        if (first) { first = false; if (ch == '﻿') continue }
        if (inQuotes) {
            if (ch == '"') {
                reader.mark(1)
                if (reader.read() == '"'.code) field.append('"') else { reader.reset(); inQuotes = false }
            } else field.append(ch)
        } else when (ch) {
            '"' -> inQuotes = true
            ',' -> { fields += field.toString(); field.setLength(0) }
            '\n' -> endRow()
            '\r' -> Unit
            else -> field.append(ch)
        }
    }
    if (field.isNotEmpty() || fields.isNotEmpty()) endRow()
}

fun main(args: Array<String>) {
    require(args.size == 5) { "Uso: generateTransit <gtfs.zip> <transit.db> <feedId> <inizio finestra AAAA-MM-GG> <giorni>" }
    val stats = generateTransit(File(args[0]), File(args[1]), args[2], LocalDate.parse(args[3]), args[4].toInt())
    // Riga TSV per build-transit.sh: fermate, linee, corse, orari, valido fino a (AAAA-MM-GG o vuoto), riquadro.
    println(
        listOf(
            stats.stops, stats.routes, stats.trips, stats.stopTimes, stats.validUntil?.toString().orEmpty(),
            stats.bbox?.joinToString(",") { "%.5f".format(java.util.Locale.ROOT, it) }.orEmpty(),
        ).joinToString("\t"),
    )
}
