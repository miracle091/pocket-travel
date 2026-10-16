package com.pockettravel.core.sync

import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.MapDetailPreferences
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.TransitFeedInfo
import com.pockettravel.core.data.readTransitValidUntil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * Installa (o aggiorna) solo i pacchetti [kinds] di una regione, lasciando intatti gli altri:
 * scarica e verifica i file (poi.db, poi-extra.db, segmenti .rd5, addresses.pmtiles, cities.db, transit.db), estrae map.pmtiles dalla build Protomaps,
 * poi sostituisce ogni pacchetto su disco e importa i POI e le guide delle citta'. Se un passo
 * fallisce, i pacchetti gia' sostituiti tornano alla versione precedente. Il chiamante valida [entry].
 */
class RegionPackageInstaller @Inject constructor(
    private val downloader: RegionPackageDownloader,
    private val regionRepository: RegionRepository,
    private val regionStorage: RegionStorage,
    private val poiImporter: PoiImporter,
    private val routingSegmentsInstaller: RoutingSegmentsInstaller,
    private val pmtilesExtractor: PmtilesExtractor,
    private val cityImporter: CityImporter,
    private val addressGridInstaller: RegionAddressGridInstaller,
    private val mapDetailPreferences: MapDetailPreferences,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) {
    suspend fun install(
        entry: RegionManifestEntry,
        kinds: Set<PackageKind>,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
        // Dopo i file: l'estrazione della mappa, in byte di tile (il totale lo dice l'indice della build).
        onMapProgress: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
        // File scaricati e mappa estratta: resta l'installazione (rete stradale, import dei POI e delle guide, attivazione),
        // che per una nazione grande dura minuti senza una percentuale da mostrare.
        onInstalling: () -> Unit = {},
    ) {
        require(kinds.isNotEmpty()) { "Nessun pacchetto da installare per ${entry.regionId}" }
        require(entry.availableKinds.containsAll(kinds)) { "Pacchetti non offerti dal catalogo per ${entry.regionId}: ${kinds - entry.availableKinds}" }
        // La mappa d'insieme non e' tra i pacchetti richiesti (non e' un PackageKind): si installa da sola con
        // ogni download della regione, quando il catalogo la offre con una versione diversa da quella
        // gia' installata — cosi' le regioni gia' scaricate la prendono al primo aggiornamento successivo.
        val installPreview = entry.preview != null && regionRepository.installed(entry.regionId)?.previewVersion != entry.preview.version
        // A griglia: solo le celle nuove o cambiate si scaricano, le
        // altre restano quelle gia' installate.
        val addressPlan = if (PackageKind.ADDRESSES in kinds) addressGridInstaller.plan(entry.regionId, entry.addressGrid!!) else null
        val files = buildList {
            if (PackageKind.ROUTING in kinds) addAll(entry.routing.files)
            if (PackageKind.POI in kinds) add(entry.poi.downloadFile)
            if (PackageKind.POI_EXTRA in kinds) add(entry.poiExtra!!.downloadFile)
            if (PackageKind.ADDRESSES in kinds) addAll(addressPlan!!.toDownload.map { it.downloadFile } + addressPlan.searchToDownload.map { it.search!!.downloadFile })
            if (PackageKind.CITIES in kinds) add(entry.cities!!.downloadFile)
            if (PackageKind.TRANSIT in kinds) addAll(entry.transit!!.feeds.map { it.stagedDownloadFile })
            if (installPreview) add(entry.preview.downloadFile)
        }
        // Prima di tutto: un tentativo precedente interrotto da un crash puo' aver lasciato backup da
        // chiudere, che activatePackage sovrascriverebbe (RegionStartupRecovery salta le regioni in download).
        withContext(ioDispatcher) { regionRepository.recoverInterruptedActivations(entry.regionId) }
        // Una cartella di staging per combinazione di pacchetti e versioni: un download interrotto
        // riprende dai file .part della stessa richiesta, una richiesta diversa riparte da zero. La rete stradale
        // "solo auto" ha la stessa versione e gli stessi nomi di quella completa: "car" li tiene separati.
        val routingVariant = if (entry.hasCarOnlyRouting) "car-" else ""
        val stagingVersion = PackageKind.entries.filter { it in kinds }
            .joinToString("_") { "${it.name.lowercase()}-${if (it == PackageKind.ROUTING) routingVariant else ""}${entry.versionOf(it)}" } +
            (if (installPreview) "_preview-${entry.preview.version}" else "")
        // Download, decompressione, attivazione e pulizia sotto lo stesso lock dello staging della regione: un altro
        // lavoro con una versione diversa non cancella (cleanupStagingExcept) i file mentre questo li usa.
        downloader.withStagingLock(entry.regionId) {
            val staging = downloader.downloadLocked(entry.regionId, stagingVersion, files, onProgress)
            if (PackageKind.POI in kinds) unpackXz(staging, entry.poi.file, entry.poi.fileXz, ioDispatcher)
            if (PackageKind.POI_EXTRA in kinds) unpackXz(staging, entry.poiExtra!!.file, entry.poiExtra.fileXz, ioDispatcher)
            if (PackageKind.ADDRESSES in kinds) {
                addressPlan!!.toDownload.forEach { unpackXz(staging, it.file, it.fileXz, ioDispatcher) }
                // Le ricerche per cella restano compresse: mergeInto le tiene .xz e le decomprime solo per l'unione.
                withContext(ioDispatcher) {
                    addressGridInstaller.mergeInto(entry.regionId, entry.map.source, addressPlan, staging, ensureActive = { ensureActive() })
                }
            }
            if (PackageKind.CITIES in kinds) unpackXz(staging, entry.cities!!.file, entry.cities.fileXz, ioDispatcher)
            if (PackageKind.TRANSIT in kinds) {
                entry.transit!!.feeds.forEach { unpackXz(staging, it.stagedFile, it.stagedFileXz, ioDispatcher) }
                withContext(ioDispatcher) {
                    // Le reti sparite dall'indice restano finche' i loro orari sono validi.
                    val installedDir = File(regionStorage.directoryFor(entry.regionId), RegionStorage.TRANSIT_DIR)
                    val carried = carriedOverTransitFeeds(
                        regionStorage.installedTransitFeeds(entry.regionId),
                        entry.transit.available.mapTo(mutableSetOf()) { it.id },
                        LocalDate.now(clock),
                    ) { readTransitValidUntil(File(installedDir, "${it.id}.db")) }
                    assembleTransitDir(staging, entry.transit.feeds, carried, installedDir)
                }
            }
            if (installPreview) unpackXz(staging, entry.preview.file, entry.preview.fileXz, ioDispatcher)

            var extractedLight: Boolean? = null

            if (PackageKind.MAP in kinds) {
                // Estrazione bloccante (HTTP range): su IO e interrompibile se il download viene annullato.
                // Con una mappa gia' installata (activatePackage la sostituisce solo dopo) scarica solo le
                // tile cambiate; senza, o se il confronto fallisce, estrae tutto.
                val installedMap = File(regionStorage.directoryFor(entry.regionId), RegionStorage.MAP_FILE)
                // Leggera o dettagliata come ha scelto l'utente. Senza scelta leggera, ma una mappa dettagliata gia'
                // installata (scaricata prima della mappa leggera) resta dettagliata anche agli aggiornamenti.
                val light = mapDetailPreferences.choice(entry.regionId)
                    ?: (!installedMap.isFile || entry.regionId in mapDetailPreferences.installedLight.value)
                val detail = if (light) MapDetail.LIGHT else MapDetail.FULL
                val stats = withContext(ioDispatcher) {
                    pmtilesExtractor.extract(entry.map.source, File(staging, RegionStorage.MAP_FILE), installedMap, detail, onMapProgress) { ensureActive() }
                }
                extractedLight = stats.maxZoom < entry.map.source.maxZoom
            }
            onInstalling()
            if (PackageKind.ROUTING in kinds) routingSegmentsInstaller.install(staging, entry.routing.files.mapTo(HashSet()) { it.name })

            // I POI si leggono a blocchi dentro la transazione (PoiImporter.replaceFromFile): caricarli tutti in
            // memoria prima, per una regione grande, rischierebbe l'OutOfMemoryError.
            val poisToImport = if (PackageKind.POI in kinds) File(staging, entry.poi.file.name) else null
            val poiExtraToImport = if (PackageKind.POI_EXTRA in kinds) File(staging, entry.poiExtra!!.file.name) else null
            val citySectionsToImport = if (PackageKind.CITIES in kinds) {
                cityImporter.readSections(entry.regionId, File(staging, entry.cities!!.file.name))
            } else {
                null
            }

            val activations = mutableListOf<RegionStorage.Activation>()
            try {
                if (PackageKind.MAP in kinds) {
                    activations += regionStorage.activatePackage(entry.regionId, RegionStorage.MAP_FILE, File(staging, RegionStorage.MAP_FILE), entry.map.version)
                }
                if (PackageKind.ROUTING in kinds) {
                    activations += regionStorage.activatePackage(entry.regionId, RegionStorage.ROUTING_DIR, File(staging, RegionStorage.ROUTING_DIR), entry.routing.version)
                }
                if (PackageKind.ADDRESSES in kinds) {
                    val version = entry.versionOf(PackageKind.ADDRESSES)!!
                    activations += regionStorage.activatePackage(entry.regionId, RegionStorage.ADDRESSES_FILE, File(staging, RegionStorage.ADDRESSES_FILE), version)
                    activations += regionStorage.activatePackage(entry.regionId, RegionStorage.ADDRESSES_CELLS_FILE, File(staging, RegionStorage.ADDRESSES_CELLS_FILE), version)
                    activations += regionStorage.activatePackage(entry.regionId, RegionStorage.ADDRESSES_SEARCH_DIR, File(staging, RegionStorage.ADDRESSES_SEARCH_DIR), version)
                }
                if (PackageKind.TRANSIT in kinds) {
                    activations += regionStorage.activatePackage(entry.regionId, RegionStorage.TRANSIT_DIR, File(staging, RegionStorage.TRANSIT_DIR), entry.versionOf(PackageKind.TRANSIT)!!)
                }
                if (installPreview) {
                    activations += regionStorage.activatePackage(entry.regionId, RegionStorage.PREVIEW_FILE, File(staging, entry.preview.file.name), entry.preview.version)
                }
                regionRepository.inInstallTransaction {
                    // Byte veri in region.db (stima dai dati importati), non la dimensione del file scaricato.
                    val poiBytes = poisToImport?.let { poiImporter.replaceFromFile(entry.regionId, it) }
                    val poiExtraBytes = poiExtraToImport?.let { poiImporter.replaceFromFile(entry.regionId, it, extra = true) }
                    citySectionsToImport?.let { cityImporter.replace(entry.regionId, it) }
                    regionRepository.markPackagesInstalled(
                        entry.regionId, entry.displayName, entry.countryCode,
                        versions = kinds.associateWith { entry.versionOf(it)!! },
                        poiSizeBytes = poiBytes,
                        poiExtraSizeBytes = poiExtraBytes,
                        previewVersion = if (installPreview) entry.preview.version else null,
                        citiesSizeBytes = if (PackageKind.CITIES in kinds) entry.cities!!.file.sizeBytes else null,
                    )
                }
            } catch (error: Exception) {
                activations.forEach { it.rollback() }
                throw error
            }
            activations.forEach { it.commit() }
            extractedLight?.let { mapDetailPreferences.setInstalledLight(entry.regionId, it) }
            staging.deleteRecursively()
        }
    }
}

