package com.pockettravel.app.regions

import android.content.Context
import com.pockettravel.feature.map.RoutePoint
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

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

/** [CountryLocator] sui confini Natural Earth gia' nell'app per la mappa delle nazioni (asset world/countries.geojson). */
@Singleton
class AssetCountryLocator @Inject constructor(@ApplicationContext private val context: Context) : CountryLocator {
    private val countries: List<Pair<String, List<List<DoubleArray>>>> by lazy { load() }

    override fun countryAt(latitude: Double, longitude: Double): String? =
        countries.firstOrNull { (_, polygons) -> polygons.any { rings -> contains(rings, longitude, latitude) } }?.first

    /**
     * I paesi confinanti via terra: quelli che hanno un vertice in comune (i confini Natural Earth sono coerenti, i
     * vicini condividono i vertici del confine). La Svezia non confina con la Lettonia, l'Italia si' con l'Austria.
     */
    override val neighbours: Map<String, Set<String>> by lazy {
        val owners = HashMap<Long, MutableSet<String>>()
        countries.forEach { (iso, polygons) ->
            polygons.forEach { rings ->
                rings.forEach { ring ->
                    for (k in ring.indices step 2) owners.getOrPut(vertexKey(ring[k], ring[k + 1])) { mutableSetOf() } += iso
                }
            }
        }
        val result = HashMap<String, MutableSet<String>>()
        owners.values.filter { it.size > 1 }.forEach { shared -> shared.forEach { a -> result.getOrPut(a) { mutableSetOf() } += shared - a } }
        result
    }

    /** Il centro del riquadro della parte piu' grande di ogni paese (per la Russia non Kaliningrad): per le distanze fra paesi. */
    override val centres: Map<String, RoutePoint> by lazy {
        countries.mapNotNull { (iso, polygons) -> countryCentre(polygons)?.let { iso to it } }.toMap()
    }

    private fun load(): List<Pair<String, List<List<DoubleArray>>>> {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val features = JSONObject(text).getJSONArray("features")
        return (0 until features.length()).mapNotNull { i ->
            val feature = features.getJSONObject(i)
            val iso = feature.optJSONObject("properties")?.optString("iso")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val geometry = feature.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")
            val polygons = when (geometry.optString("type")) {
                "Polygon" -> listOf(rings(coordinates))
                "MultiPolygon" -> (0 until coordinates.length()).map { rings(coordinates.getJSONArray(it)) }
                else -> return@mapNotNull null
            }
            iso to polygons
        }
    }

    private fun rings(polygon: JSONArray): List<DoubleArray> = (0 until polygon.length()).map { r ->
        val ring = polygon.getJSONArray(r)
        DoubleArray(ring.length() * 2) { k -> ring.getJSONArray(k / 2).getDouble(k % 2) }
    }

    private companion object {
        const val ASSET = "world/countries.geojson"
        const val VERTEX_SCALE = 10_000
        const val LATITUDE_STRIDE = 4_000_000L

        // Vertice arrotondato a 10^-4 gradi (circa 10 m): lo stesso punto di confine nei due paesi.
        fun vertexKey(lon: Double, lat: Double): Long = Math.round(lon * VERTEX_SCALE) * LATITUDE_STRIDE + Math.round(lat * VERTEX_SCALE)

        // Ray casting sull'anello esterno meno i buchi; ring = lon, lat alternati.
        fun contains(rings: List<DoubleArray>, x: Double, y: Double): Boolean =
            rings.isNotEmpty() && inside(rings[0], x, y) && rings.drop(1).none { inside(it, x, y) }

        fun inside(ring: DoubleArray, x: Double, y: Double): Boolean {
            var result = false
            var j = ring.size - 2
            var i = 0
            while (i < ring.size) {
                val xi = ring[i]; val yi = ring[i + 1]; val xj = ring[j]; val yj = ring[j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) result = !result
                j = i
                i += 2
            }
            return result
        }
    }
}

// Anelli esterni utilizzabili: un poligono senza anelli o con l'anello vuoto si salta.
private fun usableOuterRings(polygons: List<List<DoubleArray>>): List<DoubleArray> =
    polygons.mapNotNull { it.firstOrNull() }.filter { it.size >= 2 }

// Longitudini senza salto a 180: se il riquadro e' piu' largo di 180 gradi il poligono attraversa
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

/** Il centro del riquadro del poligono piu' grande; null se nessun poligono ha un anello utilizzabile. */
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
