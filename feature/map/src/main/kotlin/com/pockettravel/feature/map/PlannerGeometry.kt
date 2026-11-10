package com.pockettravel.feature.map

import com.pockettravel.core.data.MapBounds
import kotlin.math.cos

private const val METERS_PER_DEGREE = 111_320.0

// Rettangolo che contiene il cerchio di [radiusMeters] attorno a [center]: un grado di longitudine si accorcia con il
// coseno della latitudine, quindi il lato est-ovest in gradi cresce allontanandosi dall'equatore.
internal fun boundsAround(center: RoutePoint, radiusMeters: Int): MapBounds {
    val dLat = radiusMeters / METERS_PER_DEGREE
    val dLon = dLat / cos(Math.toRadians(center.latitude))
    return MapBounds(center.longitude - dLon, center.latitude - dLat, center.longitude + dLon, center.latitude + dLat)
}
