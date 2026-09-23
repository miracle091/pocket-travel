package com.pockettravel.pipeline

import java.io.File
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mappa del mondo leggera inclusa nell'app (feature/map/src/main/assets/world/): confini dei paesi
 * Natural Earth 1:50m (dominio pubblico, include i microstati come San Marino e Vaticano, che la
 * 1:110m non ha), ridotti a quello che serve per disegnarli e renderli cliccabili:
 * - countries.geojson: poligoni con solo "iso" (ISO 3166-1 alpha-2 minuscolo, lo stesso di
 *   countryCode nel manifest) e coordinate arrotondate a 3 decimali (~100 m, invisibile agli zoom
 *   di una mappa del mondo);
 * - country-labels.geojson: un punto per paese (LABEL_X/LABEL_Y di Natural Earth) con "iso",
 *   "name" (nome italiano) e "rank" (LABELRANK, per mostrare prima le etichette dei paesi grandi).
 *
 * Uso: generateWorldMap <ne_50m_admin_0_countries.geojson> <cartella di output>
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "Uso: generateWorldMap <ne_50m_admin_0_countries.geojson> <cartella di output>" }
    val source = JSONObject(File(args[0]).readText())
    val outputDir = File(args[1]).apply { mkdirs() }

    val countries = JSONArray()
    val labels = JSONArray()
    val features = source.getJSONArray("features")
    for (i in 0 until features.length()) {
        val feature = features.getJSONObject(i)
        val props = feature.getJSONObject("properties")
        val iso = props.optString("ISO_A2_EH").lowercase().takeIf { it.length == 2 }
        val name = props.optString("NAME_IT").ifBlank { props.optString("NAME") }

        countries.put(
            JSONObject()
                .put("type", "Feature")
                .put("properties", JSONObject().apply { iso?.let { put("iso", it) } })
                .put("geometry", roundGeometry(feature.getJSONObject("geometry"))),
        )
        labels.put(
            JSONObject()
                .put("type", "Feature")
                .put(
                    "properties",
                    JSONObject().put("name", name).put("rank", props.optInt("LABELRANK", 5)).apply { iso?.let { put("iso", it) } },
                )
                .put(
                    "geometry",
                    JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(round(props.getDouble("LABEL_X")), round(props.getDouble("LABEL_Y"))))),
                ),
        )
    }

    fun write(name: String, features: JSONArray) {
        File(outputDir, name).writeText(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
    }
    write("countries.geojson", countries)
    write("country-labels.geojson", labels)
    println("mappa del mondo: ${countries.length()} paesi scritti in ${outputDir.path}")
}

private fun round(value: Double): Double = (value * 1000).roundToLong() / 1000.0

// Arrotonda ogni coordinata e toglie i vertici consecutivi diventati identici dopo l'arrotondamento.
private fun roundGeometry(geometry: JSONObject): JSONObject {
    fun ring(points: JSONArray): JSONArray {
        val out = JSONArray()
        var last: Pair<Double, Double>? = null
        for (i in 0 until points.length()) {
            val p = points.getJSONArray(i)
            val rounded = round(p.getDouble(0)) to round(p.getDouble(1))
            if (rounded != last || i == points.length() - 1) out.put(JSONArray(listOf(rounded.first, rounded.second)))
            last = rounded
        }
        return out
    }
    fun polygon(rings: JSONArray) = JSONArray((0 until rings.length()).map { ring(rings.getJSONArray(it)) })
    val coordinates = geometry.getJSONArray("coordinates")
    val rounded = when (geometry.getString("type")) {
        "Polygon" -> polygon(coordinates)
        "MultiPolygon" -> JSONArray((0 until coordinates.length()).map { polygon(coordinates.getJSONArray(it)) })
        else -> coordinates
    }
    return JSONObject().put("type", geometry.getString("type")).put("coordinates", rounded)
}
