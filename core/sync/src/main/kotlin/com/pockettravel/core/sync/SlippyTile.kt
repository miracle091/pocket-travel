package com.pockettravel.core.sync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/** Intervallo di tile slippy-map (convenzione Google/OSM, la stessa di Hilbert.zxyToIndex) che copre un riquadro lon/lat a un dato zoom. */
internal data class TileRange(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

/** Condiviso da PmtilesExtractor (bounding box di una regione) e regionGridCells (bounding box di una cella): stessa proiezione, stesso arrotondamento. */
internal fun tileRangeFor(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, zoom: Int): TileRange {
    val tilesPerAxis = 1 shl zoom
    fun lonToX(lon: Double) = (((lon + 180.0) / 360.0) * tilesPerAxis).toInt().coerceIn(0, tilesPerAxis - 1)
    fun latToY(lat: Double): Int {
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * tilesPerAxis
        return y.toInt().coerceIn(0, tilesPerAxis - 1)
    }
    return TileRange(
        minX = lonToX(minLon),
        maxX = lonToX(maxLon),
        minY = latToY(maxLat), // latitudine massima -> Y minore
        maxY = latToY(minLat),
    )
}
