package com.pockettravel.core.sync

import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
data class RegionManifest(val manifestVersion: Int, val regions: List<RegionManifestEntry>)

@Serializable
data class RegionManifestEntry(
    val regionId: String, val displayName: String, val version: String,
    val updatedAt: String, val files: List<RegionManifestFile>,
    // Assente per un pacchetto senza mappa (non dovrebbe capitare in produzione, ma tenerlo
    // opzionale evita di rompere manifest di test/fixture piu' vecchi). Quando presente,
    // map.pmtiles NON e' tra i file scaricati: viene assemblato sul device estraendo solo le
    // tile di questo bounding box dalla build pubblica Protomaps — vedi PmtilesExtractor.
    val mapSource: MapExtractionSource? = null,
    // Continente (da pilot-regions.sh, aggiunto dal merge della pipeline): assente nei manifest
    // pubblicati prima del campo, l'app ricade allora sul gruppo "Altro".
    val continent: String? = null,
) { val sizeBytes: Long get() = files.sumOf { it.sizeBytes } }

@Serializable
data class RegionManifestFile(val name: String, val url: String, val sizeBytes: Long, val sha256: String)

@Serializable
data class MapExtractionSource(
    val sourceUrl: String,
    val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double,
    val minZoom: Int, val maxZoom: Int,
)

fun RegionManifestEntry.validate() {
    require(isSafeSegment(regionId)) { "regionId non valido" }
    require(version.matches(Regex("[A-Za-z0-9._-]+"))) { "version non valida" }
    require(files.isNotEmpty()) { "Il pacchetto $regionId non contiene file" }
    require(files.map { it.name }.toSet().size == files.size) { "File duplicati nel pacchetto $regionId" }
    files.forEach { file ->
        require(isSafeSegment(file.name)) { "file.name non valido" }
        require(file.sizeBytes >= 0) { "Dimensione non valida per ${file.name}" }
        require(file.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "SHA-256 non valido per ${file.name}" }
        require(isAllowedManifestUrl(file.url)) { "URL non consentito per ${file.name}" }
    }
    mapSource?.let { source ->
        require(isAllowedManifestUrl(source.sourceUrl)) { "mapSource.sourceUrl non consentito per $regionId" }
        require(source.minLon < source.maxLon && source.minLat < source.maxLat) { "bounding box non valido per $regionId" }
        require(source.minLon >= -180.0 && source.maxLon <= 180.0 && source.minLat >= -90.0 && source.maxLat <= 90.0) {
            "bounding box fuori dai limiti geografici per $regionId"
        }
        require(source.minZoom in 0..22 && source.maxZoom in source.minZoom..22) { "zoom non valido per $regionId" }
    }
}

private fun isAllowedManifestUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && uri.host != null &&
        uri.host in SyncConfig.ALLOWED_MANIFEST_HOSTS && uri.userInfo == null && uri.fragment == null
}

private fun isSafeSegment(value: String): Boolean = value.isNotEmpty() && value != "." && value != ".." &&
    !value.contains('/') && !value.contains('\\') && value.matches(Regex("[A-Za-z0-9._-]+"))
