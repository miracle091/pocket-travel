package com.pockettravel.pipeline

import java.io.File
import java.net.URI
import org.json.JSONObject

/**
 * Duplica intenzionalmente le regole di RegionManifestEntry.validate() (core/sync, modulo
 * Android non raggiungibile da questo modulo Kotlin/JVM puro — stesso tradeoff gia' accettato
 * per OsmData/parseOsmXml, vedi il commento in build.gradle.kts) cosi' il workflow di
 * pubblicazione (A3) puo' rifiutare un manifest malformato PRIMA del deploy su Pages, senza
 * bisogno di un modulo Android in CI solo per una validazione. Se le regole in RegionManifest.kt
 * cambiano, vanno aggiornate anche qui. L'host Pages non e' hardcoded: viene passato dal
 * chiamante (il workflow lo conosce da github.repository_owner), cosi' non c'e' un nome utente
 * fisso da disallineare.
 */
private val safeSegmentRegex = Regex("[A-Za-z0-9._-]+")
private val sha256Regex = Regex("[0-9a-fA-F]{64}")

class ManifestValidationException(message: String) : Exception(message)

private fun isSafeSegment(value: String): Boolean =
    value.isNotEmpty() && value != "." && value != ".." && !value.contains('/') && !value.contains('\\') &&
        value.matches(safeSegmentRegex)

private fun isAllowedUrl(url: String, allowedHosts: Set<String>): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && uri.host != null &&
        uri.host in allowedHosts && uri.userInfo == null && uri.fragment == null
}

fun validateManifestJson(manifestJson: String, allowedHosts: Set<String>) {
    val root = JSONObject(manifestJson)
    val manifestVersion = root.getInt("manifestVersion")
    if (manifestVersion != 1) throw ManifestValidationException("manifestVersion non supportata: $manifestVersion")

    val regions = root.getJSONArray("regions")
    if (regions.length() == 0) throw ManifestValidationException("Il manifest non contiene regioni")

    val seenRegionIds = mutableSetOf<String>()
    for (i in 0 until regions.length()) {
        val region = regions.getJSONObject(i)
        val regionId = region.getString("regionId")
        if (!isSafeSegment(regionId)) throw ManifestValidationException("regionId non valido: $regionId")
        if (!seenRegionIds.add(regionId)) throw ManifestValidationException("regionId duplicato: $regionId")

        val version = region.getString("version")
        if (!version.matches(safeSegmentRegex)) throw ManifestValidationException("version non valida per $regionId")

        val files = region.getJSONArray("files")
        if (files.length() == 0) throw ManifestValidationException("Il pacchetto $regionId non contiene file")
        val fileNames = mutableSetOf<String>()
        for (j in 0 until files.length()) {
            val file = files.getJSONObject(j)
            val name = file.getString("name")
            if (!isSafeSegment(name)) throw ManifestValidationException("file.name non valido per $regionId: $name")
            if (!fileNames.add(name)) throw ManifestValidationException("File duplicati nel pacchetto $regionId: $name")
            if (file.getLong("sizeBytes") < 0) throw ManifestValidationException("Dimensione non valida per $regionId/$name")
            if (!file.getString("sha256").matches(sha256Regex)) {
                throw ManifestValidationException("SHA-256 non valido per $regionId/$name")
            }
            val url = file.getString("url")
            if (!isAllowedUrl(url, allowedHosts)) throw ManifestValidationException("URL non consentito per $regionId/$name: $url")
        }

        region.optJSONObject("mapSource")?.let { mapSource ->
            val sourceUrl = mapSource.getString("sourceUrl")
            if (!isAllowedUrl(sourceUrl, allowedHosts)) throw ManifestValidationException("mapSource.sourceUrl non consentito per $regionId")
            val minLon = mapSource.getDouble("minLon")
            val maxLon = mapSource.getDouble("maxLon")
            val minLat = mapSource.getDouble("minLat")
            val maxLat = mapSource.getDouble("maxLat")
            if (!(minLon < maxLon && minLat < maxLat)) throw ManifestValidationException("bounding box non valido per $regionId")
            if (!(minLon >= -180.0 && maxLon <= 180.0 && minLat >= -90.0 && maxLat <= 90.0)) {
                throw ManifestValidationException("bounding box fuori dai limiti geografici per $regionId")
            }
            val minZoom = mapSource.getInt("minZoom")
            val maxZoom = mapSource.getInt("maxZoom")
            if (!(minZoom in 0..22 && maxZoom in minZoom..22)) throw ManifestValidationException("zoom non valido per $regionId")
        }
    }
}

fun main(args: Array<String>) {
    require(args.size == 2) { "Uso: validateManifest <manifest.json> <pagesHost>" }
    // github.com: content.db e i segmenti .rd5 vivono sugli asset della release "region-data",
    // non piu' sotto pagesHost — vedi SyncConfig.ALLOWED_MANIFEST_HOSTS (core/sync), duplicato qui
    // di proposito.
    val allowedHosts = setOf(args[1], "brouter.de", "build.protomaps.com", "github.com")
    validateManifestJson(File(args[0]).readText(), allowedHosts)
    println("manifest valido: ${args[0]}")
}
