package com.pockettravel.core.data

import java.time.Instant

/*
 * Avvisi di servizio sul tabellone: quelli del feed GTFS-RT della rete che riguardano le fermate del riquadro (anche
 * la stazione e le sue banchine), le linee che ci passano o le corse in elenco.
 */

/** Avvisi al massimo in un tabellone. */
internal const val TRANSIT_BOARD_MAX_ALERTS = 20

// Effect GTFS-RT che non dicono quanto pesa l'avviso (UNKNOWN_EFFECT, NO_EFFECT): in fondo.
private const val EFFECT_UNKNOWN = 8
private const val EFFECT_NO_EFFECT = 10
private const val EFFECT_LAST_RANK = 99

/**
 * Gli avvisi di [realtime] attivi a [now] (senza active_period, sempre; se no uno dei periodi contiene [now]) con una
 * informed_entity che riguarda il riquadro. L'entita' vale se tutti i suoi campi corrispondono: stop_id tra le fermate
 * [stops] (id GTFS), route_id tra le linee che passano da quelle fermate (non solo quelle delle partenze in finestra),
 * trip_id tra le corse di [departures], route_type tra i mezzi di quelle linee. Un'entita' con il solo agency_id vale
 * per tutta la rete e quindi sempre. Le entita' che indicano la corsa solo con linea, ora e data (Helsinki) non
 * agganciano, e il giorno di una corsa con trip_id non si controlla. Richiede gli id GTFS (meta gtfs_ids), e gtfs_routes
 * per le linee. Vedi [orderBoardAlerts] per l'ordine e il limite.
 */
internal fun boardAlerts(
    db: TransitQuery,
    meta: FeedMeta,
    feed: TransitFeedInfo?,
    stops: List<Long>,
    departures: List<StopDeparture>,
    realtime: RealtimeFeed,
    now: Instant,
): List<BoardAlert> {
    val active = if (meta.gtfsIds) realtime.alerts.mapNotNull { alert -> activePeriod(alert, now)?.let { alert to it } } else emptyList()
    if (active.isEmpty()) return emptyList()
    val scope = alertScope(db, meta, stops, departures)
    return orderBoardAlerts(
        active.filter { (alert, _) -> alert.informedEntities.any { it.matches(scope) } }
            .map { (alert, period) -> BoardAlert(alert.header, alert.description, alert.url, alert.effect, feed?.id, period.start, period.end) }
            .filter { it.header != null || it.description != null },
    )
}

/** Senza duplicati, dal piu' grave (effect 1..7, 9, 11), poi UNKNOWN_EFFECT e NO_EFFECT, a parita' il piu' recente; al massimo [TRANSIT_BOARD_MAX_ALERTS]. */
internal fun orderBoardAlerts(alerts: List<BoardAlert>): List<BoardAlert> =
    alerts.distinct()
        .sortedWith(compareBy<BoardAlert>({ effectRank(it.effect) }, { it.start?.let { start -> -start.epochSecond } ?: 0L }))
        .take(TRANSIT_BOARD_MAX_ALERTS)

private fun effectRank(effect: Int?): Int = if (effect == null || effect == EFFECT_UNKNOWN || effect == EFFECT_NO_EFFECT) EFFECT_LAST_RANK else effect

// Il periodo attivo a [now]: uno che lo contiene, o un periodo aperto se l'avviso non ne ha; null se non e' attivo.
private fun activePeriod(alert: TransitAlert, now: Instant): TransitActivePeriod? =
    if (alert.activePeriods.isEmpty()) {
        TransitActivePeriod(null, null)
    } else {
        alert.activePeriods.firstOrNull { (it.start == null || !now.isBefore(it.start)) && (it.end == null || now.isBefore(it.end)) }
    }

/** Id GTFS di fermate, linee e corse del riquadro, e mezzi delle sue linee. */
private class AlertScope(val stops: Set<String>, val routes: Set<String>, val trips: Set<String>, val modes: Set<TransitMode>)

private fun alertScope(db: TransitQuery, meta: FeedMeta, stops: List<Long>, departures: List<StopDeparture>): AlertScope {
    fun gtfsIds(sql: String) = db.query(sql) { it.string(0).orEmpty() }.toSet()
    val stopIds = if (stops.isEmpty()) emptySet() else gtfsIds("SELECT stop_id FROM gtfs_stop WHERE id IN (${stops.joinToString(",")})")
    val tripIds = departures.map { it.trip }.distinct().let { trips ->
        if (trips.isEmpty()) emptySet() else gtfsIds("SELECT trip_id FROM gtfs_trip WHERE id IN (${trips.joinToString(",")})")
    }
    val routes = if (stops.isEmpty()) {
        emptyList()
    } else {
        db.query(
            "SELECT DISTINCT t.route, r.type FROM pattern_stop ps JOIN trip t ON t.pattern = ps.pattern JOIN route r ON r.id = t.route " +
                "WHERE ps.stop IN (${stops.joinToString(",")})",
        ) { it.int(0) to it.int(1) }
    }
    val routeIds = if (!meta.gtfsRoutes || routes.isEmpty()) emptySet() else gtfsIds("SELECT route_id FROM gtfs_route WHERE id IN (${routes.map { it.first }.joinToString(",")})")
    return AlertScope(stopIds, routeIds, tripIds, routes.mapTo(mutableSetOf()) { transitModeOf(it.second) })
}

private fun TransitInformedEntity.matches(scope: AlertScope): Boolean {
    // Una corsa indicata solo da linea, ora e data non si aggancia; un'entita' vuota non dice a chi si riferisce.
    val tripByKeysOnly = tripId == null && tripRouteId != null
    val empty = listOf(stopId, routeId, tripId, agencyId, routeType).all { it == null }
    if (tripByKeysOnly || empty) return false
    return (stopId == null || stopId in scope.stops) &&
        (routeId == null || routeId in scope.routes) &&
        (tripId == null || tripId in scope.trips) &&
        (routeType == null || transitModeOf(routeType) in scope.modes)
}
