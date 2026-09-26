package com.pockettravel.pipeline

import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** sourceKey: solo per i .rd5 ritagliati sulla regione (clip_rd5.py), "<dimensione del file originale
 *  di brouter.de> <riquadro> <margine>"; build-region.sh la confronta con quella attesa per capire se
 *  la tile va riscaricata o ritagliata di nuovo (sizeBytes e' quella del file ritagliato). Non letto dall'app. */
data class ManifestFileEntry(val name: String, val url: String, val sizeBytes: Long, val sha256: String, val sourceKey: String? = null)

/** bbox+sorgente per l'estrazione lato device di map.pmtiles (vedi PmtilesExtractor, core:sync) —
 *  non un file da impacchettare, la build Protomaps e' letta via HTTP range direttamente dal device. */
data class MapSourceInput(
    val sourceUrl: String,
    val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double,
    val minZoom: Int, val maxZoom: Int,
)

/** Versione del formato di manifest.json prodotto dalla pipeline (vedi RegionManifest.kt, core/sync). */
const val MANIFEST_VERSION = 2

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(file.inputStream(), digest).use { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (stream.read(buffer) != -1) {
            // il digest si aggiorna come effetto collaterale di DigestInputStream.read
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** Per un file generato/ospitato da noi (poi.db, guides.db): hash calcolato sul file locale. */
fun localFileEntry(file: File, name: String, url: String): ManifestFileEntry =
    ManifestFileEntry(name = name, url = url, sizeBytes = file.length(), sha256 = sha256Of(file))

private fun ManifestFileEntry.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("url", url)
    .put("sizeBytes", sizeBytes)
    .put("sha256", sha256)
    .putOpt("sourceKey", sourceKey)

private fun MapSourceInput.toJson(): JSONObject = JSONObject()
    .put("sourceUrl", sourceUrl)
    .put("minLon", minLon)
    .put("minLat", minLat)
    .put("maxLon", maxLon)
    .put("maxLat", maxLat)
    .put("minZoom", minZoom)
    .put("maxZoom", maxZoom)

/**
 * Frammento manifest (schema v2, vedi RegionManifest.kt in core/sync) di una regione rigenerata
 * per intero: i tre pacchetti della regione (mappa, routing, POI) hanno ciascuno la propria
 * versione, qui tutte uguali perche' appena rigenerati insieme. I segmenti .rd5 non sono file
 * locali per QUESTO tool (a differenza di poi.db) — il chiamante li passa gia' come
 * ManifestFileEntry con sha256/sizeBytes calcolati scaricandoli e hashandoli una volta (vedi
 * tools/data-pipeline/scripts/build-region.sh), che li scrive anche su disco perche' vengano
 * ri-ospitati: url punta quindi al nostro host, non a brouter.de.
 */
fun buildRegionFragmentJson(
    regionId: String,
    displayName: String,
    version: String,
    updatedAt: String,
    mapSource: MapSourceInput,
    routingFiles: List<ManifestFileEntry>,
    poiFile: ManifestFileEntry,
    // POI extra (scaricati solo su richiesta): assente se la regione non ne ha.
    poiExtraFile: ManifestFileEntry? = null,
): String {
    require(routingFiles.isNotEmpty()) { "Il routing di $regionId non contiene segmenti .rd5" }

    val region = JSONObject()
        .put("regionId", regionId)
        .put("displayName", displayName)
        .put("updatedAt", updatedAt)
        .put("map", JSONObject().put("version", version).put("source", mapSource.toJson()))
        .put("routing", JSONObject().put("version", version).put("files", JSONArray(routingFiles.map { it.toJson() })))
        .put("poi", JSONObject().put("version", version).put("file", poiFile.toJson()))
    poiExtraFile?.let { region.put("poiExtra", JSONObject().put("version", version).put("file", it.toJson())) }

    return JSONObject()
        .put("manifestVersion", MANIFEST_VERSION)
        .put("regions", JSONArray().put(region))
        .toString(2)
}

/**
 * Frammento manifest del pacchetto guide unico (vedi build-guides.sh): nessuna regione, solo la
 * voce "guides". wikivoyageUrls (regionId -> pagina da cui viene la guida) non e' letto dall'app:
 * mergeManifests lo scrive nel campo wikivoyageUrl di ogni regione per la pagina index.html del sito.
 */
fun buildGuidesFragmentJson(version: String, file: ManifestFileEntry, wikivoyageUrls: Map<String, String>): String =
    JSONObject()
        .put("manifestVersion", MANIFEST_VERSION)
        .put("guides", JSONObject().put("version", version).put("file", file.toJson()))
        .put("wikivoyageUrls", JSONObject(wikivoyageUrls))
        .put("regions", JSONArray())
        .toString(2)

/**
 * Legge una "spec" JSON prodotta dall'orchestratore batch (build-region.sh) invece di N
 * argomenti posizionali: contiene gia' gli hash dei segmenti .rd5 (calcolati scaricando e
 * hashando lo stream, vedi sopra) e il bbox per l'estrazione mappa lato device.
 *
 * Formato atteso:
 * {
 *   "regionId": "sm", "displayName": "San Marino", "version": "2026.09.14",
 *   "poiDb": { "path": "/abs/poi.db", "url": "https://.../poi.db" },
 *   "poiExtraDb": { "path": "/abs/poi-extra.db", "url": "https://.../poi-extra.db" },   (facoltativo)
 *   "routingFiles": [ { "name": "E10_N40.rd5", "url": "...", "sizeBytes": 123, "sha256": "..." } ],
 *   "mapSource": { "sourceUrl": "...", "minLon": .., "minLat": .., "maxLon": .., "maxLat": .., "minZoom": 0, "maxZoom": 14 }
 * }
 */
fun buildRegionFragmentJsonFromSpec(specJson: String): String {
    val spec = JSONObject(specJson)
    val poiDb = spec.getJSONObject("poiDb")
    val routingFiles = spec.getJSONArray("routingFiles").let { files ->
        (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            ManifestFileEntry(
                name = f.getString("name"),
                url = f.getString("url"),
                sizeBytes = f.getLong("sizeBytes"),
                sha256 = f.getString("sha256"),
                sourceKey = f.optString("sourceKey").ifEmpty { null },
            )
        }
    }
    val ms = spec.getJSONObject("mapSource")
    return buildRegionFragmentJson(
        regionId = spec.getString("regionId"),
        displayName = spec.getString("displayName"),
        version = spec.getString("version"),
        updatedAt = Instant.now().toString(),
        mapSource = MapSourceInput(
            sourceUrl = ms.getString("sourceUrl"),
            minLon = ms.getDouble("minLon"), minLat = ms.getDouble("minLat"),
            maxLon = ms.getDouble("maxLon"), maxLat = ms.getDouble("maxLat"),
            minZoom = ms.getInt("minZoom"), maxZoom = ms.getInt("maxZoom"),
        ),
        routingFiles = routingFiles,
        poiFile = localFileEntry(File(poiDb.getString("path")), "poi.db", poiDb.getString("url")),
        poiExtraFile = spec.optJSONObject("poiExtraDb")?.let {
            localFileEntry(File(it.getString("path")), "poi-extra.db", it.getString("url"))
        },
    )
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--guides") {
        require(args.size == 6) {
            "Uso: generateManifest --guides <guides.db> <url> <version> <regioni.tsv di generateGuides> <output frammento.json>"
        }
        val wikivoyageUrls = File(args[4]).readLines().filter { it.isNotBlank() }
            .map { it.split('\t') }.filter { it.size >= 3 && it[2].isNotBlank() }.associate { it[0] to it[2] }
        val fragment = buildGuidesFragmentJson(args[3], localFileEntry(File(args[1]), "guides.db", args[2]), wikivoyageUrls)
        File(args[5]).writeText(fragment)
        println("frammento guide scritto in ${args[5]}")
        return
    }
    require(args.size == 2) { "Uso: generateManifest <spec.json> <output frammento.json>" }
    File(args[1]).writeText(buildRegionFragmentJsonFromSpec(File(args[0]).readText()))
    println("frammento manifest scritto in ${args[1]}")
}
