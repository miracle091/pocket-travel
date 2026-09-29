package com.pockettravel.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.hypot

/*
 * Tabellone delle partenze dei mezzi pubblici, dai transit.db (una rete GTFS l'uno) installati nella
 * cartella RegionStorage.TRANSIT_DIR della regione: fermate vicino a un punto e prossime partenze.
 * Qui la logica pura (bit dei giorni, fusi, dopo mezzanotte, finestra di validita') e le query, scritte
 * su TransitQuery cosi' i test JVM le eseguono su un transit.db sintetico; TransitRepository le
 * collega a SQLiteDatabase.
 */

/** Mezzo di una linea, dal route_type GTFS (base e tipi estesi 100-1700, mappati per centinaia). */
enum class TransitMode { TRAM, METRO, TRAIN, BUS, TROLLEYBUS, FERRY, CABLE, MONORAIL, OTHER }

fun transitModeOf(routeType: Int): TransitMode = when (routeType) {
    0 -> TransitMode.TRAM
    1 -> TransitMode.METRO
    2 -> TransitMode.TRAIN
    3 -> TransitMode.BUS
    4 -> TransitMode.FERRY
    5, 6, 7 -> TransitMode.CABLE
    11 -> TransitMode.TROLLEYBUS
    12 -> TransitMode.MONORAIL
    in 100..199 -> TransitMode.TRAIN
    in 200..299, in 700..799 -> TransitMode.BUS
    in 400..499 -> TransitMode.METRO
    in 800..899 -> TransitMode.TROLLEYBUS
    in 900..999 -> TransitMode.TRAM
    in 1000..1099 -> TransitMode.FERRY
    in 1300..1499 -> TransitMode.CABLE
    else -> TransitMode.OTHER
}

/** Una partenza: [minuteOfDay] e' l'ora sull'orologio della rete (0..1439), [inMinutes] i minuti da adesso. */
data class TransitDeparture(
    val line: String,
    val mode: TransitMode,
    // ARGB opachi dal colore GTFS della linea; null se la rete non lo indica. Il testo ricade su nero o
    // bianco in base alla luminosita' dello sfondo.
    val color: Int?,
    val textColor: Int?,
    val headsign: String?,
    val minuteOfDay: Int,
    val inMinutes: Int,
)

/**
 * Nome, attribuzione e licenza di una rete installata: TransitRepository li legge da [RegionStorage.TRANSIT_FEEDS_FILE].
 * [dataDate]: il giorno in cui gli orari sono stati presi dalla fonte (window_start di transit.db), mostrato con
 * la fonte come chiedono Licence Ouverte e Renfe; non sta in feeds.json, lo aggiunge readFeedBoard.
 */
@Serializable
data class TransitFeedInfo(
    val id: String,
    val name: String,
    val attribution: String,
    val licenseUrl: String? = null,
    @Transient val dataDate: LocalDate? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun encode(feeds: List<TransitFeedInfo>): String = json.encodeToString(ListSerializer(serializer()), feeds)
        fun decode(text: String): List<TransitFeedInfo> = json.decodeFromString(ListSerializer(serializer()), text)
    }
}

/** Che cosa mostrare nella scheda di una fermata. */
sealed interface TransitBoard {
    /** Nessuna rete installata ha fermate vicino al punto. */
    data object NoStops : TransitBoard

    /** Orari fuori dalla finestra di validita' (prima dell'inizio o dopo la fine): nessuna partenza. */
    data class Expired(val validUntil: LocalDate, val feeds: List<TransitFeedInfo>) : TransitBoard

    /** Le prossime partenze (anche nessuna), con l'ultimo giorno valido e i giorni che restano (0 = scade oggi). */
    data class Departures(
        val items: List<TransitDeparture>,
        val validUntil: LocalDate,
        val daysLeft: Int,
        val feeds: List<TransitFeedInfo>,
    ) : TransitBoard {
        /** Gli orari scadono entro una settimana: il tabellone propone di aggiornarli. */
        val expiresSoon: Boolean get() = daysLeft <= 7
    }
}

/** Raggio entro cui le fermate contano come "vicine" al punto. */
internal const val TRANSIT_STOP_RADIUS_M = 150.0

/** Ampiezza in avanti del tabellone e numero massimo di partenze mostrate. */
internal const val TRANSIT_WINDOW_MINUTES = 180
internal const val TRANSIT_MAX_DEPARTURES = 10

private const val MINUTES_PER_DAY = 1440
private const val METERS_PER_DEGREE = 111_320.0
private val BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE

