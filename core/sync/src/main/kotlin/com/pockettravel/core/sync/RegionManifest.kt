package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import java.net.URI
import kotlinx.serialization.Serializable

/**
 * manifest.json nel formato a pacchetti (manifestVersion 2, generato da tools/data-pipeline):
 * un pacchetto guide unico per tutte le regioni, e per ogni regione i pacchetti mappa, routing,
 * POI e (facoltativi) POI extra e civici, ciascuno con la propria versione, scaricabili e aggiornabili
 * separatamente.
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
    // POI extra (fontanelle, tavoli da picnic...): assenti per le regioni senza o non ancora rigenerate.
    val poiExtra: PoiPackageEntry? = null,
    // Numeri civici: assenti per le regioni non ancora generate o troppo grandi da estrarre.
    val addresses: AddressesPackageEntry? = null,
    // Continente (da pilot-regions.sh, aggiunto dal merge della pipeline): assente, l'app ricade
    // sul gruppo "Altro".
    val continent: String? = null,
    // Codice ISO 3166-1 alpha-2 minuscolo del paese (piu' regioni possono condividerlo, es. "us").
    val countryCode: String? = null,
) {
    /** Versione del pacchetto nel manifest, null se la regione non lo offre (POI extra e civici). */
    fun versionOf(kind: PackageKind): String? = when (kind) {
        PackageKind.MAP -> map.version
        PackageKind.ROUTING -> routing.version
        PackageKind.POI -> poi.version
        PackageKind.POI_EXTRA -> poiExtra?.version
        PackageKind.ADDRESSES -> addresses?.version
    }

    /** I pacchetti che il manifest offre per questa regione (tutti tranne, a volte, POI extra e civici). */
    val availableKinds: Set<PackageKind>
        get() = PackageKind.entries.filterTo(mutableSetOf()) { versionOf(it) != null }

    /** I pacchetti del download completo ("Scarica"): tutti quelli offerti tranne i POI extra, solo su richiesta. */
    val defaultKinds: Set<PackageKind>
        get() = availableKinds - PackageKind.POI_EXTRA

    /**
     * Byte da scaricare per questi pacchetti. La mappa non ha una dimensione nota in anticipo:
     * map.pmtiles viene estratto sul device dalla build Protomaps (vedi PmtilesExtractor), quindi
     * conta zero come prima della separazione in pacchetti.
     */
    fun downloadBytes(kinds: Set<PackageKind>): Long =
        (if (PackageKind.ROUTING in kinds) routing.files.sumOf { it.sizeBytes } else 0L) +
            (if (PackageKind.POI in kinds) poi.file.sizeBytes else 0L) +
            (if (PackageKind.POI_EXTRA in kinds) poiExtra?.file?.sizeBytes ?: 0L else 0L) +
            (if (PackageKind.ADDRESSES in kinds) addresses?.file?.sizeBytes ?: 0L else 0L)
}

/** map.pmtiles non e' un file scaricato: viene estratto sul device dalle tile di [source]. */
@Serializable
data class MapPackageEntry(val version: String, val source: MapExtractionSource)

/** Segmenti BRouter .rd5 della regione. */
@Serializable
data class RoutingPackageEntry(val version: String, val files: List<RegionManifestFile>)

/** poi.db (o poi-extra.db) della regione. */
@Serializable
data class PoiPackageEntry(val version: String, val file: RegionManifestFile)

/** addresses.pmtiles della regione: i soli civici, sovrapposti alla mappa. */
@Serializable
data class AddressesPackageEntry(val version: String, val file: RegionManifestFile)

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
    poiExtra?.let {
        require(isSafeVersion(it.version)) { "version dei POI extra non valida per $regionId" }
        it.file.validate(regionId)
    }
    addresses?.let {
        require(isSafeVersion(it.version)) { "version dei civici non valida per $regionId" }
        it.file.validate(regionId)
    }
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
