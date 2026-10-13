package com.pockettravel.app.regions

import com.pockettravel.feature.map.RoutePoint

/** [CountryLocator] senza asset: risponde con [country] per ogni punto (null = in mare) e conta le richieste in [lookups]. */
class FakeCountryLocator(var country: String? = null) : CountryLocator {
    var lookups = 0
        private set

    override val neighbours: Map<String, Set<String>> = emptyMap()
    override val centres: Map<String, RoutePoint> = emptyMap()

    override fun countryAt(latitude: Double, longitude: Double): String? {
        lookups++
        return country
    }
}