/** Righe di una query, per non legare la logica ne' ad android.database ne' a JDBC. */
internal interface TransitRow {
    fun int(column: Int): Int
    fun string(column: Int): String?
    fun bytes(column: Int): ByteArray?
}

/** Schema di transit.db che l'app legge (GenerateTransit.kt, TRANSIT_DB_FORMAT, in tools/data-pipeline). */
internal const val TRANSIT_DB_FORMAT = 2

internal interface TransitQuery {
    fun <T> query(sql: String, read: (TransitRow) -> T): List<T>
}

/** Esito per una rete: [board] e' Departures o Expired; null se la rete non ha fermate vicino al punto. */
internal fun readFeedBoard(db: TransitQuery, feed: TransitFeedInfo?, latitude: Double, longitude: Double, now: Instant): TransitBoard? {
    val meta = db.query("SELECT key, value FROM meta") { it.string(0).orEmpty() to it.string(1).orEmpty() }.toMap()
    // Un formato diverso da quello che l'app sa leggere: la rete si ignora finche' non si aggiorna.
    if (meta["format"]?.toIntOrNull() != TRANSIT_DB_FORMAT) return null
    val zone = ZoneId.of(meta.getValue("timezone"))
    val windowStart = LocalDate.parse(meta.getValue("window_start"), BASIC_DATE)
    val validUntil = LocalDate.parse(meta.getValue("valid_until"), BASIC_DATE)
    val windowDays = meta["window_days"]?.toIntOrNull()
    val stops = nearbyStopIds(db, latitude, longitude)
    if (stops.isEmpty()) return null
    val feeds = listOfNotNull(feed?.copy(dataDate = windowStart))

    val local = now.atZone(zone)
    val today = local.toLocalDate()
    if (today < windowStart || today > validUntil) return TransitBoard.Expired(validUntil, feeds)
    val nowMinute = local.hour * 60 + local.minute

    val ids = stops.joinToString(",")
    val found = mutableListOf<TransitDeparture>()
    val seen = mutableSetOf<Triple<Int, Int, Int>>()
    // Ieri (servizi che passano la mezzanotte, minuti oltre 1440), oggi e domani (partenze subito dopo
    // mezzanotte quando la finestra la attraversa): minuto di servizio = minuto assoluto - offset * 1440.
    for (offset in -1..1) {
        val from = maxOf(0, nowMinute - offset * MINUTES_PER_DAY)
        val to = nowMinute + TRANSIT_WINDOW_MINUTES - offset * MINUTES_PER_DAY
        if (to < from) continue
        val dayIndex = ChronoUnit.DAYS.between(windowStart, today.plusDays(offset.toLong())).toInt()
        if (dayIndex < 0) continue
        // Formato 2 (GenerateTransit): i pattern della fermata, poi le loro corse per minuto di partenza
        // (chiave della tabella trip); il passaggio alla fermata e' partenza + offset.
        val rows = db.query(
            "SELECT t.start + ps.offset AS minute, t.id, r.short_name, r.long_name, r.type, r.color, r.text_color, h.text, sv.days " +
                "FROM pattern_stop ps JOIN trip t ON t.pattern = ps.pattern AND t.start BETWEEN $from - ps.offset AND $to - ps.offset " +
                "JOIN route r ON r.id = t.route JOIN service sv ON sv.id = t.service LEFT JOIN headsign h ON h.id = t.headsign " +
                "WHERE ps.stop IN ($ids) ORDER BY minute",
        ) { row ->
            val days = row.bytes(8)
            if (days == null || !isServiceActive(days, dayIndex, windowDays)) return@query null
            val minute = row.int(0)
            if (!seen.add(Triple(row.int(1), offset, minute))) return@query null
            val absolute = offset * MINUTES_PER_DAY + minute
            val color = parseGtfsColor(row.string(5))
            TransitDeparture(
                line = row.string(2)?.takeIf { it.isNotBlank() } ?: row.string(3)?.takeIf { it.isNotBlank() } ?: "?",
                mode = transitModeOf(row.int(4)),
                color = color,
                textColor = parseGtfsColor(row.string(6)) ?: color?.let(::contrastingTextColor),
                headsign = row.string(7)?.takeIf { it.isNotBlank() },
                minuteOfDay = Math.floorMod(absolute, MINUTES_PER_DAY),
                inMinutes = absolute - nowMinute,
            )
        }
        found += rows.filterNotNull()
    }
    val daysLeft = ChronoUnit.DAYS.between(today, validUntil).toInt()
    return TransitBoard.Departures(found.sortedBy { it.inMinutes }, validUntil, daysLeft, feeds)
}

