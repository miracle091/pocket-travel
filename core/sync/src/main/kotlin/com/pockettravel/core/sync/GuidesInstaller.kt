package com.pockettravel.core.sync

import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.RegionRepository
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Inject

/**
 * Scarica guides.db (guide e numeri di emergenza di tutte le regioni, eventualmente compresso con
 * xz, vedi [GuidesManifestEntry]), ne verifica lo SHA-256 e lo importa in region.db con
 * [GuidesImporter]. Compresso pesa circa 2,6 MB in italiano e 4,5 MB in inglese: nessun progresso da mostrare.
 */
class GuidesInstaller @Inject constructor(
    private val downloader: RegionPackageDownloader,
    private val guidesImporter: GuidesImporter,
    private val regionRepository: RegionRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** [installedVersion]: la versione da registrare, vedi [GuidesChoice]. */
    suspend fun install(guides: GuidesManifestEntry, installedVersion: String = guides.version) {
        guides.validate()
        // Download, decompressione e import sotto lo stesso lock dello staging: un altro lavoro non lo svuota a meta'.
        downloader.withStagingLock(STAGING_ID) {
            // Chi aspettava il lock mentre un altro installava la stessa versione non riscarica nulla.
            if (regionRepository.installedGuidesVersion() == installedVersion) return@withStagingLock
            // Staging per versione installata (con il prefisso della lingua): italiano e inglese hanno la stessa guides.version.
            val staging = downloader.downloadLocked(STAGING_ID, installedVersion, listOf(guides.downloadFile))
            unpackXz(staging, guides.file, guides.fileXz, ioDispatcher)
            guidesImporter.import(File(staging, guides.file.name), installedVersion)
            staging.deleteRecursively()
        }
    }

    internal companion object {
        // Cartella di staging delle guide, accanto a quelle per regione (RegionStorage).
        const val STAGING_ID = "_guides"
    }
}
