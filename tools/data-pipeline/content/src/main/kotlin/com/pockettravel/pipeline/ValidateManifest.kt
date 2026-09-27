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
    guides.optJSONObject("fileXz")?.let { validateFile(it, "guide", allowedHosts) }

    // Mappa del mondo online (facoltativa, vedi map-preview-online-plan.md): non un "file" da
    // verificare dopo il download (letta a pezzi con richieste Range), solo url/sizeBytes/maxZoom.
    root.optJSONObject("worldMap")?.let { worldMap ->
        validateVersion(worldMap, "worldMap")
        val maxZoom = worldMap.getInt("maxZoom")
        if (maxZoom !in 0..22) throw ManifestValidationException("worldMap.maxZoom non valido: $maxZoom")
        if (worldMap.getLong("sizeBytes") < 0) throw ManifestValidationException("worldMap.sizeBytes non valido")
        val url = worldMap.getString("url")
        if (!isAllowedUrl(url, allowedHosts)) throw ManifestValidationException("worldMap.url non consentito: $url")
    }

    // Indice dei civici a celle (facoltativo, vedi address-grid-plan.md): assente finche' la
    // griglia non e' stata pubblicata la prima volta.
    root.optJSONObject("addressGrid")?.let { addressGrid ->
        validateVersion(addressGrid, "addressGrid")
        if (addressGrid.getLong("sizeBytes") < 0) throw ManifestValidationException("addressGrid.sizeBytes non valido")
        if (!addressGrid.getString("sha256").matches(sha256Regex)) throw ManifestValidationException("addressGrid.sha256 non valido")
        val url = addressGrid.getString("url")
        if (!isAllowedUrl(url, allowedHosts)) throw ManifestValidationException("addressGrid.url non consentito: $url")
    }

    if (root.has("minAppVersionCode")) {
        val minAppVersionCode = root.getInt("minAppVersionCode")
        if (minAppVersionCode <= 0) throw ManifestValidationException("minAppVersionCode non valido: $minAppVersionCode")
    }

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
            addresses.optJSONObject("fileXz")?.let { validateFile(it, regionId, allowedHosts) }
        }

        // Citta': facoltative (regioni non ancora rigenerate, o senza pagine {{QuickbarCity}}
        // abbinate su Wikivoyage IT), stesso schema di addresses.
        region.optJSONObject("cities")?.let { cities ->
            validateVersion(cities, "$regionId/cities")
            validateFile(cities.getJSONObject("file"), regionId, allowedHosts)
            cities.optJSONObject("fileXz")?.let { validateFile(it, regionId, allowedHosts) }
        }

        // Anteprima offline: facoltativa (regioni non ancora rigenerate da quando esiste, o senza
        // go-pmtiles in quella run), stesso schema di poi/poiExtra piu' il livello di zoom usato.
        region.optJSONObject("preview")?.let { preview ->
            validateVersion(preview, "$regionId/preview")
            val maxZoom = preview.getInt("maxZoom")
            if (maxZoom !in 0..22) throw ManifestValidationException("preview.maxZoom non valido per $regionId: $maxZoom")
            validateFile(preview.getJSONObject("file"), regionId, allowedHosts)
            preview.optJSONObject("fileXz")?.let { validateFile(it, regionId, allowedHosts) }
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

/**
 * Valida address-grid.json (indice dei civici a celle, vedi address-grid-plan.md): duplica qui le
 * stesse regole della griglia dell'app (RegionManifest.kt, core/sync), stesso motivo di
 * validateManifestJson sopra. Celle con id valido (z in 0..14, x/y dentro 0..2^z-1), nessuna
 * discendente di un'altra (isAncestorCell, GenerateAddressGrid.kt), ordinate per id, file/fileXz
 * come le altre voci del manifest, almeno un'attribuzione.
 */
fun validateAddressGridJson(indexJson: String, allowedHosts: Set<String>) {
    val root = JSONObject(indexJson)
    if (!root.getString("version").matches(safeSegmentRegex)) throw ManifestValidationException("address-grid.json: version non valida")
    val tileZoom = root.getInt("tileZoom")
    if (tileZoom !in 0..22) throw ManifestValidationException("address-grid.json: tileZoom non valido: $tileZoom")

    val cells = root.getJSONArray("cells")
    if (cells.length() == 0) throw ManifestValidationException("address-grid.json: nessuna cella")

    val seenIds = mutableSetOf<String>()
    val ids = mutableListOf<String>()
    var previous: Triple<Int, Int, Int>? = null
    for (i in 0 until cells.length()) {
        val cell = cells.getJSONObject(i)
        val id = cell.getString("id")
        val parts = id.split('/')
        val z = parts.getOrNull(0)?.toIntOrNull()
        val x = parts.getOrNull(1)?.toIntOrNull()
        val y = parts.getOrNull(2)?.toIntOrNull()
        if (parts.size != 3 || z == null || x == null || y == null || z !in 0..14 || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) {
            throw ManifestValidationException("address-grid.json: id di cella non valido: $id")
        }
        if (!seenIds.add(id)) throw ManifestValidationException("address-grid.json: id di cella duplicato: $id")
        val sortKey = Triple(z, x, y)
        previous?.let { prev ->
            val cmp = compareValuesBy(sortKey, prev, { it.first }, { it.second }, { it.third })
            if (cmp < 0) throw ManifestValidationException("address-grid.json: celle non ordinate per id (vicino a $id)")
        }
        previous = sortKey
        ids += id

        validateVersion(cell, "addressGrid/$id")
        validateFile(cell.getJSONObject("file"), "addressGrid/$id", allowedHosts)
        cell.optJSONObject("fileXz")?.let { validateFile(it, "addressGrid/$id", allowedHosts) }
    }
    ids.forEach { candidate ->
        if (ids.any { other -> isAncestorCell(candidate, other) }) {
            throw ManifestValidationException("address-grid.json: $candidate e' antenata di un'altra cella")
        }
    }

    val attributions = root.getJSONArray("attributions")
    if (attributions.length() == 0) throw ManifestValidationException("address-grid.json: nessuna attribuzione")
    for (i in 0 until attributions.length()) {
        val attribution = attributions.getJSONObject(i)
        attribution.getString("source")
        attribution.getString("license")
        attribution.getString("url")
    }
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
    require(args.size == 2 || args.size == 3) { "Uso: validateManifest <manifest.json> <pagesHost> [<address-grid.json>]" }
    // github.com: guides.db, poi.db e i segmenti .rd5 vivono sugli asset delle release "region-data*"
    // e "address-cells-*", non piu' sotto pagesHost — vedi SyncConfig.ALLOWED_MANIFEST_HOSTS
    // (core/sync), duplicato qui di proposito.
    val allowedHosts = setOf(args[1], "brouter.de", "build.protomaps.com", "github.com")
    validateManifestJson(File(args[0]).readText(), allowedHosts)
    println("manifest valido: ${args[0]}")
    if (args.size == 3) {
        validateAddressGridJson(File(args[2]).readText(), allowedHosts)
        println("indice civici valido: ${args[2]}")
    }
}
