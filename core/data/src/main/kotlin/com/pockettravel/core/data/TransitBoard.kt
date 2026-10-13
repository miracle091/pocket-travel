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
    GTFS_BUS -> TransitMode.BUS
    GTFS_FERRY -> TransitMode.FERRY
    GTFS_CABLE_TRAM, GTFS_AERIAL_LIFT, GTFS_FUNICULAR -> TransitMode.CABLE
    GTFS_TROLLEYBUS -> TransitMode.TROLLEYBUS
    GTFS_MONORAIL -> TransitMode.MONORAIL
    in GTFS_EXTENDED_RAIL -> TransitMode.TRAIN
    in GTFS_EXTENDED_COACH, in GTFS_EXTENDED_BUS -> TransitMode.BUS
    in GTFS_EXTENDED_METRO -> TransitMode.METRO
    in GTFS_EXTENDED_TROLLEYBUS -> TransitMode.TROLLEYBUS
    in GTFS_EXTENDED_TRAM -> TransitMode.TRAM
    in GTFS_EXTENDED_WATER -> TransitMode.FERRY
    in GTFS_EXTENDED_CABLE -> TransitMode.CABLE
    // Gli altri tipi estesi per centinaia: 3xx ferrovia suburbana, 5xx e 6xx metropolitana, 12xx traghetto.
    else -> when (routeType / GTFS_EXTENDED_CLASS_SIZE) {
        GTFS_CLASS_SUBURBAN_RAIL -> TransitMode.TRAIN
        GTFS_CLASS_METRO, GTFS_CLASS_UNDERGROUND -> TransitMode.METRO
        GTFS_CLASS_FERRY -> TransitMode.FERRY
        else -> TransitMode.OTHER
    }
}

// route_type GTFS base: i valori 0 (tram), 1 (metro) e 2 (treno) restano nel when.
private const val GTFS_BUS = 3
private const val GTFS_FERRY = 4
private const val GTFS_CABLE_TRAM = 5
private const val GTFS_AERIAL_LIFT = 6
private const val GTFS_FUNICULAR = 7
private const val GTFS_TROLLEYBUS = 11
private const val GTFS_MONORAIL = 12

// route_type GTFS estesi: un intervallo di cento valori per categoria.
private val GTFS_EXTENDED_RAIL = 100..199
private val GTFS_EXTENDED_COACH = 200..299
private val GTFS_EXTENDED_METRO = 400..499
private val GTFS_EXTENDED_BUS = 700..799
private val GTFS_EXTENDED_TROLLEYBUS = 800..899
private val GTFS_EXTENDED_TRAM = 900..999
private val GTFS_EXTENDED_WATER = 1000..1099
private val GTFS_EXTENDED_CABLE = 1300..1499

// Categorie estese non elencate sopra, identificate dalle centinaia del route_type.
private const val GTFS_EXTENDED_CLASS_SIZE = 100
private const val GTFS_CLASS_SUBURBAN_RAIL = 3
private const val GTFS_CLASS_METRO = 5
private const val GTFS_CLASS_UNDERGROUND = 6
private const val GTFS_CLASS_FERRY = 12

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
    // wheelchair_accessible GTFS della corsa: true accessibile, false no, null se la rete non lo indica.
    val wheelchair: Boolean? = null,
    // Rete scaduta da al massimo TRANSIT_GRACE_DAYS giorni: partenza ricavata dallo stesso giorno della settimana prima.
    val estimated: Boolean = false,
    // Le partenze successive della stessa linea e direzione, al massimo [TRANSIT_LATER_DEPARTURES] (vuoto nelle successive).
    val later: List<TransitDeparture> = emptyList(),
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

/** Che cosa mostrare nel riquadro di una fermata. */
sealed interface TransitBoard {
    /** Nessuna rete installata ha fermate vicino al punto. */
    data object NoStops : TransitBoard

    /**
     * Orari fuori dalla finestra di validita' (oltre un giorno prima dell'inizio, o dopo la fine): nessuna partenza.
     * [estimated]: scaduti da al massimo [TRANSIT_GRACE_DAYS] giorni, con le partenze stimate dalla settimana prima.
     */
    data class Expired(val validUntil: LocalDate, val feeds: List<TransitFeedInfo>, val estimated: Boolean = false) : TransitBoard