/**
 * Prepara la cartella degli orari da attivare: `transit/` in [staging] con una `<id>.db` per rete
 * (gia' scaricata e decompressa, vedi [stagedFile]) e [RegionStorage.TRANSIT_FEEDS_FILE] con nome e
 * attribuzione di ciascuna. Attivata come cartella, sostituisce le reti precedenti: quelle sparite dal
 * catalogo spariscono con lei, tranne le [carried] (vedi [carriedOverTransitFeeds]), copiate da [installedDir].
 */
internal fun assembleTransitDir(
    staging: File,
    feeds: List<TransitFeed>,
    carried: List<TransitFeedInfo> = emptyList(),
    installedDir: File? = null,
) {
    val dir = File(staging, RegionStorage.TRANSIT_DIR)
    dir.deleteRecursively()
    check(dir.mkdirs()) { "Impossibile creare ${RegionStorage.TRANSIT_DIR}" }
    feeds.forEach { feed ->
        check(File(staging, feed.stagedFile.name).renameTo(File(dir, feed.stagedFile.name))) { "Impossibile installare la rete ${feed.id}" }
    }
    carried.forEach { info ->
        val source = File(requireNotNull(installedDir) { "Cartella installata mancante" }, "${info.id}.db")
        source.copyTo(File(dir, source.name))
    }
    File(dir, RegionStorage.TRANSIT_FEEDS_FILE).writeText(TransitFeedInfo.encode(feeds.map { it.info() } + carried))
}
