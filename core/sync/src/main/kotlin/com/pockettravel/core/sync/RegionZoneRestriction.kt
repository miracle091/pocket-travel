package com.pockettravel.core.sync

import com.pockettravel.core.data.RegionZone

/** Riquadro in gradi di una tile .rd5 di BRouter. */
internal data class TileBounds(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double)

private val RD5_NAME = Regex("^([EW])(\\d+)_([NS])(\\d+)\\.rd5$")

/** "E10_N45.rd5" -> 10..15 E, 45..50 N (BRouter nomina le tile 5x5 dall'angolo sud-ovest); null se il nome e' diverso. */
internal fun rd5TileBounds(name: String): TileBounds? {
    val match = RD5_NAME.matchEntire(name) ?: return null
    val (ew, lon, ns, lat) = match.destructured
    val minLon = lon.toDouble() * if (ew == "W") -1 else 1
    val minLat = lat.toDouble() * if (ns == "S") -1 else 1
    return TileBounds(minLon, minLat, minLon + RD5_TILE_DEGREES, minLat + RD5_TILE_DEGREES)
}

private const val RD5_TILE_DEGREES = 5.0

/**
 * La regione limitata a [zone] (null = tutta): la mappa si estrae solo dal riquadro della zona, dentro quello della
 * regione; la rete stradale solo dalle tile .rd5 che la toccano; i civici solo dalle celle che la toccano (nessuna = niente
 * civici). Punti di interesse, guide e mezzi pubblici restano interi. Le versioni non cambiano, tranne quella dei
 * civici che si calcola dalle celle: per questo va applicata sia dove si confrontano le versioni sia prima di installare.
 */
fun RegionManifestEntry.restrictedTo(zone: RegionZone?): RegionManifestEntry {
    if (zone == null) return this
    val source = map.source
    val minLon = maxOf(zone.minLon, source.minLon)
    val minLat = maxOf(zone.minLat, source.minLat)
    val maxLon = minOf(zone.maxLon, source.maxLon)
    val maxLat = minOf(zone.maxLat, source.maxLat)
    if (minLon >= maxLon || minLat >= maxLat) return this
    val bbox = source.copy(minLon = minLon, minLat = minLat, maxLon = maxLon, maxLat = maxLat)
    val files = routing.files.filter { file ->
        val tile = rd5TileBounds(file.name) ?: return@filter true
        tile.minLon < maxLon && tile.maxLon > minLon && tile.minLat < maxLat && tile.maxLat > minLat
    }
    val cells = addressGrid?.let { grid -> regionGridCells(AddressGridIndex(version = "", tileZoom = 0, cells = grid.cells), bbox) }
    return copy(
        map = map.copy(source = bbox),
        routing = routing.copy(files = files),
        addressGrid = cells?.takeIf { it.isNotEmpty() }?.let(::RegionAddressGridEntry),
    )
}
