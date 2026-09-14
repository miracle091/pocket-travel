package com.pockettravel.feature.map

data class RoutePoint(val latitude: Double, val longitude: Double)

data class Route(
    val points: List<RoutePoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

interface RouteEngine {
    fun route(from: RoutePoint, to: RoutePoint): Route?
}
