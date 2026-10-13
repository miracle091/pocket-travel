package com.pockettravel.app.regions

import android.content.Context
import com.pockettravel.feature.map.RoutePoint
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

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
