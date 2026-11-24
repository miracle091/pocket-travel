package com.pockettravel.feature.map

import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

internal fun lineString(points: List<RoutePoint>): LineString =
    LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })

/** Layer [id] che disegna la sorgente [sourceId] come linea di [color] e [width] con estremi e giunzioni arrotondati. */
internal fun roundedLineLayer(id: String, sourceId: String, color: Int, width: Float, opacity: Float? = null): LineLayer =
    LineLayer(id, sourceId).withProperties(
        PropertyFactory.lineColor(color), PropertyFactory.lineWidth(width),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ).also { layer -> opacity?.let { layer.setProperties(PropertyFactory.lineOpacity(it)) } }
