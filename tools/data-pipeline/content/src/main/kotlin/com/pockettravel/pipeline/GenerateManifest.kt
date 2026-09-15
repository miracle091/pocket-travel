package com.pockettravel.pipeline

import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class ManifestFileEntry(val name: String, val url: String, val sizeBytes: Long, val sha256: String)

/** bbox+sorgente per l'estrazione lato device di map.pmtiles (vedi PmtilesExtractor, core:sync) —
 *  non un file da impacchettare, la build Protomaps e' letta via HTTP range direttamente dal device. */
data class MapSourceInput(
    val sourceUrl: String,
    val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double,
    val minZoom: Int, val maxZoom: Int,
)

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

/** Per un file generato/ospitato da noi (oggi solo content.db): hash calcolato sul file locale. */
fun localFileEntry(file: File, name: String, url: String): ManifestFileEntry =
    ManifestFileEntry(name = name, url = url, sizeBytes = file.length(), sha256 = sha256Of(file))

/**
 * Schema atteso da RegionManifest.kt/RegionManifestTest.kt (core/sync). I segmenti .rd5 non
 * sono file locali per QUESTO tool (a differenza di content.db) — il chiamante li passa gia'
 * come ManifestFileEntry con sha256/sizeBytes calcolati scaricandoli e hashandoli una volta
 * (vedi tools/data-pipeline/scripts/build-region.sh), che li scrive anche su disco accanto a
 * content.db perche' il sito li ri-ospiti: url punta quindi al nostro host, non a brouter.de.
 */
fun buildManifestJson(
    regionId: String,
    displayName: String,
    version: String,
    updatedAt: String,
    files: List<ManifestFileEntry>,
    mapSource: MapSourceInput? = null,
): String {
    require(files.isNotEmpty()) { "Il pacchetto $regionId non contiene file" }

    val filesArray = JSONArray()
    files.forEach { f ->
        filesArray.put(
            JSONObject()
                .put("name", f.name)
                .put("url", f.url)
                .put("sizeBytes", f.sizeBytes)
                .put("sha256", f.sha256)
        )
    }

    val region = JSONObject()
        .put("regionId", regionId)
        .put("displayName", displayName)
        .put("version", version)
        .put("updatedAt", updatedAt)
        .put("files", filesArray)

    mapSource?.let { ms ->
        region.put(
            "mapSource",
            JSONObject()
                .put("sourceUrl", ms.sourceUrl)
                .put("minLon", ms.minLon)
                .put("minLat", ms.minLat)
                .put("maxLon", ms.maxLon)
                .put("maxLat", ms.maxLat)
                .put("minZoom", ms.minZoom)
                .put("maxZoom", ms.maxZoom)
        )
    }

    val root = JSONObject()
        .put("manifestVersion", 1)
        .put("regions", JSONArray().put(region))

    return root.toString(2)
}

/**
 * Legge una "spec" JSON prodotta dall'orchestratore batch (build-region.sh) invece di N
 * argomenti posizionali: contiene gia' gli hash dei segmenti .rd5 remoti (calcolati scaricando
 * e hashando lo stream, vedi sopra) e il bbox per l'estrazione mappa lato device.
 *
 * Formato atteso:
 * {
 *   "regionId": "sm", "displayName": "San Marino", "version": "2026.09.14",
 *   "contentDb": { "path": "/abs/content.db", "url": "https://.../content.db" },
 *   "remoteFiles": [ { "name": "E10_N40.rd5", "url": "...", "sizeBytes": 123, "sha256": "..." } ],
 *   "mapSource": { "sourceUrl": "...", "minLon": .., "minLat": .., "maxLon": .., "maxLat": .., "minZoom": 0, "maxZoom": 14 }
 * }
 */
fun buildManifestJsonFromSpec(specJson: String): String {
    val spec = JSONObject(specJson)
    val contentDb = spec.getJSONObject("contentDb")
    val files = mutableListOf(
        localFileEntry(File(contentDb.getString("path")), "content.db", contentDb.getString("url"))
    )
    spec.optJSONArray("remoteFiles")?.let { remoteFiles ->
        for (i in 0 until remoteFiles.length()) {
            val f = remoteFiles.getJSONObject(i)
            files += ManifestFileEntry(
                name = f.getString("name"),
                url = f.getString("url"),
                sizeBytes = f.getLong("sizeBytes"),
                sha256 = f.getString("sha256"),
            )
        }
    }
    val mapSource = spec.optJSONObject("mapSource")?.let { ms ->
        MapSourceInput(
            sourceUrl = ms.getString("sourceUrl"),
            minLon = ms.getDouble("minLon"), minLat = ms.getDouble("minLat"),
            maxLon = ms.getDouble("maxLon"), maxLat = ms.getDouble("maxLat"),
            minZoom = ms.getInt("minZoom"), maxZoom = ms.getInt("maxZoom"),
        )
    }
    return buildManifestJson(
        regionId = spec.getString("regionId"),
        displayName = spec.getString("displayName"),
        version = spec.getString("version"),
        updatedAt = Instant.now().toString(),
        files = files,
        mapSource = mapSource,
    )
}

fun main(args: Array<String>) {
    require(args.size == 2) { "Uso: generateManifest <spec.json> <output manifest.json>" }
    val specFile = File(args[0])
    val outputFile = File(args[1])

    outputFile.writeText(buildManifestJsonFromSpec(specFile.readText()))
    println("manifest.json scritto in ${outputFile.path}")
}
