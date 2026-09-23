package com.pockettravel.core.sync

import java.io.File
import javax.inject.Inject

/**
 * Scarica guides.db (guide e numeri di emergenza di tutte le regioni), ne verifica lo SHA-256 e
 * lo importa in region.db con [GuidesImporter]. Pesa meno di un MB: nessun progresso da mostrare.
 */
class GuidesInstaller @Inject constructor(
    private val downloader: RegionPackageDownloader,
    private val guidesImporter: GuidesImporter,
) {
    suspend fun install(guides: GuidesManifestEntry) {
        guides.validate()
        val staging = downloader.download(STAGING_ID, guides.version, listOf(guides.file))
        guidesImporter.import(File(staging, guides.file.name), guides.version)
        staging.deleteRecursively()
    }

    private companion object {
        // Cartella di staging delle guide, accanto a quelle per regione (RegionStorage).
        const val STAGING_ID = "_guides"
    }
}
