package com.pockettravel.app.regions

import com.pockettravel.feature.map.RoutePoint
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Il paese (codice ISO 3166-1 alpha-2 minuscolo) in cui cade un punto, offline e senza servizi di geocodifica.
 * Da chiamare fuori dal main thread: [AssetCountryLocator] la prima volta legge 1,6 MB.
 */
interface CountryLocator {
    fun countryAt(latitude: Double, longitude: Double): String?

    /** I paesi confinanti via terra, per codice. */
    val neighbours: Map<String, Set<String>>

    /** Il centro di ogni paese, per le distanze fra paesi. */
    val centres: Map<String, RoutePoint>
}

// Anelli esterni utilizzabili: un poligono senza anelli o con l'anello vuoto si salta.
private fun usableOuterRings(polygons: List<List<DoubleArray>>): List<DoubleArray> =
    polygons.mapNotNull { it.firstOrNull() }.filter { it.size >= 2 }

// Longitudini senza salto a 180: se il rettangolo e' piu' largo di 180 gradi il poligono attraversa
// l'antimeridiano e le longitudini negative si spostano di +360.
private fun unwrappedLongitudes(ring: DoubleArray): List<Double> {
    val lons = ring.filterIndexed { i, _ -> i % 2 == 0 }
    return if (lons.max() - lons.min() > HALF_TURN) lons.map { if (it < 0) it + FULL_TURN else it } else lons
}

private fun latitudes(ring: DoubleArray): List<Double> = ring.filterIndexed { i, _ -> i % 2 == 1 }

private fun boxArea(ring: DoubleArray): Double {
    val lons = unwrappedLongitudes(ring)
    val lats = latitudes(ring)
    return (lons.max() - lons.min()) * (lats.max() - lats.min())
}

private const val HALF_TURN = 180.0
private const val FULL_TURN = 360.0

/** Il centro del rettangolo del poligono piu' grande; null se nessun poligono ha un anello utilizzabile. */
internal fun countryCentre(polygons: List<List<DoubleArray>>): RoutePoint? =
    usableOuterRings(polygons).maxByOrNull(::boxArea)?.let { outer ->
        val lons = unwrappedLongitudes(outer)
        val lats = latitudes(outer)
        val lon = (lons.min() + lons.max()) / 2
        RoutePoint((lats.min() + lats.max()) / 2, if (lon > HALF_TURN) lon - FULL_TURN else lon)
    }

/** Lega [AssetCountryLocator] all'interfaccia che i ViewModel iniettano. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CountryLocatorModule {
    @Binds
    abstract fun bindCountryLocator(impl: AssetCountryLocator): CountryLocator
}
