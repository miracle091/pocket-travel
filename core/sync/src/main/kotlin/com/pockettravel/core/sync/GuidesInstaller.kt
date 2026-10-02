package com.pockettravel.core.sync

import java.io.File
import javax.inject.Inject

/**
 * Scarica guides.db (guide e numeri di emergenza di tutte le regioni, eventualmente compresso con
 * xz, vedi [GuidesManifestEntry]), ne verifica lo SHA-256 e lo importa in region.db con
 * [GuidesImporter]. Pesa meno di un MB (meno ancora compresso): nessun progresso da mostrare.
 */
class GuidesInstaller @Inject constructor(
    private val downloader: RegionPackageDownloader,
    private val guidesImporter: GuidesImporter,
) {
    /** [installedVersion]: la versione da registrare, vedi [GuidesChoice]. */
    suspend fun install(guides: GuidesManifestEntry, installedVersion: String = guides.version) {
        guides.validate()
        // Download, decompressione e import sotto lo stesso lock dello staging: un altro lavoro non lo svuota a meta'.
        downloader.withStagingLock(STAGING_ID) {
            val staging = downloader.downloadLocked(STAGING_ID, guides.version, listOf(guides.downloadFile))
            unpackXz(staging, guides.file, guides.fileXz)
            guidesImporter.import(File(staging, guides.file.name), installedVersion)
            staging.deleteRecursively()
        }
    }

    internal companion object {
        // Cartella di staging delle guide, accanto a quelle per regione (RegionStorage).
        const val STAGING_ID = "_guides"
    }
}
