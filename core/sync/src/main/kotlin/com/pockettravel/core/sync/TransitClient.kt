package com.pockettravel.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val signatureVerifier: ManifestSignatureVerifier,
) {
    suspend fun fetchIndex(entry: TransitManifestEntry): TransitIndex = withContext(Dispatchers.IO) {
        // Lettura, firma e validate() fuori dal thread del chiamante (spesso il Main di un viewModelScope).
        val file = RegionManifestFile(name = FILE_NAME, url = entry.url, sizeBytes = entry.sizeBytes, sha256 = entry.sha256)
        val staging = downloader.download(STAGING_ID, entry.version, listOf(file))
        val indexFile = File(staging, FILE_NAME)
        // Prima di interpretarlo; la firma verificata resta in staging (vedi ManifestSignatureVerifier.verifyFile).
        signatureVerifier.verifyFile(entry.url, indexFile)
        val index = json.decodeFromString(TransitIndex.serializer(), indexFile.readText())
        index.validate()
        index
    }

    internal companion object {
        const val FILE_NAME = "transit.json"
        // Come AddressGridClient.STAGING_ID: accanto alle cartelle per regione, fa anche da cache per versione.
        const val STAGING_ID = "_transit"
    }
}