/**
 * Fermate del gruppo del punto: quelle entro [TRANSIT_STOP_RADIUS_M] (riquadro in microgradi, poi
 * distanza), con le loro stazioni (parent) e tutte le banchine di quelle stazioni. Vuoto se nessuna.
 */
internal fun nearbyStopIds(db: TransitQuery, latitude: Double, longitude: Double): List<Long> {
    val dLat = TRANSIT_STOP_RADIUS_M / METERS_PER_DEGREE
    val dLon = dLat / maxOf(cos(Math.toRadians(latitude)), 0.01)
    fun micro(degrees: Double) = Math.round(degrees * 1e6)
    val rows = db.query(
        "SELECT id, parent, latE6, lonE6 FROM stop WHERE latE6 BETWEEN ${micro(latitude - dLat)} AND ${micro(latitude + dLat)} " +
            "AND lonE6 BETWEEN ${micro(longitude - dLon)} AND ${micro(longitude + dLon)}",
    ) { StopRow(it.string(0)!!.toLong(), it.string(1)?.toLong(), it.string(2)!!.toLong(), it.string(3)!!.toLong()) }
    val near = rows.filter { distanceMeters(latitude, longitude, it.latE6 / 1e6, it.lonE6 / 1e6) <= TRANSIT_STOP_RADIUS_M }
    if (near.isEmpty()) return emptyList()
    val ids = near.mapTo(mutableSetOf()) { it.id }
    val stations = near.mapTo(mutableSetOf()) { it.parent ?: it.id }
    ids += db.query("SELECT id FROM stop WHERE id IN (${stations.joinToString(",")}) OR parent IN (${stations.joinToString(",")})") { it.string(0)!!.toLong() }
    return ids.sorted()
}

private data class StopRow(val id: Long, val parent: Long?, val latE6: Long, val lonE6: Long)

/** Distanza approssimata (piano locale), abbondante per qualche centinaio di metri. */
internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dy = (lat2 - lat1) * METERS_PER_DEGREE
    val dx = (lon2 - lon1) * METERS_PER_DEGREE * cos(Math.toRadians((lat1 + lat2) / 2))
    return hypot(dx, dy)
}

/**
 * Il servizio c'e' il giorno [dayIndex] (giorni da window_start) se il bit e' acceso: byte i/8, bit i%8
 * dal meno significativo. Fuori dalla finestra ([windowDays] o la lunghezza della maschera) mai.
 */
internal fun isServiceActive(days: ByteArray, dayIndex: Int, windowDays: Int? = null): Boolean {
    if (dayIndex < 0 || dayIndex >= (windowDays ?: (days.size * 8)) || dayIndex / 8 >= days.size) return false
    return (days[dayIndex / 8].toInt() shr (dayIndex % 8)) and 1 == 1
}

/** "RRGGBB" (con o senza #) in ARGB opaco, null se vuoto o non valido. */
internal fun parseGtfsColor(value: String?): Int? {
    val hex = value?.trim()?.removePrefix("#") ?: return null
    if (hex.length != 6) return null
    return hex.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
}

/** Nero o bianco, quello che si legge meglio sul colore [background]. */
internal fun contrastingTextColor(background: Int): Int {
    val r = (background shr 16) and 0xFF
    val g = (background shr 8) and 0xFF
    val b = background and 0xFF
    return if (0.299 * r + 0.587 * g + 0.114 * b > 150) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
}

/**
 * Unisce gli esiti delle reti: nessuna rete con fermate vicine -> [TransitBoard.NoStops]; tutte fuori
 * finestra -> Expired (con la data piu' recente); altrimenti le partenze delle reti valide, in ordine
 * di ora e al massimo [TRANSIT_MAX_DEPARTURES], con la scadenza piu' vicina tra quelle reti.
 */
internal fun combineBoards(boards: List<TransitBoard>): TransitBoard {
    val expired = boards.filterIsInstance<TransitBoard.Expired>()
    val active = boards.filterIsInstance<TransitBoard.Departures>()
    if (active.isEmpty()) {
        return if (expired.isEmpty()) TransitBoard.NoStops else TransitBoard.Expired(expired.maxOf { it.validUntil }, expired.flatMap { it.feeds })
    }
    return TransitBoard.Departures(
        items = active.flatMap { it.items }.sortedBy { it.inMinutes }.take(TRANSIT_MAX_DEPARTURES),
        validUntil = active.minOf { it.validUntil },
        daysLeft = active.minOf { it.daysLeft },
        feeds = active.flatMap { it.feeds },
    )
}

