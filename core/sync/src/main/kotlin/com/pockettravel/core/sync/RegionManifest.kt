package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * manifest.json nel formato a pacchetti (manifestVersion 2, generato da tools/data-pipeline):
 * un pacchetto guide unico per tutte le regioni, e per ogni regione i pacchetti mappa, routing,
 * POI e (facoltativi) POI extra, civici e orari dei mezzi pubblici, ciascuno con la propria versione, scaricabili e aggiornabili
 * separatamente.
 */
@Serializable
data class RegionManifest(
    val manifestVersion: Int,
    val guides: GuidesManifestEntry,
    // Guide in inglese (build-guides.sh ... en): assenti finche' la pipeline non le pubblica. Vedi guidesChoice.
    val guidesEn: GuidesManifestEntry? = null,
    // Indice dei civici a griglia, a fianco di manifest.json su GitHub
    // Pages: assente finche' la pipeline non e' passata alla griglia, o per le app vecchie che non
    // lo sanno leggere (ignoreUnknownKeys = true).
    val addressGrid: AddressGridManifestEntry? = null,
    // Reti dei mezzi pubblici (transit.json, a fianco di manifest.json): come addressGrid, assente finche'
    // la pipeline non le pubblica o per le app vecchie.
    val transit: TransitManifestEntry? = null,
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
    // Celle dei civici a griglia che intersecano questa regione: mai nel
    // manifest (assente li'), valorizzato dall'app dopo aver scaricato l'indice (AddressGridClient +
    // regionGridCells) prima di accodare un download — cosi' RegionPackageDownloadWorker lo riceve
    // nell'input di lavoro insieme al resto dell'entry, senza bisogno di un canale a parte. La
    // versione dei civici si calcola da qui (regionAddressesGridVersion), non e' un campo a parte:
    // non puo' disallinearsi dalle celle. Null se il manifest non offre [RegionManifest.addressGrid]
    // o se questa regione non ha celle nel suo riquadro.
    val addressGrid: RegionAddressGridEntry? = null,
    // Reti dei mezzi pubblici che servono questa regione: mai nel manifest, valorizzato dall'app dopo
    // aver scaricato transit.json (TransitClient + attachTransitFeeds) prima di accodare un download,
    // come addressGrid. La versione del pacchetto si calcola da qui (regionTransitVersion). Null se il
    // manifest non offre transit.json o nessuna rete e' di questa regione.
    val transit: RegionTransitEntry? = null,
    // Guide delle citta' (city_sections di cities.db): assenti per le regioni senza citta' abbinate.
    val cities: CitiesPackageEntry? = null,
    // Citta' di Wikivoyage EN (build-cities.sh ... en). L'app non le legge da qui: con l'interfaccia in
    // inglese ManifestClient le mette al posto di [cities] (vedi forLanguage in GuidesChoice.kt).
    val citiesEn: CitiesPackageEntry? = null,
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
    // Nome inglese di groupLabel (es. "North Carolina" per "Carolina del Nord"), per l'app in inglese.
    val groupLabelEn: String? = null,
) {
    /** Versione del pacchetto nel manifest, null se la regione non lo offre (POI extra e civici). */
    fun versionOf(kind: PackageKind): String? = when (kind) {
        PackageKind.MAP -> map.version
        PackageKind.ROUTING -> routing.version
        PackageKind.POI -> poi.version
        PackageKind.POI_EXTRA -> poiExtra?.version
        PackageKind.ADDRESSES -> addressGrid?.let { regionAddressesGridVersion(it.cells) }
        PackageKind.CITIES -> cities?.version
        PackageKind.TRANSIT -> transit?.let { regionTransitVersion(it.feeds) }
    }

    /** I pacchetti che il manifest offre per questa regione (tutti tranne, a volte, POI extra, civici e mezzi pubblici). */
    val availableKinds: Set<PackageKind>
        get() = PackageKind.entries.filterTo(mutableSetOf()) { versionOf(it) != null }

    /**
     * I pacchetti del download completo ("Scarica"): tutti quelli offerti tranne i POI extra e i
     * percorsi, solo su richiesta dal foglio Pacchetti o con [downloadKinds]. I percorsi pesano
     * (Italia 840 MB) e servono solo alla navigazione. Anche gli orari dei mezzi pubblici si chiedono
     * a parte (la proposta del primo avvio dipende dalla modalita' d'uso). "Aggiorna" riguarda comunque
     * tutti i pacchetti installati.
     */
    val defaultKinds: Set<PackageKind>
        get() = availableKinds - PackageKind.POI_EXTRA - PackageKind.ROUTING - PackageKind.TRANSIT

    /** Il download di "Scarica": [defaultKinds] piu' i percorsi se l'utente vuole le indicazioni. */
    fun downloadKinds(withRouting: Boolean): Set<PackageKind> =
        if (withRouting) defaultKinds + (availableKinds intersect setOf(PackageKind.ROUTING)) else defaultKinds

    /**
     * Byte da scaricare per questi pacchetti. La mappa non ha una dimensione nota in anticipo:
     * map.pmtiles viene estratto sul device dalla build Protomaps (vedi PmtilesExtractor), quindi
     * conta zero come prima della separazione in pacchetti. Per i civici a griglia [installedAddressCells]
     * (id di cella -> version gia' installata, RegionStorage.installedAddressCells) fa contare solo le
     * celle nuove o cambiate, come verra' davvero scaricato: vuoto (default) conta tutte le celle,
     * corretto per una prima installazione.
     */
    fun downloadBytes(kinds: Set<PackageKind>, installedAddressCells: Map<String, String> = emptyMap()): Long =
        (if (PackageKind.ROUTING in kinds) routing.files.sumOf { it.sizeBytes } else 0L) +
            (if (PackageKind.POI in kinds) poi.downloadFile.sizeBytes else 0L) +
            (if (PackageKind.POI_EXTRA in kinds) poiExtra?.downloadFile?.sizeBytes ?: 0L else 0L) +
            (if (PackageKind.ADDRESSES in kinds) addressesDownloadBytes(installedAddressCells) else 0L) +
            (if (PackageKind.CITIES in kinds) cities?.downloadFile?.sizeBytes ?: 0L else 0L) +
            (if (PackageKind.TRANSIT in kinds) transit?.feeds?.sumOf { it.downloadFile.sizeBytes } ?: 0L else 0L)

    private fun addressesDownloadBytes(installedAddressCells: Map<String, String>): Long =
        addressGrid?.cells?.filter { installedAddressCells[it.id] != it.version }?.sumOf { it.downloadFile.sizeBytes + (it.search?.downloadFile?.sizeBytes ?: 0L) } ?: 0L
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

/** Celle dei civici a griglia di una regione: vedi [RegionManifestEntry.addressGrid]. */
@Serializable
data class RegionAddressGridEntry(val cells: List<AddressGridCell>)

/**
 * Voce "addressGrid" in cima al manifest: riferimento a address-grid.json,
 * scaricato e verificato (sha256) come gli altri file del manifest, vedi AddressGridClient.
 */
@Serializable
data class AddressGridManifestEntry(val version: String, val url: String, val sizeBytes: Long, val sha256: String)

/**
 * address-grid.json: indice pubblicato di tutte le celle dei civici, a fianco
 * di manifest.json. [cells] ordinato per id, nessun id discendente di un altro (validate()).
 */
@Serializable
data class AddressGridIndex(
    val version: String,
    val tileZoom: Int,
    val cells: List<AddressGridCell>,
    val attributions: List<AddressGridAttribution> = emptyList(),
)

/**
 * Una cella (nodo z/x/y del quadtree Web Mercator, z <= 14 = tileZoom): stesso schema di
 * [PoiPackageEntry], [fileXz] se c'e' e' il file da scaricare, compresso con xz. [search], se c'e',
 * e' il database per la ricerca degli indirizzi della cella (addresses-search.db), assente nei manifest
 * pubblicati prima: senza, la cella funziona come sempre ma non si cerca per indirizzo.
 */
@Serializable
data class AddressGridCell(
    val id: String, val version: String, val file: RegionManifestFile, val fileXz: RegionManifestFile? = null,
    val search: AddressSearchEntry? = null,
) {
    val downloadFile: RegionManifestFile get() = fileXz ?: file
}

/**
 * addresses-search.db di una cella, stesso schema di [AddressGridCell]: [fileXz], se c'e', e' il file da
 * scaricare, compresso con xz, il cui risultato decompresso deve avere dimensione e sha256 di [file].
 */
@Serializable
data class AddressSearchEntry(val file: RegionManifestFile, val fileXz: RegionManifestFile? = null) {
    val downloadFile: RegionManifestFile get() = fileXz ?: file
}

/** Fonte dei civici (OSM, dataset Overture ammessi...), per la schermata Licenze. */
@Serializable
data class AddressGridAttribution(val source: String, val license: String, val url: String? = null)

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
    addressGrid?.cells?.forEach { it.validate(regionId) }
    transit?.feeds?.forEach { it.validate() }
    listOfNotNull(cities, citiesEn).forEach {
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

fun AddressGridManifestEntry.validate() {
    require(isSafeVersion(version)) { "version della griglia indirizzi non valida" }
    require(sizeBytes >= 0) { "Dimensione della griglia indirizzi non valida" }
    require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "SHA-256 della griglia indirizzi non valido" }
    require(isAllowedManifestUrl(url)) { "url della griglia indirizzi non consentito" }
}

/**
 * Convalida address-grid.json: id di cella nel formato "z/x/y" (z <= 14, [parseCellId]), nessuno
 * duplicato o discendente di un altro (le celle non si sovrappongono mai),
 * ordinate per id come pubblicate dalla pipeline.
 */
fun AddressGridIndex.validate() {
    require(isSafeVersion(version)) { "version dell'indice civici non valida" }
    require(tileZoom in 0..22) { "tileZoom non valido" }
    require(cells == cells.sortedBy { it.id }) { "cells non ordinato per id" }
    require(cells.map { it.id }.toSet().size == cells.size) { "id di cella duplicati" }
    val ids = cells.map { requireNotNull(parseCellId(it.id)) { "id di cella non valido: ${it.id}" } }
    // Id diversi come testo ma uguali una volta interpretati ("1/01/0" e "1/1/0") sono la stessa cella.
    val idSet = ids.toHashSet()
    require(idSet.size == ids.size) { "id di cella duplicati" }
    // O(n * zoom): per ogni cella si risalgono gli antenati, nessun confronto a coppie.
    ids.forEachIndexed { i, id ->
        for (shift in 1..id.z) {
            val ancestor = CellId(id.z - shift, id.x shr shift, id.y shr shift)
            require(ancestor !in idSet) { "cella discendente di un'altra: ${cells[i].id} di ${ancestor.z}/${ancestor.x}/${ancestor.y}" }
        }
    }
    cells.forEach { it.validate("address-grid") }
}

private fun AddressGridCell.validate(owner: String) {
    require(parseCellId(id) != null) { "id di cella non valido: $id" }
    require(isSafeVersion(version)) { "version di cella non valida per $id" }
    file.validate("$owner/$id")
    fileXz?.validate("$owner/$id")
    search?.let {
        it.file.validate("$owner/$id/search")
        it.fileXz?.validate("$owner/$id/search")
    }
}

internal fun RegionManifestFile.validate(owner: String) {
    require(isSafeSegment(name)) { "file.name non valido per $owner" }
    require(sizeBytes >= 0) { "Dimensione non valida per $owner/$name" }
    require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "SHA-256 non valido per $owner/$name" }
    require(isAllowedManifestUrl(url)) { "URL non consentito per $owner/$name" }
}

internal fun isSafeVersion(version: String): Boolean = version.matches(Regex("[A-Za-z0-9._-]+"))

internal fun isAllowedManifestUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val secure = uri.scheme.equals("https", ignoreCase = true) ||
        (uri.scheme == "http" && uri.host != null && uri.host == SyncConfig.CLEARTEXT_MANIFEST_HOST)
    return secure && uri.host != null &&
        uri.host in SyncConfig.ALLOWED_MANIFEST_HOSTS && uri.userInfo == null && uri.fragment == null
}

internal fun isSafeSegment(value: String): Boolean = value.isNotEmpty() && value != "." && value != ".." &&
    !value.contains('/') && !value.contains('\\') && value.matches(Regex("[A-Za-z0-9._-]+"))
