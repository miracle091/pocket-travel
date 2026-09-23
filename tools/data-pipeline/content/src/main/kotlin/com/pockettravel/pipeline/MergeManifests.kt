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
 *
 * continents (regionId -> continente, da pilot-regions.sh) viene scritto nel campo "continent" di
 * ogni regione presente, anche di quelle non ricostruite in questa run: l'app raggruppa
 * l'elenco regioni per continente e non ha altra fonte per saperlo.
 */
fun mergeManifestJson(manifestJsons: List<String>, continents: Map<String, String> = emptyMap()): String {
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

    regionsById.forEach { (regionId, region) -> continents[regionId]?.let { region.put("continent", it) } }

    val merged = JSONObject()
        .put("manifestVersion", 1)
        .put("regions", JSONArray(regionsById.values.toList()))
    return merged.toString(2)
}

fun main(args: Array<String>) {
    // --continents <file.tsv> opzionale in testa: righe "regionId<TAB>continente".
    val continentsFile = if (args.firstOrNull() == "--continents") File(args[1]) else null
    val rest = if (continentsFile != null) args.drop(2) else args.toList()
    require(rest.size >= 2) {
        "Uso: mergeManifests [--continents <regioni.tsv>] <output manifest.json> <input1.json> [input2.json ...]"
    }
    val continents = continentsFile?.readLines()
        ?.filter { it.isNotBlank() }
        ?.associate { line -> line.substringBefore('\t') to line.substringAfter('\t') }
        .orEmpty()
    val outputFile = File(rest[0])
    val inputFiles = rest.drop(1).map { File(it) }
    inputFiles.forEach { require(it.exists()) { "Manifest non trovato: ${it.path}" } }

    val merged = mergeManifestJson(inputFiles.map { it.readText() }, continents)
    outputFile.writeText(merged)
    println("manifest unito (${inputFiles.size} input, ${JSONObject(merged).getJSONArray("regions").length()} regioni) scritto in ${outputFile.path}")
}
