package com.pockettravel.core.sync

import com.pockettravel.core.data.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

/** [TransitClient] sul downloader dei pacchetti, con cache per versione nello staging. */
class HttpTransitClient @Inject constructor(
    private val downloader: RegionPackageDownloader,
    private val json: Json,
    private val signatureVerifier: ManifestSignatureVerifier,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TransitClient {
    override suspend fun fetchIndex(entry: TransitManifestEntry): TransitIndex = withContext(ioDispatcher) {
        // Lettura, firma e validate() fuori dal thread del chiamante (spesso il Main di un viewModelScope).
        val file = RegionManifestFile(name = TransitClient.FILE_NAME, url = entry.url, sizeBytes = entry.sizeBytes, sha256 = entry.sha256)
        val staging = downloader.download(TransitClient.STAGING_ID, entry.version, listOf(file))
        val indexFile = File(staging, TransitClient.FILE_NAME)
        // Prima di interpretarlo; la firma verificata resta in staging (vedi ManifestSignatureVerifier.verifyFile).
        signatureVerifier.verifyFile(entry.url, indexFile)
        val index = json.decodeFromString(TransitIndex.serializer(), indexFile.readText())
        index.validate()
        index
    }
}
