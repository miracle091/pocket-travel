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
data class RegionManifest(
    val manifestVersion: Int,
    val guides: GuidesManifestEntry,
    val regions: List<RegionManifestEntry>,
    // Regioni tolte e divise in regioni piu' piccole (es. "stati-uniti" -> gli stati): l'app le propone
    // a chi ha ancora installata quella vecchia.
    val replacedRegions: List<ReplacedRegion> = emptyList(),
    // Mondo online a bassa risoluzione (z0-8), nostro: assente finche' la release GitHub "world-map"
    // non e' pubblicata. core/sync lo salva in WorldMapStore (core/data) ad ogni sync riuscita.
    val worldMap: WorldMapEntry? = null,
    // Versione minima dell'app (versionCode) che sa leggere questi dati: vedi AppCompatibility.
    val minAppVersionCode: Int? = null,
)

/** Regione tolta dal manifest e sostituita dalle regioni del gruppo [groupName]. */
@Serializable
data class ReplacedRegion(val regionId: String, val groupName: String)

/**
 * guides.db: guide Wikivoyage e numeri di emergenza di tutte le regioni. [fileXz], se c'e', e' il
 * file da scaricare, compresso con xz: lo si decomprime e il risultato deve avere dimensione e
 * sha256 di [file]. Senza [fileXz] (manifest pubblicati prima della compressione) si scarica
 * direttamente [file].
 */
@Serializable
data class GuidesManifestEntry(val version: String, val file: RegionManifestFile, val fileXz: RegionManifestFile? = null) {
    val downloadFile: RegionManifestFile get() = fileXz ?: file
}

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
    // Guide delle citta' (city_sections di cities.db): assenti per le regioni senza citta' abbinate.
    val cities: CitiesPackageEntry? = null,
    // Anteprima offline (pochi zoom, tetto di peso compresso): si installa da sola con ogni download
    // della regione (RegionPackageInstaller), non e' un PackageKind. Assente per le regioni non ancora
    // rigenerate.
    val preview: PreviewPackageEntry? = null,
    // Continente (da pilot-regions.sh, aggiunto dal merge della pipeline): assente, l'app ricade
    // sul gruppo "Altro".
    val continent: String? = null,
    // Codice ISO 3166-1 alpha-2 minuscolo del paese (piu' regioni possono condividerlo, es. "us").
    val countryCode: String? = null,
    // Paese diviso in piu' regioni (es. "Stati Uniti d'America") e nome breve della regione nel
    // gruppo (es. "California"): l'elenco le raccoglie sotto un'unica voce. Assenti per le nazioni intere.
    val groupName: String? = null,
    val groupLabel: String? = null,
) {
    /** Versione del pacchetto nel manifest, null se la regione non lo offre (POI extra e civici). */
    fun versionOf(kind: PackageKind): String? = when (kind) {
        PackageKind.MAP -> map.version
        PackageKind.ROUTING -> routing.version
        PackageKind.POI -> poi.version
        PackageKind.POI_EXTRA -> poiExtra?.version
        PackageKind.ADDRESSES -> addresses?.version
        PackageKind.CITIES -> cities?.version
    }

    /** I pacchetti che il manifest offre per questa regione (tutti tranne, a volte, POI extra e civici). */
    val availableKinds: Set<PackageKind>
        get() = PackageKind.entries.filterTo(mutableSetOf()) { versionOf(it) != null }

    /**
     * I pacchetti del download completo ("Scarica"): tutti quelli offerti tranne i POI extra e i
     * percorsi, solo su richiesta dal foglio Pacchetti. I percorsi tornano nel download completo
     * quando c'e' la schermata che li usa (routes-integration-plan.md): oggi occupano spazio (Italia
     * 840 MB) senza servire a nulla. "Aggiorna" riguarda comunque tutti i pacchetti installati.
     */
    val defaultKinds: Set<PackageKind>
        get() = availableKinds - PackageKind.POI_EXTRA - PackageKind.ROUTING

    /**
     * Byte da scaricare per questi pacchetti. La mappa non ha una dimensione nota in anticipo:
     * map.pmtiles viene estratto sul device dalla build Protomaps (vedi PmtilesExtractor), quindi
     * conta zero come prima della separazione in pacchetti.
     */
    fun downloadBytes(kinds: Set<PackageKind>): Long =
        (if (PackageKind.ROUTING in kinds) routing.files.sumOf { it.sizeBytes } else 0L) +
            (if (PackageKind.POI in kinds) poi.downloadFile.sizeBytes else 0L) +
            (if (PackageKind.POI_EXTRA in kinds) poiExtra?.downloadFile?.sizeBytes ?: 0L else 0L) +
            (if (PackageKind.ADDRESSES in kinds) addresses?.downloadFile?.sizeBytes ?: 0L else 0L) +
            (if (PackageKind.CITIES in kinds) cities?.downloadFile?.sizeBytes ?: 0L else 0L)
}

/** map.pmtiles non e' un file scaricato: viene estratto sul device dalle tile di [source]. */
@Serializable
data class MapPackageEntry(val version: String, val source: MapExtractionSource)

