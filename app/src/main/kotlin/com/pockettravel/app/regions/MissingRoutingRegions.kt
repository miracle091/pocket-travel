package com.pockettravel.app.regions

import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.feature.map.RoutePoint
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Le regioni del manifest da cui scaricare la rete stradale per andare dal primo all'ultimo dei [points] (partenza, linea
 * retta in mezzo, arrivo), nell'ordine del viaggio. I paesi sono quelli del cammino via terra fra il paese di
 * partenza e quello di arrivo ([landPath] sui confini veri: da San Marino a Riga Italia, Austria, Cechia, Polonia,
 * Lituania, non la Croazia o Kaliningrad che la linea sfiora passando sul mare). Per ogni paese del cammino, le
 * regioni sotto la linea (gli Stati Uniti sono divisi in stati) o, se la linea non ci passa, la sua regione piu'
 * vicina alla linea. Senza un cammino via terra (isole, partenza in mare) si segue la linea. Vuota se tutto e'
 * coperto dalla rete stradale installata ([installedRoutingIds]).
 */
internal fun missingRoutingRegions(
    regions: List<RegionManifestEntry>,
    installedRoutingIds: Set<String>,
    points: List<RoutePoint>,
    countryAt: (RoutePoint) -> String?,
    neighbours: Map<String, Set<String>> = emptyMap(),
    centres: Map<String, RoutePoint> = emptyMap(),
): List<RegionManifestEntry> {
    val countries = points.map(countryAt)
    val from = countries.firstOrNull()
    val to = countries.lastOrNull()
    val path = if (from != null && to != null) {
        landPath(from, to, neighbours, centres) { country -> country == from || country == to || regions.any { it.countryCode == country } }
    } else {
        null
    }
    val covering = regions.filter { it.regionId in installedRoutingIds }.toMutableList()
    val missing = mutableListOf<RegionManifestEntry>()
    val onLine = points.indices.filter { path == null || countries[it] in path }.groupBy { countries[it] }
    for (country in path ?: listOf(null)) {
        val indices = if (country == null) points.indices.toList() else onLine[country].orEmpty()
        if (indices.isEmpty() && country != null) {
            // La linea non ci passa (la Lituania, da San Marino a Riga): la regione del paese piu' vicina alla linea.
            val nearest = regions.filter { it.countryCode == country }.minByOrNull { region -> points.minOf { region.distance(it) } } ?: continue
            if (nearest !in covering) {
                missing += nearest
                covering += nearest
            }
            continue
        }
        for (i in indices) {
            val region = regionToDownload(regions, points[i], countries[i] ?: continue, installedRoutingIds, covering) ?: continue
            missing += region
            covering += region
        }
    }
    return missing
}

/**
 * La regione senza rete stradale da scaricare per [point], nel paese [country] secondo i confini veri; null se e' gia'
 * coperto ([covering]) o nessuna regione fa al caso. Fra quelle del paese che lo contengono, la piu' piccola (l'Italia
 * contiene San Marino, gli Stati Uniti sono divisi in stati). Se nessun riquadro del suo paese lo contiene (i riquadri
 * del catalogo sono approssimati: la penisola dei Curi e' Lituania ma fuori dal suo), la regione del paese col riquadro
 * piu' vicino; se il paese non ha regioni col suo codice, fra quelle che lo contengono con un codice che non e' un paese
 * ISO (le Canarie hanno "ic", i confini dicono "es"). Mai il riquadro di un altro paese: quello della Svezia copre
 * mezzo Baltico, quello dell'Italia l'Austria del sud.
 */
private fun regionToDownload(
    regions: List<RegionManifestEntry>,
    point: RoutePoint,
    country: String,
    installedRoutingIds: Set<String>,
    covering: List<RegionManifestEntry>,
): RegionManifestEntry? {
    val ofCountry = regions.filter { it.countryCode == country }
    val candidates = when {
        ofCountry.isEmpty() -> regions.filter { it.contains(point) && it.countryCode?.uppercase() !in ISO_COUNTRIES }
        ofCountry.any { it.contains(point) } -> ofCountry.filter { it.contains(point) }
        else -> listOfNotNull(ofCountry.minByOrNull { it.distance(point) })
    }
    if (candidates.any { it in covering }) return null
    return candidates.filter { it.regionId !in installedRoutingIds }.minByOrNull { it.area() }
}

/**
 * Il cammino via terra piu' corto fra due paesi, estremi compresi, sul grafo dei confini ([neighbours]) pesato con la
 * distanza fra i loro centri ([centres]), passando solo per i paesi [allowed] (quelli del catalogo). Null se non
 * c'e' (isole, paesi senza confini nel grafo).
 */
internal fun landPath(
    from: String,
    to: String,
    neighbours: Map<String, Set<String>>,
    centres: Map<String, RoutePoint>,
    allowed: (String) -> Boolean,
): List<String>? {
    if (from == to) return listOf(from)
    val distance = hashMapOf(from to 0.0)
    val previous = HashMap<String, String>()
    val queue = java.util.PriorityQueue<Pair<String, Double>>(compareBy { it.second })
    queue += from to 0.0
    while (queue.isNotEmpty()) {
        val (country, cost) = queue.poll()!!
        if (country == to) break
        if (cost > distance.getValue(country)) continue
        val centre = centres[country] ?: continue
        for (next in neighbours[country].orEmpty()) {
            if (!allowed(next)) continue
            val nextCost = cost + degrees(centre, centres[next] ?: continue)
            if (nextCost < (distance[next] ?: Double.MAX_VALUE)) {
                distance[next] = nextCost
                previous[next] = country
                queue += next to nextCost
            }
        }
    }
    if (to !in previous) return null
    return generateSequence(to) { previous[it] }.toList().reversed()
}

// Distanza in gradi, con la longitudine accorciata dalla latitudine: basta per confrontare cammini.
private fun degrees(a: RoutePoint, b: RoutePoint): Double =
    hypot((a.longitude - b.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2)), a.latitude - b.latitude)

private fun RegionManifestEntry.contains(point: RoutePoint): Boolean = map.source.let {
    point.longitude in it.minLon..it.maxLon && point.latitude in it.minLat..it.maxLat
}

private fun RegionManifestEntry.area(): Double = map.source.let { (it.maxLon - it.minLon) * (it.maxLat - it.minLat) }

// Distanza in gradi dal riquadro (0 dentro): basta per scegliere il piu' vicino.
private fun RegionManifestEntry.distance(point: RoutePoint): Double = map.source.let {
    hypot(maxOf(it.minLon - point.longitude, 0.0, point.longitude - it.maxLon), maxOf(it.minLat - point.latitude, 0.0, point.latitude - it.maxLat))
}

private val ISO_COUNTRIES = Locale.getISOCountries().toSet()
