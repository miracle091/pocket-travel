package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionStorage
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Installa (o aggiorna) solo i pacchetti [kinds] di una regione, lasciando intatti gli altri:
 * scarica e verifica i file (poi.db, poi-extra.db, segmenti .rd5, addresses.pmtiles), estrae map.pmtiles dalla build Protomaps,
 * poi sostituisce ogni pacchetto su disco e importa i POI. Se un passo fallisce, i pacchetti
 * gia' sostituiti tornano alla versione precedente. Il chiamante valida [entry].
 */
class RegionPackageInstaller @Inject constructor(
    private val downloader: RegionPackageDownloader,
    private val regionRepository: RegionRepository,
    private val regionStorage: RegionStorage,
    private val poiImporter: PoiImporter,
    private val routingGraphInstaller: RegionRoutingGraphInstaller,
    private val pmtilesExtractor: PmtilesExtractor,
) {
    suspend fun install(
        entry: RegionManifestEntry,
        kinds: Set<PackageKind>,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        require(kinds.isNotEmpty()) { "Nessun pacchetto da installare per ${entry.regionId}" }
        require(entry.availableKinds.containsAll(kinds)) { "Pacchetti non offerti dal manifest per ${entry.regionId}: ${kinds - entry.availableKinds}" }
        // L'anteprima non e' tra i pacchetti richiesti (non e' un PackageKind): si installa da sola con
        // ogni download della regione, quando il manifest la offre con una versione diversa da quella
        // gia' installata — cosi' le regioni gia' scaricate la prendono al primo aggiornamento successivo.
        val installPreview = entry.preview != null && regionRepository.installed(entry.regionId)?.previewVersion != entry.preview.version
        val files = buildList {
            if (PackageKind.ROUTING in kinds) addAll(entry.routing.files)
            if (PackageKind.POI in kinds) add(entry.poi.downloadFile)
            if (PackageKind.POI_EXTRA in kinds) add(entry.poiExtra!!.downloadFile)
            if (PackageKind.ADDRESSES in kinds) add(entry.addresses!!.downloadFile)
            if (installPreview) add(entry.preview!!.downloadFile)
        }
        // Prima di tutto: un tentativo precedente interrotto da un crash puo' aver lasciato backup da
        // chiudere, che activatePackage sovrascriverebbe (RegionStartupRecovery salta le regioni in download).
        withContext(Dispatchers.IO) { regionRepository.recoverInterruptedActivations(entry.regionId) }
        // Una cartella di staging per combinazione di pacchetti e versioni: un download interrotto
        // riprende dai file .part della stessa richiesta, una richiesta diversa riparte da zero.
        val stagingVersion = PackageKind.entries.filter { it in kinds }.joinToString("_") { "${it.name.lowercase()}-${entry.versionOf(it)}" } +
            (if (installPreview) "_preview-${entry.preview!!.version}" else "")
        val staging = downloader.download(entry.regionId, stagingVersion, files, onProgress)
        if (PackageKind.POI in kinds) unpackXz(staging, entry.poi.file, entry.poi.fileXz)
        if (PackageKind.POI_EXTRA in kinds) unpackXz(staging, entry.poiExtra!!.file, entry.poiExtra.fileXz)
        if (PackageKind.ADDRESSES in kinds) unpackXz(staging, entry.addresses!!.file, entry.addresses.fileXz)
        if (installPreview) unpackXz(staging, entry.preview!!.file, entry.preview.fileXz)

        if (PackageKind.MAP in kinds) {
            // Estrazione bloccante (HTTP range): su IO e interrompibile se il download viene annullato.
            withContext(Dispatchers.IO) {
                pmtilesExtractor.extract(entry.map.source, File(staging, RegionStorage.MAP_FILE)) { ensureActive() }
            }
        }
        if (PackageKind.ROUTING in kinds) routingGraphInstaller.install(staging)

        val activations = mutableListOf<RegionStorage.Activation>()
        try {
            if (PackageKind.MAP in kinds) {
                activations += regionStorage.activatePackage(entry.regionId, RegionStorage.MAP_FILE, File(staging, RegionStorage.MAP_FILE), entry.map.version)
            }
            if (PackageKind.ROUTING in kinds) {
                activations += regionStorage.activatePackage(entry.regionId, RegionStorage.ROUTING_DIR, File(staging, RegionStorage.ROUTING_DIR), entry.routing.version)
            }
            if (PackageKind.ADDRESSES in kinds) {
                activations += regionStorage.activatePackage(entry.regionId, RegionStorage.ADDRESSES_FILE, File(staging, entry.addresses!!.file.name), entry.addresses.version)
            }
            if (installPreview) {
                activations += regionStorage.activatePackage(entry.regionId, RegionStorage.PREVIEW_FILE, File(staging, entry.preview!!.file.name), entry.preview.version)
            }
            regionRepository.inInstallTransaction {
                if (PackageKind.POI in kinds) poiImporter.import(entry.regionId, File(staging, entry.poi.file.name))
                if (PackageKind.POI_EXTRA in kinds) poiImporter.import(entry.regionId, File(staging, entry.poiExtra!!.file.name), extra = true)
                regionRepository.markPackagesInstalled(
                    entry.regionId, entry.displayName, entry.countryCode,
                    versions = kinds.associateWith { entry.versionOf(it)!! },
                    poiSizeBytes = if (PackageKind.POI in kinds) entry.poi.file.sizeBytes else null,
                    poiExtraSizeBytes = if (PackageKind.POI_EXTRA in kinds) entry.poiExtra!!.file.sizeBytes else null,
                    previewVersion = if (installPreview) entry.preview!!.version else null,
                )
            }
        } catch (error: Exception) {
            activations.forEach { it.rollback() }
            throw error
        }
        activations.forEach { it.commit() }
        staging.deleteRecursively()
    }
}