    /** Le prossime partenze (anche nessuna), con l'ultimo giorno valido e i giorni che restano (0 = scade oggi). */
    data class Departures(
        val items: List<TransitDeparture>,
        val validUntil: LocalDate,
        val daysLeft: Int,
        val feeds: List<TransitFeedInfo>,
        // wheelchair_boarding GTFS delle fermate vicine: true se almeno una e' accessibile, false se nessuna lo e'
        // e almeno una no, null se la rete non lo indica.
        val stopWheelchair: Boolean? = null,
        // Reti con fermate vicine ma orari scaduti: le loro partenze non sono in [items] (o ci sono stimate, se
        // Expired.estimated), il tabellone lo dice.
        val expired: List<Expired> = emptyList(),
    ) : TransitBoard {
        /** Gli orari scadono entro una settimana: il tabellone propone di aggiornarli. */
        val expiresSoon: Boolean get() = daysLeft in 0..EXPIRES_SOON_DAYS
    }
}

/** Raggio entro cui le fermate contano come "vicine" al punto. */
internal const val TRANSIT_STOP_RADIUS_M = 150.0

private const val EXPIRES_SOON_DAYS = 7

/**
 * Per stazioni e terminal: se una rete non ha fermate entro [TRANSIT_STOP_RADIUS_M], la sua fermata piu' vicina entro
 * questo raggio servita dal mezzo della stazione (treni per una stazione, bus per un'autostazione...). Nelle stazioni
 * grandi la fermata GTFS e' lontana dal punto OSM (Riga Centrale 228 m, Milano Centrale 158 m, Roma Termini 156 m);
 * il mezzo evita di prendere, per una stazione con i treni vicini, il tram o il bus di un'altra rete a 400 m.
 */
internal const val TRANSIT_STATION_RADIUS_M = 400.0

/**
 * Ampiezza in avanti del tabellone e partenze successive mostrate accanto alla prossima di ogni linea e direzione.
 * Una riga per linea e direzione, senza limite: nelle fermate con centinaia di partenze (autostazione di Riga) un
 * limite sulle righe lasciava fuori le linee meno frequenti, coperte da tram e bus urbani.
 */
internal const val TRANSIT_WINDOW_MINUTES = 180
internal const val TRANSIT_LATER_DEPARTURES = 2

/** Giorni dopo la scadenza in cui le partenze si stimano dallo stesso giorno della settimana prima. */
internal const val TRANSIT_GRACE_DAYS = 3L

private const val MINUTES_PER_DAY = 1440

// Un servizio stimato prende i giorni di una settimana prima.
private const val ESTIMATED_SHIFT_DAYS = 7L
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

/**
 * Esito per una rete: [board] e' Departures o Expired; null se la rete non ha fermate vicino al punto. [stationModes]: i
 * mezzi della stazione o del terminal nel punto, vuoto per un punto qualsiasi; vedi [TRANSIT_STATION_RADIUS_M].
 */
