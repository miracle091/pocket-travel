package com.pockettravel.pipeline

import java.io.File
import java.net.URI
import org.json.JSONObject

/**
 * Duplica intenzionalmente le regole di RegionManifestEntry.validate() (core/sync, modulo
 * Android non raggiungibile da questo modulo Kotlin/JVM puro) cosi' il workflow di
 * pubblicazione (publish-regions.yml) puo' rifiutare un manifest malformato PRIMA del deploy su Pages, senza
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
    if (manifestVersion != MANIFEST_VERSION) throw ManifestValidationException("manifestVersion non supportata: $manifestVersion")

    val guides = root.optJSONObject("guides") ?: throw ManifestValidationException("Il manifest non contiene il pacchetto guide")
    validateVersion(guides, "guide")
    validateFile(guides.getJSONObject("file"), "guide", allowedHosts)

    val regions = root.getJSONArray("regions")
    if (regions.length() == 0) throw ManifestValidationException("Il manifest non contiene regioni")

    val seenRegionIds = mutableSetOf<String>()
    val groupNames = mutableSetOf<String>()
    for (i in 0 until regions.length()) {
        val region = regions.getJSONObject(i)
        val regionId = region.getString("regionId")
        if (!isSafeSegment(regionId)) throw ManifestValidationException("regionId non valido: $regionId")
        if (!seenRegionIds.add(regionId)) throw ManifestValidationException("regionId duplicato: $regionId")
        region.optString("groupName").takeIf { it.isNotBlank() }?.let { groupNames += it }

        val map = region.getJSONObject("map")
        validateVersion(map, "$regionId/map")
        validateMapSource(map.getJSONObject("source"), regionId, allowedHosts)

        val routing = region.getJSONObject("routing")
        validateVersion(routing, "$regionId/routing")
        val files = routing.getJSONArray("files")
        if (files.length() == 0) throw ManifestValidationException("Il routing di $regionId non contiene file")
        val fileNames = mutableSetOf<String>()
        for (j in 0 until files.length()) {
            val file = files.getJSONObject(j)
            if (!fileNames.add(file.getString("name"))) {
                throw ManifestValidationException("File duplicati nel routing di $regionId: ${file.getString("name")}")
            }
            validateFile(file, regionId, allowedHosts)
        }

        val poi = region.getJSONObject("poi")
        validateVersion(poi, "$regionId/poi")
        validateFile(poi.getJSONObject("file"), regionId, allowedHosts)
        poi.optJSONObject("fileXz")?.let { validateFile(it, regionId, allowedHosts) }

        // POI extra: facoltativi (regioni senza, o non ancora rigenerate).
        region.optJSONObject("poiExtra")?.let { poiExtra ->
            validateVersion(poiExtra, "$regionId/poiExtra")
            validateFile(poiExtra.getJSONObject("file"), regionId, allowedHosts)
            poiExtra.optJSONObject("fileXz")?.let { validateFile(it, regionId, allowedHosts) }
        }

        // Civici: facoltativi (regioni non ancora generate o troppo grandi da estrarre).
        region.optJSONObject("addresses")?.let { addresses ->
            validateVersion(addresses, "$regionId/addresses")
            validateFile(addresses.getJSONObject("file"), regionId, allowedHosts)
        }
    }

    // Regioni tolte e sostituite da un gruppo di regioni piu' piccole (facoltativo).
    val replaced = root.optJSONArray("replacedRegions")
    for (i in 0 until (replaced?.length() ?: 0)) {
        val entry = replaced!!.getJSONObject(i)
        val regionId = entry.getString("regionId")
        if (!isSafeSegment(regionId)) throw ManifestValidationException("regionId sostituita non valida: $regionId")
        if (regionId in seenRegionIds) throw ManifestValidationException("$regionId e' sostituita ma ancora tra le regioni")
        val groupName = entry.getString("groupName")
        if (groupName !in groupNames) throw ManifestValidationException("Nessuna regione nel gruppo $groupName che sostituisce $regionId")
    }
}

private fun validateVersion(pkg: JSONObject, label: String) {
    if (!pkg.getString("version").matches(safeSegmentRegex)) throw ManifestValidationException("version non valida per $label")
}

private fun validateFile(file: JSONObject, owner: String, allowedHosts: Set<String>) {
    val name = file.getString("name")
    if (!isSafeSegment(name)) throw ManifestValidationException("file.name non valido per $owner: $name")
    if (file.getLong("sizeBytes") < 0) throw ManifestValidationException("Dimensione non valida per $owner/$name")
    if (!file.getString("sha256").matches(sha256Regex)) throw ManifestValidationException("SHA-256 non valido per $owner/$name")
    val url = file.getString("url")
    if (!isAllowedUrl(url, allowedHosts)) throw ManifestValidationException("URL non consentito per $owner/$name: $url")
}

private fun validateMapSource(mapSource: JSONObject, regionId: String, allowedHosts: Set<String>) {
    val sourceUrl = mapSource.getString("sourceUrl")
    if (!isAllowedUrl(sourceUrl, allowedHosts)) throw ManifestValidationException("map.source.sourceUrl non consentito per $regionId")
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

fun main(args: Array<String>) {
    require(args.size == 2) { "Uso: validateManifest <manifest.json> <pagesHost>" }
    // github.com: guides.db, poi.db e i segmenti .rd5 vivono sugli asset delle release "region-data*",
    // non piu' sotto pagesHost — vedi SyncConfig.ALLOWED_MANIFEST_HOSTS (core/sync), duplicato qui
    // di proposito.
    val allowedHosts = setOf(args[1], "brouter.de", "build.protomaps.com", "github.com")
    validateManifestJson(File(args[0]).readText(), allowedHosts)
    println("manifest valido: ${args[0]}")
}
