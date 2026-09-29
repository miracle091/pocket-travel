package com.pockettravel.app.regions

import android.content.Context
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