internal fun readFeedBoard(
    db: TransitQuery,
    feed: TransitFeedInfo?,
    latitude: Double,
    longitude: Double,
    now: Instant,
    stationModes: Set<TransitMode> = emptySet(),
): TransitBoard? {
    val meta = db.query("SELECT key, value FROM meta") { it.string(0).orEmpty() to it.string(1).orEmpty() }.toMap()
    // Un formato diverso da quello che l'app sa leggere: la rete si ignora finche' non si aggiorna.
    if (meta["format"]?.toIntOrNull() != TRANSIT_DB_FORMAT) return null
    val zone = ZoneId.of(meta.getValue("timezone"))
    val windowStart = LocalDate.parse(meta.getValue("window_start"), BASIC_DATE)
    val validUntil = LocalDate.parse(meta.getValue("valid_until"), BASIC_DATE)
    val windowDays = meta["window_days"]?.toIntOrNull()
    val stops = nearbyStopIds(db, latitude, longitude, stationModes)
    if (stops.isEmpty()) return null
    val feeds = listOfNotNull(feed?.copy(dataDate = windowStart))
    // Colonne wheelchair di stop e trip: solo nei transit.db costruiti dopo che la pipeline le ha aggiunte, vedi meta.
    val hasWheelchair = meta["wheelchair"] == "1"

    val local = now.atZone(zone)
    val today = local.toLocalDate()
    // window_start e' la data di costruzione nel fuso della rete: con un fuso avanti rispetto a chi ha
    // costruito puo' essere domani. Quel giorno di scarto non e' scaduto: nessuna partenza oggi (dayIndex < 0
    // sotto), ma quelle dopo mezzanotte si vedono.
    if (today.plusDays(1) < windowStart || today > validUntil.plusDays(TRANSIT_GRACE_DAYS)) return TransitBoard.Expired(validUntil, feeds)
    val grace = today > validUntil // scaduta da poco: i giorni dopo la scadenza prendono i servizi di 7 giorni prima
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
        val estimated = grace && today.plusDays(offset.toLong()) > validUntil
        val dayIndex = ChronoUnit.DAYS.between(windowStart, today.plusDays(offset - if (estimated) ESTIMATED_SHIFT_DAYS else 0L)).toInt()
        if (dayIndex < 0) continue
        // Formato 2 (GenerateTransit): i pattern della fermata, poi le loro corse per minuto di partenza
        // (chiave della tabella trip); il passaggio alla fermata e' partenza + offset.
        val rows = db.query(
            "SELECT t.start + ps.offset AS minute, t.id, r.short_name, r.long_name, r.type, r.color, r.text_color, h.text, sv.days, ${if (hasWheelchair) "t.wheelchair" else "NULL"} " +
                "FROM pattern_stop ps JOIN trip t ON t.pattern = ps.pattern AND t.start BETWEEN $from - ps.offset AND $to - ps.offset " +
                "JOIN route r ON r.id = t.route JOIN service sv ON sv.id = t.service LEFT JOIN headsign h ON h.id = t.headsign " +
                "WHERE ps.stop IN ($ids) ORDER BY minute",
        ) { row ->
            val days = row.bytes(DEP_COL_DAYS)
            if (days == null || !isServiceActive(days, dayIndex, windowDays)) return@query null
            val minute = row.int(DEP_COL_MINUTE)
            if (!seen.add(Triple(row.int(DEP_COL_TRIP), offset, minute))) return@query null
            val absolute = offset * MINUTES_PER_DAY + minute
            val color = parseGtfsColor(row.string(DEP_COL_COLOR))
            TransitDeparture(
                line = row.string(DEP_COL_SHORT_NAME)?.takeIf { it.isNotBlank() } ?: row.string(DEP_COL_LONG_NAME)?.takeIf { it.isNotBlank() } ?: "?",
                mode = transitModeOf(row.int(DEP_COL_TYPE)),
                color = color,
                textColor = parseGtfsColor(row.string(DEP_COL_TEXT_COLOR)) ?: color?.let(::contrastingTextColor),
                headsign = row.string(DEP_COL_HEADSIGN)?.takeIf { it.isNotBlank() },
                minuteOfDay = Math.floorMod(absolute, MINUTES_PER_DAY),
                inMinutes = absolute - nowMinute,
                wheelchair = wheelchairOf(row.string(DEP_COL_WHEELCHAIR)),
                estimated = estimated,
            )
        }
        found += rows.filterNotNull()
    }
    val daysLeft = ChronoUnit.DAYS.between(today, validUntil).toInt()
    return TransitBoard.Departures(
        found.sortedBy { it.inMinutes }, validUntil, daysLeft, feeds, if (hasWheelchair) stopWheelchair(db, ids) else null,
        expired = if (grace) listOf(TransitBoard.Expired(validUntil, feeds, estimated = true)) else emptyList(),
    )
}

/** wheelchair_boarding delle fermate [ids]: true se almeno una e' accessibile, false se nessuna e almeno una no. */
private fun stopWheelchair(db: TransitQuery, ids: String): Boolean? {
    val values = db.query("SELECT wheelchair FROM stop WHERE id IN ($ids)") { wheelchairOf(it.string(0)) }
    return if (true in values) true else if (false in values) false else null
}

