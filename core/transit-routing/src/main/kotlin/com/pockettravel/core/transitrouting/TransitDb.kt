package com.pockettravel.core.transitrouting

/** Una fermata del blob di trip_stop: [seq] e' lo stop_sequence GTFS, [stop] l'id di transit.db, [offset] i minuti dalla partenza (trip.start). */
class RawTripStop(val seq: Int, val stop: Int, val offset: Int, val flags: Int) {
    val pickup: Boolean get() = flags and FLAG_NO_PICKUP == 0
    val dropOff: Boolean get() = flags and FLAG_NO_DROP_OFF == 0
}

/**
 * Le fermate del blob di trip_stop: per ogni fermata varint(stop_sequence meno il precedente), varint(zigzag(stop meno il
 * precedente)), varint(zigzag(offset meno il precedente)) e un byte di flag (bit 0 senza salita, bit 1 senza discesa); il
 * primo campo di ogni tipo e' relativo a 0. Null se il blob e' troncato o malformato.
 */
fun decodeTripStops(blob: ByteArray): List<RawTripStop>? {
    var pos = 0
    var broken = false
    fun varint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            if (pos >= blob.size || shift > VARINT_MAX_SHIFT) {
                broken = true
                return 0
            }
            val b = blob[pos++].toInt() and BYTE_MASK
            result = result or ((b and VARINT_PAYLOAD_MASK) shl shift)
            if (b and VARINT_CONTINUE == 0) return result
            shift += VARINT_PAYLOAD_BITS
        }
    }
    fun zigzag(): Int = varint().let { (it ushr 1) xor -(it and 1) }
    val stops = mutableListOf<RawTripStop>()
    var seq = 0
    var stop = 0
    var offset = 0
    while (pos < blob.size) {
        seq += varint()
        stop += zigzag()
        offset += zigzag()
        if (broken || pos >= blob.size) return null
        stops += RawTripStop(seq, stop, offset, blob[pos++].toInt() and BYTE_MASK)
    }
    return stops
}

/**
 * Il servizio c'e' il giorno [dayIndex] (giorni da window_start) se il bit e' acceso: byte i/8, bit i%8
 * dal meno significativo. Fuori dalla finestra ([windowDays] o la lunghezza della maschera) mai.
 */
fun isServiceActive(days: ByteArray, dayIndex: Int, windowDays: Int? = null): Boolean {
    if (dayIndex < 0 || dayIndex >= (windowDays ?: (days.size * BITS_PER_BYTE)) || dayIndex / BITS_PER_BYTE >= days.size) return false
    return (days[dayIndex / BITS_PER_BYTE].toInt() shr (dayIndex % BITS_PER_BYTE)) and 1 == 1
}

/** Una riga di stop di transit.db. */
class DbStop(val id: Int, val latE6: Int, val lonE6: Int, val parent: Int?)

/** Una riga di trip di transit.db con i giorni del suo servizio. */
class DbTrip(val id: Int, val route: Int, val start: Int, val serviceDays: ByteArray, val fullPattern: Int)

/** La rete di un giorno ([network]) con, per ogni indice di fermata, l'id di transit.db ([stopIds]). */
class DayNetwork(val network: TransitNetwork, val stopIds: IntArray)

/**
 * La rete del giorno [dayIndex] (giorni da window_start) da transit.db, con le corse che passano tra [fromMinute] e
 * [toMinute] (minuti dalla mezzanotte di quel giorno): quelle con servizio quel giorno e quelle del giorno prima ancora
 * in viaggio dopo mezzanotte, con gli orari meno 1440. Un pattern per full_pattern (stesse fermate agli stessi
 * intervalli, quindi senza sorpassi); [tripStops] e' il blob di trip_stop per pattern. Le corse senza un blob leggibile
 * si saltano.
 */
fun dayNetwork(
    stops: List<DbStop>,
    tripStops: Map<Int, ByteArray>,
    trips: List<DbTrip>,
    dayIndex: Int,
    fromMinute: Int,
    toMinute: Int,
    windowDays: Int? = null,
): DayNetwork {
    val index = stops.withIndex().associate { (i, stop) -> stop.id to i }
    val decoded = mutableMapOf<Int, List<RawTripStop>?>()
    val byPattern = mutableMapOf<Int, MutableList<PatternTrip>>()
    val routeOf = mutableMapOf<Int, Int>()
    for (trip in trips) {
        val raw = decoded.getOrPut(trip.fullPattern) { tripStops[trip.fullPattern]?.let(::decodeTripStops) }
        if (raw.isNullOrEmpty() || raw.any { it.stop !in index }) continue
        // Le corse del giorno e quelle partite il giorno prima, che dopo mezzanotte hanno gli orari oltre 1440.
        val runs = listOf(dayIndex to 0, dayIndex - 1 to -MINUTES_PER_DAY)
            .filter { (day, _) -> isServiceActive(trip.serviceDays, day, windowDays) }
            .map { (_, shift) -> IntArray(raw.size) { trip.start + raw[it].offset + shift } }
            .filter { times -> times.last() >= fromMinute && times.first() <= toMinute }
        for (times in runs) {
            byPattern.getOrPut(trip.fullPattern) { mutableListOf() } += PatternTrip(trip.id, times, times)
            routeOf[trip.fullPattern] = trip.route
        }
    }
    val patterns = byPattern.map { (pattern, patternTrips) ->
        val raw = checkNotNull(decoded[pattern]) { "pattern $pattern senza fermate" }
        TransitPattern(
            routeId = routeOf.getValue(pattern),
            stops = IntArray(raw.size) { index.getValue(raw[it].stop) },
            trips = patternTrips.sortedBy { it.departures.first() },
            canBoard = BooleanArray(raw.size) { raw[it].pickup },
            canAlight = BooleanArray(raw.size) { raw[it].dropOff },
        )
    }
    val network = TransitNetwork(
        // La stazione e le sue banchine hanno la stessa chiave: la banchina ha parent = id della stazione.
        stops.map { TransitStop(it.latE6 / E6, it.lonE6 / E6, (it.parent ?: it.id).toString()) },
        patterns,
    )
    return DayNetwork(network, IntArray(stops.size) { stops[it].id })
}

private const val MINUTES_PER_DAY = 1440
private const val E6 = 1e6
private const val BITS_PER_BYTE = 8
private const val FLAG_NO_PICKUP = 1
private const val FLAG_NO_DROP_OFF = 2
private const val VARINT_PAYLOAD_BITS = 7
private const val VARINT_PAYLOAD_MASK = 0x7F
private const val VARINT_CONTINUE = 0x80
private const val VARINT_MAX_SHIFT = 28
private const val BYTE_MASK = 0xFF
