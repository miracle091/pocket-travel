package com.pockettravel.core.tiles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

private const val HALF_WORLD_DEGREES = 180.0
private const val WORLD_DEGREES = 360.0

/** Intervallo di tile slippy-map (convenzione Google/OSM, la stessa di Hilbert.zxyToIndex) che copre un rettangolo lon/lat a un dato zoom. */
data class TileRange(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

/** Condiviso dall'estrazione della mappa, dalle celle dei civici e dall'impronta della mappa: stessa proiezione, stesso arrotondamento. */
fun tileRangeFor(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, zoom: Int): TileRange {
    val tilesPerAxis = 1 shl zoom
    fun lonToX(lon: Double) = (((lon + HALF_WORLD_DEGREES) / WORLD_DEGREES) * tilesPerAxis).toInt().coerceIn(0, tilesPerAxis - 1)
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
