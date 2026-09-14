package com.pockettravel.pipeline

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unisce N manifest.json (ciascuno con una sola voce in "regions", prodotto da
 * GenerateManifest per una singola nazione) in un unico manifest.json valido per lo schema
 * di RegionManifest (core/sync). Se una regionId compare in piu' manifest di input vince
 * l'ultimo fornito: permette a una pubblicazione parziale (workflow A3 nel piano) di
 * aggiornare solo le nazioni toccate mergiando col manifest gia' online, senza far sparire
 * le nazioni non toccate in questa run.
 */
fun mergeManifestJson(manifestJsons: List<String>): String {
    require(manifestJsons.isNotEmpty()) { "Nessun manifest da unire" }

    val regionsById = LinkedHashMap<String, JSONObject>()
    manifestJsons.forEach { json ->
        val root = JSONObject(json)
        val manifestVersion = root.getInt("manifestVersion")
        require(manifestVersion == 1) { "manifestVersion non supportata: $manifestVersion" }
        val regions = root.getJSONArray("regions")
        for (i in 0 until regions.length()) {
            val region = regions.getJSONObject(i)
            regionsById[region.getString("regionId")] = region
        }
    }

    val merged = JSONObject()
        .put("manifestVersion", 1)
        .put("regions", JSONArray(regionsById.values.toList()))
    return merged.toString(2)
}

fun main(args: Array<String>) {
    require(args.size >= 2) {
        "Uso: mergeManifests <output manifest.json> <input1.json> [input2.json ...]"
    }
    val outputFile = File(args[0])
    val inputFiles = args.drop(1).map { File(it) }
    inputFiles.forEach { require(it.exists()) { "Manifest non trovato: ${it.path}" } }

    val merged = mergeManifestJson(inputFiles.map { it.readText() })
    outputFile.writeText(merged)
    println("manifest unito (${inputFiles.size} input, ${JSONObject(merged).getJSONArray("regions").length()} regioni) scritto in ${outputFile.path}")
}
