package com.pockettravel.app.onboarding

import com.pockettravel.app.regions.RegionBbox
import com.pockettravel.app.regions.RegionUiItem
import com.pockettravel.core.data.PackageKind
import kotlin.math.hypot

/**
 * Regioni consigliate nel primo avvio: quelle del paese [countryCode] (la nazionalita' scelta, o il
 * paese del telefono) e poi le vicine, cioe' quelle il cui riquadro geografico tocca quello di una
 * regione del paese allargato di [NEAR_DEGREES] (non esiste una tabella dei confini), dalla piu'
 * vicina, al massimo [maxNeighbours]. Vuota se il paese non ha regioni nel catalogo.
 */
internal fun recommendedRegions(items: List<RegionUiItem>, countryCode: String?, maxNeighbours: Int = 6): List<RegionUiItem> {
    if (countryCode.isNullOrBlank()) return emptyList()
    val own = items.filter { it.countryCode.equals(countryCode, ignoreCase = true) }
    if (own.isEmpty()) return emptyList()
    val ownBoxes = own.mapNotNull { it.bbox }
    if (ownBoxes.isEmpty()) return own
    val centerLon = ownBoxes.map { (it.minLon + it.maxLon) / 2 }.average()
    val centerLat = ownBoxes.map { (it.minLat + it.maxLat) / 2 }.average()
    val neighbours = items
        .filter { item -> item !in own && item.bbox?.let { box -> ownBoxes.any { it.touches(box, NEAR_DEGREES) } } == true }
        .sortedBy { item -> item.bbox!!.let { hypot((it.minLon + it.maxLon) / 2 - centerLon, (it.minLat + it.maxLat) / 2 - centerLat) } }
        .take(maxNeighbours)
    return own + neighbours
}

/**
 * I pacchetti scelti di default per una regione nel primo avvio: come "Scarica" (tutti tranne POI
 * extra e percorsi, questi solo con "Indicazioni"), tra quelli che la regione offre.
 */
internal fun defaultPackageChoice(item: RegionUiItem, withRouting: Boolean): Set<PackageKind> =
    item.packages.map { it.kind }.filterTo(mutableSetOf()) { kind ->
        kind != PackageKind.POI_EXTRA && (kind != PackageKind.ROUTING || withRouting)
    }

/** Byte da scaricare per i pacchetti [kinds] della regione (la mappa conta zero: dimensione nota solo dopo). */
internal fun downloadBytes(item: RegionUiItem, kinds: Set<PackageKind>): Long =
    item.packages.filter { it.kind in kinds }.sumOf { it.downloadBytes }

private fun RegionBbox.touches(other: RegionBbox, margin: Double): Boolean =
    minLon - margin <= other.maxLon && maxLon + margin >= other.minLon &&
        minLat - margin <= other.maxLat && maxLat + margin >= other.minLat

/** Margine in gradi (circa 50 km) per considerare vicine due regioni che non si toccano per poco. */
private const val NEAR_DEGREES = 0.5
