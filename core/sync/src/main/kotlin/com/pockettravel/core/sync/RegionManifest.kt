package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import java.net.URI
import kotlinx.serialization.Serializable

/**
 * manifest.json nel formato a pacchetti (manifestVersion 2, generato da tools/data-pipeline):
 * un pacchetto guide unico per tutte le regioni, e per ogni regione tre pacchetti — mappa,
 * routing, POI — ciascuno con la propria versione, scaricabili e aggiornabili separatamente.
 */
@Serializable
data class RegionManifest(val manifestVersion: Int, val guides: GuidesManifestEntry, val regions: List<RegionManifestEntry>)

/** guides.db: guide Wikivoyage e numeri di emergenza di tutte le regioni. */
@Serializable
data class GuidesManifestEntry(val version: String, val file: RegionManifestFile)

@Serializable
data class RegionManifestEntry(
    val regionId: String, val displayName: String, val updatedAt: String,
    val map: MapPackageEntry,
    val routing: RoutingPackageEntry,
    val poi: PoiPackageEntry,
    // Continente (da pilot-regions.sh, aggiunto dal merge della pipeline): assente, l'app ricade
    // sul gruppo "Altro".
    val continent: String? = null,
    // Codice ISO 3166-1 alpha-2 minuscolo del paese (piu' regioni possono condividerlo, es. "us").
    val countryCode: String? = null,
) {
    fun versionOf(kind: PackageKind): String = when (kind) {
        PackageKind.MAP -> map.version
        PackageKind.ROUTING -> routing.version
        PackageKind.POI -> poi.version
    }

    /**
     * Byte da scaricare per questi pacchetti. La mappa non ha una dimensione nota in anticipo:
     * map.pmtiles viene estratto sul device dalla build Protomaps (vedi PmtilesExtractor), quindi
     * conta zero come prima della separazione in pacchetti.
     */
    fun downloadBytes(kinds: Set<PackageKind>): Long =
        (if (PackageKind.ROUTING in kinds) routing.files.sumOf { it.sizeBytes } else 0L) +
            (if (PackageKind.POI in kinds) poi.file.sizeBytes else 0L)
}

/** map.pmtiles non e' un file scaricato: viene estratto sul device dalle tile di [source]. */
@Serializable
data class MapPackageEntry(val version: String, val source: MapExtractionSource)

/** Segmenti BRouter .rd5 della regione. */
@Serializable
data class RoutingPackageEntry(val version: String, val files: List<RegionManifestFile>)

/** poi.db della regione. */
@Serializable
data class PoiPackageEntry(val version: String, val file: RegionManifestFile)

@Serializable
data class RegionManifestFile(val name: String, val url: String, val sizeBytes: Long, val sha256: String)

@Serializable
data class MapExtractionSource(
    val sourceUrl: String,
    val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double,
    val minZoom: Int, val maxZoom: Int,
)

fun GuidesManifestEntry.validate() {
    require(isSafeVersion(version)) { "version delle guide non valida" }
    file.validate("guide")
}

fun RegionManifestEntry.validate() {
    require(isSafeSegment(regionId)) { "regionId non valido" }
    listOf(map.version, routing.version, poi.version).forEach { require(isSafeVersion(it)) { "version non valida per $regionId" } }
    require(routing.files.isNotEmpty()) { "Il routing di $regionId non contiene file" }
    require(routing.files.map { it.name }.toSet().size == routing.files.size) { "File duplicati nel routing di $regionId" }
    routing.files.forEach { it.validate(regionId) }
    poi.file.validate(regionId)
    val source = map.source
    require(isAllowedManifestUrl(source.sourceUrl)) { "map.source.sourceUrl non consentito per $regionId" }
    require(source.minLon < source.maxLon && source.minLat < source.maxLat) { "bounding box non valido per $regionId" }
    require(source.minLon >= -180.0 && source.maxLon <= 180.0 && source.minLat >= -90.0 && source.maxLat <= 90.0) {
        "bounding box fuori dai limiti geografici per $regionId"
    }
    require(source.minZoom in 0..22 && source.maxZoom in source.minZoom..22) { "zoom non valido per $regionId" }
}

private fun RegionManifestFile.validate(owner: String) {
    require(isSafeSegment(name)) { "file.name non valido per $owner" }
    require(sizeBytes >= 0) { "Dimensione non valida per $owner/$name" }
    require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "SHA-256 non valido per $owner/$name" }
    require(isAllowedManifestUrl(url)) { "URL non consentito per $owner/$name" }
}

private fun isSafeVersion(version: String): Boolean = version.matches(Regex("[A-Za-z0-9._-]+"))

private fun isAllowedManifestUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val secure = uri.scheme.equals("https", ignoreCase = true) ||
        (uri.scheme == "http" && uri.host != null && uri.host == SyncConfig.CLEARTEXT_MANIFEST_HOST)
    return secure && uri.host != null &&
        uri.host in SyncConfig.ALLOWED_MANIFEST_HOSTS && uri.userInfo == null && uri.fragment == null
}

private fun isSafeSegment(value: String): Boolean = value.isNotEmpty() && value != "." && value != ".." &&
    !value.contains('/') && !value.contains('\\') && value.matches(Regex("[A-Za-z0-9._-]+"))
