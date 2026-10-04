package com.pockettravel.feature.map

import com.pockettravel.core.data.CarRoute
import com.pockettravel.core.data.CarRouteCalculator
import com.pockettravel.core.data.db.CityCoordinates

/** [CarRouteCalculator] con il motore del Navigatore e il profilo dell'auto, sulla rete stradale della sola regione. */
class BRouterCarRouteCalculator(private val routeEngineFactory: RouteEngineFactory) : CarRouteCalculator {
    override suspend fun carRoute(regionId: String, from: CityCoordinates, to: CityCoordinates): CarRoute? {
        val engine = routeEngineFactory.create(listOf(regionId))
        return when (val result = engine.route(from.toRoutePoint(), to.toRoutePoint(), profile = UsageMode.AUTO.routingProfile)) {
            is RouteResult.Found -> CarRoute(result.route.distanceMeters, result.route.durationSeconds)
            RouteResult.NoRoutingData, RouteResult.NotFound, RouteResult.TimedOut, is RouteResult.Failed -> null
        }
    }
}

private fun CityCoordinates.toRoutePoint() = RoutePoint(latitude, longitude)
