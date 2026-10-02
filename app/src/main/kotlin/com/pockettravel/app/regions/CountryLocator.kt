package com.pockettravel.app.regions

import android.content.Context
import com.pockettravel.feature.map.RoutePoint
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Il paese (codice ISO 3166-1 alpha-2 minuscolo) in cui cade un punto, dai confini Natural Earth gia' nell'app
 * per la mappa del mondo (asset world/countries.geojson): offline, senza servizi di geocodifica. Da chiamare
 * fuori dal main thread (la prima volta legge 1,6 MB).
 */
@Singleton
class CountryLocator @Inject constructor(@ApplicationContext private val context: Context) {
    private val countries: List<Pair<String, List<List<DoubleArray>>>> by lazy { load() }

    fun countryAt(latitude: Double, longitude: Double): String? =
        countries.firstOrNull { (_, polygons) -> polygons.any { rings -> contains(rings, longitude, latitude) } }?.first

    /**
     * I paesi confinanti via terra: quelli che hanno un vertice in comune (i confini Natural Earth sono coerenti, i
     * vicini condividono i vertici del confine). La Svezia non confina con la Lettonia, l'Italia si' con l'Austria.
     */
    val neighbours: Map<String, Set<String>> by lazy {
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
    val centres: Map<String, RoutePoint> by lazy {
        countries.associate { (iso, polygons) ->
            val outer = polygons.map { it[0] }.maxBy { ring -> boxArea(ring) }
            val lons = outer.filterIndexed { i, _ -> i % 2 == 0 }
            val lats = outer.filterIndexed { i, _ -> i % 2 == 1 }
            iso to RoutePoint((lats.min() + lats.max()) / 2, (lons.min() + lons.max()) / 2)
        }
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

        // Vertice arrotondato a 10^-4 gradi (circa 10 m): lo stesso punto di confine nei due paesi.
        fun vertexKey(lon: Double, lat: Double): Long = Math.round(lon * 10_000) * 4_000_000L + Math.round(lat * 10_000)

        fun boxArea(ring: DoubleArray): Double {
            val lons = ring.filterIndexed { i, _ -> i % 2 == 0 }
            val lats = ring.filterIndexed { i, _ -> i % 2 == 1 }
            return (lons.max() - lons.min()) * (lats.max() - lats.min())
        }

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