/** Segmenti BRouter .rd5 della regione. */
@Serializable
data class RoutingPackageEntry(val version: String, val files: List<RegionManifestFile>)

/**
 * poi.db (o poi-extra.db) della regione. [fileXz], se c'e', e' il file da scaricare, compresso con
 * xz: lo si decomprime e il risultato deve avere dimensione e sha256 di [file]. Senza [fileXz]
 * (regioni pubblicate prima della compressione) si scarica direttamente [file].
 */
@Serializable
data class PoiPackageEntry(val version: String, val file: RegionManifestFile, val fileXz: RegionManifestFile? = null) {
    val downloadFile: RegionManifestFile get() = fileXz ?: file
}

/**
 * addresses.pmtiles della regione: i soli civici, sovrapposti alla mappa. Stesso schema di
 * [PoiPackageEntry]: [fileXz], se c'e', e' il file da scaricare, compresso con xz, il cui risultato
 * decompresso deve avere dimensione e sha256 di [file].
 */
@Serializable
data class AddressesPackageEntry(val version: String, val file: RegionManifestFile, val fileXz: RegionManifestFile? = null) {
    val downloadFile: RegionManifestFile get() = fileXz ?: file
}

/**
 * cities.db della regione: sezioni delle guide di citta' (city_sections), stesso schema di
 * [PoiPackageEntry]: [fileXz], se c'e', e' il file da scaricare, compresso con xz, il cui risultato
 * decompresso deve avere dimensione e sha256 di [file].
 */
@Serializable
data class CitiesPackageEntry(val version: String, val file: RegionManifestFile, val fileXz: RegionManifestFile? = null) {
    val downloadFile: RegionManifestFile get() = fileXz ?: file
}

/**
 * preview.pmtiles della regione, stesso schema di [PoiPackageEntry]: [fileXz], se c'e', e' il file da
 * scaricare, compresso con xz, il cui risultato decompresso deve avere dimensione e sha256 di [file].
 */
@Serializable
data class PreviewPackageEntry(val version: String, val maxZoom: Int, val file: RegionManifestFile, val fileXz: RegionManifestFile? = null) {
    val downloadFile: RegionManifestFile get() = fileXz ?: file
}

/**
 * Mondo online a bassa risoluzione, nostro, pubblicato sulla release GitHub "world-map": non
 * compresso, letto a pezzi con richieste range (pmtiles://https://...) quando la mappa della regione
 * non e' scaricata — niente sha256 da verificare, il file non si scarica per intero.
 */
@Serializable
data class WorldMapEntry(val version: String, val maxZoom: Int, val url: String, val sizeBytes: Long)

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
    fileXz?.validate("guide")
}

fun RegionManifestEntry.validate() {
    require(isSafeSegment(regionId)) { "regionId non valido" }
    listOf(map.version, routing.version, poi.version).forEach { require(isSafeVersion(it)) { "version non valida per $regionId" } }
    require(routing.files.isNotEmpty()) { "Il routing di $regionId non contiene file" }
    require(routing.files.map { it.name }.toSet().size == routing.files.size) { "File duplicati nel routing di $regionId" }
    routing.files.forEach { it.validate(regionId) }
    poi.file.validate(regionId)
    poi.fileXz?.validate(regionId)
    poiExtra?.let {
        require(isSafeVersion(it.version)) { "version dei POI extra non valida per $regionId" }
        it.file.validate(regionId)
        it.fileXz?.validate(regionId)
    }
    addresses?.let {
        require(isSafeVersion(it.version)) { "version dei civici non valida per $regionId" }
        it.file.validate(regionId)
        it.fileXz?.validate(regionId)
    }
    cities?.let {
        require(isSafeVersion(it.version)) { "version delle guide di citta' non valida per $regionId" }
        it.file.validate(regionId)
        it.fileXz?.validate(regionId)
    }
    preview?.let {
        require(isSafeVersion(it.version)) { "version dell'anteprima non valida per $regionId" }
        require(it.maxZoom in 0..22) { "maxZoom dell'anteprima non valido per $regionId" }
        it.file.validate(regionId)
        it.fileXz?.validate(regionId)
    }
    val source = map.source
    require(isAllowedManifestUrl(source.sourceUrl)) { "map.source.sourceUrl non consentito per $regionId" }
    require(source.minLon < source.maxLon && source.minLat < source.maxLat) { "bounding box non valido per $regionId" }
    require(source.minLon >= -180.0 && source.maxLon <= 180.0 && source.minLat >= -90.0 && source.maxLat <= 90.0) {
        "bounding box fuori dai limiti geografici per $regionId"
    }
    require(source.minZoom in 0..22 && source.maxZoom in source.minZoom..22) { "zoom non valido per $regionId" }
}

fun WorldMapEntry.validate() {
    require(isSafeVersion(version)) { "version del mondo online non valida" }
    require(maxZoom in 0..22) { "maxZoom del mondo online non valido" }
    require(sizeBytes >= 0) { "Dimensione del mondo online non valida" }
    require(isAllowedManifestUrl(url)) { "url del mondo online non consentito" }
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