/** wheelchair_boarding / wheelchair_accessible come li scrive la pipeline: 1 si', 2 no, altro (NULL) non indicato. */
internal fun wheelchairOf(value: String?): Boolean? = when (value) {
    "1" -> true
    "2" -> false
    else -> null
}

/**
 * Fermate del gruppo del punto: quelle entro [TRANSIT_STOP_RADIUS_M] (rettangolo in microgradi, poi
 * distanza), con le loro stazioni (parent) e tutte le banchine di quelle stazioni. Per una stazione senza
 * fermate cosi' vicine, il gruppo della fermata piu' vicina entro [TRANSIT_STATION_RADIUS_M] servita da uno dei
 * [stationModes]. Vuoto se nessuna.
 */
internal fun nearbyStopIds(db: TransitQuery, latitude: Double, longitude: Double, stationModes: Set<TransitMode> = emptySet()): List<Long> {
    val radius = if (stationModes.isEmpty()) TRANSIT_STOP_RADIUS_M else TRANSIT_STATION_RADIUS_M
    val dLat = radius / METERS_PER_DEGREE
    val dLon = dLat / maxOf(cos(Math.toRadians(latitude)), 0.01)
    fun micro(degrees: Double) = Math.round(degrees * MICRODEGREES_PER_DEGREE)
    val rows = db.query(
        "SELECT id, parent, latE6, lonE6 FROM stop WHERE latE6 BETWEEN ${micro(latitude - dLat)} AND ${micro(latitude + dLat)} " +
            "AND lonE6 BETWEEN ${micro(longitude - dLon)} AND ${micro(longitude + dLon)}",
    ) { StopRow(it.string(STOP_COL_ID)!!.toLong(), it.string(STOP_COL_PARENT)?.toLong(), it.string(STOP_COL_LAT)!!.toLong(), it.string(STOP_COL_LON)!!.toLong()) }
    val distances = rows.associateWith { distanceMeters(latitude, longitude, it.latE6 / MICRODEGREES_PER_DEGREE, it.lonE6 / MICRODEGREES_PER_DEGREE) }
    val near = distances.filterValues { it <= TRANSIT_STOP_RADIUS_M }.keys
        .ifEmpty { listOfNotNull(nearestServedStop(db, distances.filterValues { it <= radius }, stationModes)) }
    if (near.isEmpty()) return emptyList()
    val ids = near.mapTo(mutableSetOf()) { it.id }
    val stations = near.mapTo(mutableSetOf()) { it.parent ?: it.id }
    ids += db.query("SELECT id FROM stop WHERE id IN (${stations.joinToString(",")}) OR parent IN (${stations.joinToString(",")})") { it.string(0)!!.toLong() }
    return ids.sorted()
}

/**
 * La piu' vicina tra le fermate [candidates] (con la distanza) servite da linee di uno dei [modes]; le stazioni parent
 * non hanno passaggi, le loro banchine si'. Null se nessuna.
 */
private fun nearestServedStop(db: TransitQuery, candidates: Map<StopRow, Double>, modes: Set<TransitMode>): StopRow? {
    if (candidates.isEmpty()) return null
    val served = db.query(
        "SELECT DISTINCT ps.stop, r.type FROM pattern_stop ps JOIN trip t ON t.pattern = ps.pattern JOIN route r ON r.id = t.route " +
            "WHERE ps.stop IN (${candidates.keys.joinToString(",") { it.id.toString() }})",
    ) { it.string(0)?.toLongOrNull() to transitModeOf(it.int(1)) }
        .filter { it.second in modes }.mapNotNullTo(mutableSetOf()) { it.first }
    return candidates.filterKeys { it.id in served }.minByOrNull { it.value }?.key
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
    if (dayIndex < 0 || dayIndex >= (windowDays ?: (days.size * BITS_PER_BYTE)) || dayIndex / BITS_PER_BYTE >= days.size) return false
    return (days[dayIndex / BITS_PER_BYTE].toInt() shr (dayIndex % BITS_PER_BYTE)) and 1 == 1
}

