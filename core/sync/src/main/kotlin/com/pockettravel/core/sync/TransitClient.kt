package com.pockettravel.core.sync

import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

/**
 * Scarica e verifica (sha256) transit.json (RegionManifest.transit) con lo stesso downloader e la
 * stessa cache per versione dell'indice dei civici, vedi [AddressGridClient].
 */
class TransitClient @Inject constructor(
    private val downloader: RegionPackageDownloader,
    private val json: Json,
) {
    suspend fun fetchIndex(entry: TransitManifestEntry): TransitIndex {
        val file = RegionManifestFile(name = FILE_NAME, url = entry.url, sizeBytes = entry.sizeBytes, sha256 = entry.sha256)
        val staging = downloader.download(STAGING_ID, entry.version, listOf(file))
        val index = json.decodeFromString(TransitIndex.serializer(), File(staging, FILE_NAME).readText())
        index.validate()
        return index
    }

    internal companion object {
        const val FILE_NAME = "transit.json"
        // Come AddressGridClient.STAGING_ID: accanto alle cartelle per regione, fa anche da cache per versione.
        const val STAGING_ID = "_transit"
    }
}
