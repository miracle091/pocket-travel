package com.pockettravel.pipeline

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unisce N manifest.json (frammenti di GenerateManifest: una regione ciascuno, oppure il solo
 * pacchetto guide) in un unico manifest.json valido per lo schema v2 di RegionManifest
 * (core/sync). Se una regionId compare in piu' manifest di input vince l'ultimo fornito, e lo
 * stesso vale per la voce "guides": permette a una pubblicazione parziale (publish-regions.yml) di
 * aggiornare solo cio' che e' stato rigenerato mergiando col manifest gia' online,
 * senza far sparire il resto.
 *
 * Gli input possono essere anche in formato v1 (il manifest pubblicato prima della separazione
 * in pacchetti, o un frammento riusato da quello): ogni regione v1 viene convertita, con il
 * content.db pubblicato come pacchetto POI (contiene gia' la tabella "poi", che e' tutto cio' che
 * l'app ne legge) — le regioni non ancora rigenerate restano cosi' scaricabili senza doverle
 * rigenerare tutte nella stessa run. Le loro guide arrivano invece da guides.db.
 *
 * continents e countryCodes (regionId -> continente / codice ISO 3166-1 alpha-2 minuscolo, da
 * pilot-regions.sh) vengono scritti nei campi "continent" e "countryCode" di ogni regione presente,
 * anche di quelle non ricostruite in questa run: l'app raggruppa l'elenco per continente e trova le
 * regioni di un paese toccato sulla mappa del mondo, e non ha altra fonte per saperlo. Allo stesso
 * modo i "wikivoyageUrls" del frammento guide finiscono nel campo "wikivoyageUrl" delle regioni.
 *
 * groups (regionId -> groupName, groupLabel, da pilot-regions.sh) finisce nei campi "groupName" e
 * "groupLabel": l'app raccoglie sotto un'unica voce le regioni dello stesso paese (es. gli stati USA).
 * replacedRegions (regionId di una regione tolta -> groupName delle regioni che la sostituiscono): la regione
 * tolta resta finche' tutte le regioni del gruppo sono nel manifest, poi sparisce e finisce nel campo
 * "replacedRegions", con cui l'app propone le regioni nuove a chi ha ancora installata quella vecchia.
 *
 * knownRegionIds (le regioni di pilot-regions.sh), se indicato, scarta le regioni che non ne fanno
 * piu' parte: senza, una regione tolta dal lotto pilota resterebbe per sempre nel manifest,
 * ricopiata a ogni run da quello gia' pubblicato.
 */
fun mergeManifestJson(
    manifestJsons: List<String>,
    continents: Map<String, String> = emptyMap(),
    countryCodes: Map<String, String> = emptyMap(),
    knownRegionIds: Set<String>? = null,
    groups: Map<String, Pair<String, String>> = emptyMap(),
    replacedRegions: Map<String, String> = emptyMap(),
    // Build Protomaps corrente: sourceUrl di tutte le regioni (le build vecchie spariscono in ~6 giorni).
    mapSourceUrl: String? = null,
): String {
    require(manifestJsons.isNotEmpty()) { "Nessun manifest da unire" }

    val regionsById = LinkedHashMap<String, JSONObject>()
    val wikivoyageUrls = mutableMapOf<String, String>()
    var guides: JSONObject? = null
    manifestJsons.forEach { json ->
        val root = JSONObject(json)
        val manifestVersion = root.getInt("manifestVersion")
        require(manifestVersion == 1 || manifestVersion == MANIFEST_VERSION) { "manifestVersion non supportata: $manifestVersion" }
        root.optJSONObject("guides")?.let { guides = it }
        root.optJSONObject("wikivoyageUrls")?.let { urls -> urls.keySet().forEach { wikivoyageUrls[it] = urls.getString(it) } }
        val regions = root.getJSONArray("regions")
        for (i in 0 until regions.length()) {
            val region = regions.getJSONObject(i)
            regionsById[region.getString("regionId")] = if (region.has("files")) convertV1Region(region) else region
        }
    }

    // Una regione sostituita resta (com'e', senza aggiornamenti) finche' tutte le regioni del suo gruppo
    // non sono nel manifest: senza, al primo run dopo la divisione il paese sparirebbe per giorni,
    // finche' il calendario settimanale non ha pubblicato tutte le regioni nuove.
    val completed = replacedRegions.filter { (_, groupName) ->
        val members = groups.filterValues { it.first == groupName }.keys
        members.isNotEmpty() && members.all { it in regionsById }
    }
    knownRegionIds?.let { known -> regionsById.keys.retainAll(known + (replacedRegions.keys - completed.keys)) }

    regionsById.forEach { (regionId, region) ->
        continents[regionId]?.let { region.put("continent", it) }
        countryCodes[regionId]?.let { region.put("countryCode", it) }
        wikivoyageUrls[regionId]?.let { region.put("wikivoyageUrl", it) }
        mapSourceUrl?.let { url -> region.optJSONObject("map")?.optJSONObject("source")?.put("sourceUrl", url) }
        val group = groups[regionId]
        if (group != null) {
            region.put("groupName", group.first).put("groupLabel", group.second)
        } else if (knownRegionIds != null) {
            // Con l'elenco completo delle regioni, chi non ha gruppo non deve tenerne uno vecchio.
            region.remove("groupName")
            region.remove("groupLabel")
        }
    }

    val merged = JSONObject().put("manifestVersion", MANIFEST_VERSION)
    guides?.let { merged.put("guides", it) }
    merged.put("regions", JSONArray(regionsById.values.toList()))
    if (completed.isNotEmpty()) {
        merged.put(
            "replacedRegions",
            JSONArray(completed.map { (regionId, groupName) -> JSONObject().put("regionId", regionId).put("groupName", groupName) }),
        )
    }
    return merged.toString(2)
}

/** Regione v1 (files = content.db + .rd5, mapSource, una sola version) -> regione v2. */
internal fun convertV1Region(region: JSONObject): JSONObject {
    val regionId = region.getString("regionId")
    val version = region.getString("version")
    val files = region.getJSONArray("files").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    val contentDb = requireNotNull(files.firstOrNull { it.getString("name") == "content.db" }) {
        "La regione v1 $regionId non ha content.db"
    }
    val mapSource = requireNotNull(region.optJSONObject("mapSource")) { "La regione v1 $regionId non ha mapSource" }

    val converted = JSONObject()
        .put("regionId", regionId)
        .put("displayName", region.getString("displayName"))
        .put("updatedAt", region.getString("updatedAt"))
        .put("map", JSONObject().put("version", version).put("source", mapSource))
        .put("routing", JSONObject().put("version", version).put("files", JSONArray(files.filter { it.getString("name").endsWith(".rd5") })))
        .put("poi", JSONObject().put("version", version).put("file", JSONObject(contentDb.toString()).put("name", "poi.db")))
    listOf("continent", "countryCode", "wikivoyageUrl").forEach { key -> region.optString(key).ifBlank { null }?.let { converted.put(key, it) } }
    return converted
}

fun main(args: Array<String>) {
    // Opzioni in testa: --continents <file.tsv> (righe "regionId<TAB>continente<TAB>codicePaese<TAB>
    // groupName<TAB>groupLabel") e --replaced <file.tsv> (righe "regionId tolta<TAB>groupName").
    var continentsFile: File? = null
    var replacedFile: File? = null
    var mapSourceUrl: String? = null
    var rest = args.toList()
    while (rest.firstOrNull()?.startsWith("--") == true) {
        when (rest[0]) {
            "--continents" -> continentsFile = File(rest[1])
            "--replaced" -> replacedFile = File(rest[1])
            "--map-source-url" -> mapSourceUrl = rest[1]
            else -> error("Opzione sconosciuta: ${rest[0]}")
        }
        rest = rest.drop(2)
    }
    require(rest.size >= 2) {
        "Uso: mergeManifests [--continents <regioni.tsv>] [--replaced <sostituite.tsv>] [--map-source-url <url>] <output manifest.json> <input1.json> [input2.json ...]"
    }
    val rows = continentsFile?.readLines()?.filter { it.isNotBlank() }?.map { it.split('\t') }.orEmpty()
    val continents = rows.filter { it.size >= 2 }.associate { it[0] to it[1] }
    val countryCodes = rows.filter { it.size >= 3 && it[2].isNotBlank() }.associate { it[0] to it[2] }
    val groups = rows.filter { it.size >= 5 && it[3].isNotBlank() }.associate { it[0] to (it[3] to it[4]) }
    val replaced = replacedFile?.readLines()?.filter { it.isNotBlank() }?.map { it.split('\t') }
        ?.associate { it[0] to it[1] }.orEmpty()
    val outputFile = File(rest[0])
    val inputFiles = rest.drop(1).map { File(it) }
    inputFiles.forEach { require(it.exists()) { "Manifest non trovato: ${it.path}" } }

    // La tabella --continents elenca tutte le regioni di pilot-regions.sh: chi non c'e' e' stata tolta.
    val knownRegionIds = continentsFile?.let { rows.map { it[0] }.toSet() }?.takeIf { it.isNotEmpty() }
    val merged = mergeManifestJson(inputFiles.map { it.readText() }, continents, countryCodes, knownRegionIds, groups, replaced, mapSourceUrl)
    outputFile.writeText(merged)
    println("manifest unito (${inputFiles.size} input, ${JSONObject(merged).getJSONArray("regions").length()} regioni) scritto in ${outputFile.path}")
}