/** "RRGGBB" (con o senza #) in ARGB opaco, null se vuoto o non valido. */
internal fun parseGtfsColor(value: String?): Int? {
    val hex = value?.trim()?.removePrefix("#") ?: return null
    // toIntOrNull(16) accetterebbe anche il segno e le cifre non ASCII.
    if (hex.length != HEX_COLOR_LENGTH || HEX_DIGITS_ONLY.matchEntire(hex) == null) return null
    return hex.toIntOrNull(HEX_RADIX)?.let { 0xFF000000.toInt() or it }
}

private val HEX_DIGITS_ONLY = Regex("[0-9a-fA-F]+")

private const val HEX_COLOR_LENGTH = 6
private const val HEX_RADIX = 16
private const val BITS_PER_BYTE = 8
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MASK = 0xFF

// Luminanza percepita (ITU-R BT.601): sopra la soglia lo sfondo e' chiaro e il testo va nero.
private const val LUMA_RED = 0.299
private const val LUMA_GREEN = 0.587
private const val LUMA_BLUE = 0.114
private const val DARK_TEXT_LUMA_THRESHOLD = 150

private const val MICRODEGREES_PER_DEGREE = 1e6

// Colonne della query delle partenze (dopo il minuto, l'id della corsa e i dati della linea).
private const val DEP_COL_MINUTE = 0
private const val DEP_COL_TRIP = 1
private const val DEP_COL_SHORT_NAME = 2
private const val DEP_COL_LONG_NAME = 3
private const val DEP_COL_TYPE = 4
private const val DEP_COL_COLOR = 5
private const val DEP_COL_TEXT_COLOR = 6
private const val DEP_COL_HEADSIGN = 7
private const val DEP_COL_DAYS = 8
private const val DEP_COL_WHEELCHAIR = 9

// Colonne della query delle fermate vicine.
private const val STOP_COL_ID = 0
private const val STOP_COL_PARENT = 1
private const val STOP_COL_LAT = 2
private const val STOP_COL_LON = 3

/** Nero o bianco, quello che si legge meglio sul colore [background]. */
internal fun contrastingTextColor(background: Int): Int {
    val r = (background shr RED_SHIFT) and CHANNEL_MASK
    val g = (background shr GREEN_SHIFT) and CHANNEL_MASK
    val b = background and CHANNEL_MASK
    return if (LUMA_RED * r + LUMA_GREEN * g + LUMA_BLUE * b > DARK_TEXT_LUMA_THRESHOLD) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
}

/**
 * Unisce gli esiti delle reti: nessuna rete con fermate vicine -> [TransitBoard.NoStops]; tutte fuori
 * finestra -> Expired (con la data piu' recente, stimato solo se lo sono tutte); altrimenti le partenze delle
 * reti valide, una per linea, mezzo e direzione con le successive in [TransitDeparture.later], in ordine di ora, con la scadenza piu' vicina tra le reti
 * ancora valide (tra quelle stimate se non ce ne sono) e le reti scadute.
 */
internal fun combineBoards(boards: List<TransitBoard>): TransitBoard {
    val expired = boards.filterIsInstance<TransitBoard.Expired>()
    val active = boards.filterIsInstance<TransitBoard.Departures>()
    if (active.isEmpty()) {
        return if (expired.isEmpty()) TransitBoard.NoStops else TransitBoard.Expired(expired.maxOf { it.validUntil }, expired.flatMap { it.feeds }, expired.all { it.estimated })
    }
    val valid = active.filter { it.expired.isEmpty() }.ifEmpty { active }
    return TransitBoard.Departures(
        items = active.flatMap { it.items }.sortedBy { it.inMinutes }
            .groupBy { Triple(it.line, it.mode, it.headsign) }.values
            .map { group -> group.first().copy(later = group.drop(1).take(TRANSIT_LATER_DEPARTURES)) },
        validUntil = valid.minOf { it.validUntil },
        daysLeft = valid.minOf { it.daysLeft },
        feeds = active.flatMap { it.feeds },
        stopWheelchair = active.map { it.stopWheelchair }.let { if (true in it) true else if (false in it) false else null },
        expired = active.flatMap { it.expired } + expired,
    )
}

