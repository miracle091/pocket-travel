package com.pockettravel.core.data.alerts

/** Fonti che danno una copia della stessa allerta per ogni zona di previsione. */
private val ZONED_SOURCES = setOf(AlertSource.NWS, AlertSource.ECCC)

/** Lunghezza massima dell'elenco delle zone di un'allerta unita: oltre, si tronca con "…". */
private const val MAX_AREA_NAMES_CHARS = 160

/**
 * Le copie per zona di NWS ed ECCC con stesso tipo, livello e testo diventano una sola allerta, con le zone in elenco, il
 * rettangolo che le contiene tutte e la validita' piu' ampia. Le allerte di tsunami di NWS si tolgono quando ci sono
 * quelle dei centri tsunami, che sono la fonte del messaggio.
 */
internal fun List<Alert>.mergeZones(): List<Alert> {
    val hasTsunamiCenter = any { it.source == AlertSource.TSUNAMI }
    return filterNot { hasTsunamiCenter && it.source == AlertSource.NWS && it.kind == AlertKind.TSUNAMI }
        .groupBy { alert -> if (alert.source in ZONED_SOURCES) listOf(alert.source, alert.title, alert.level, alert.details) else alert.id }
        .values.map { copies -> if (copies.size == 1) copies.first() else merged(copies) }
}

private fun merged(copies: List<Alert>): Alert {
    val areas = copies.map { it.area }
    val corners = areas.filterNotNull().flatMap { listOf(it.minLon to it.minLat, it.maxLon to it.maxLat) }
    val ends = copies.map { it.endsAt }
    return copies.first().copy(
        areaName = copies.mapNotNull { it.areaName }.distinct().joinToString(", ").shortened(),
        area = if (areas.any { it == null }) null else boundsOf(corners),
        startsAt = copies.mapNotNull { it.startsAt }.minOrNull(),
        endsAt = if (ends.any { it == null }) null else ends.filterNotNull().maxOrNull(),
    )
}

private fun String.shortened(): String? = when {
    isEmpty() -> null
    length <= MAX_AREA_NAMES_CHARS -> this
    else -> take(MAX_AREA_NAMES_CHARS).substringBeforeLast(", ") + "…"
}
