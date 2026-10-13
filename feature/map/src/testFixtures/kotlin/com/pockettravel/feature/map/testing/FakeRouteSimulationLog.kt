package com.pockettravel.feature.map.testing

import com.pockettravel.feature.map.Route
import com.pockettravel.feature.map.RouteSimulationLog

class FakeRouteSimulationLog : RouteSimulationLog {
    val routes = mutableListOf<Route>()

    override fun log(route: Route) {
        routes += route
    }
}
