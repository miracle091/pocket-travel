package com.pockettravel.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

/**
 * Scarica e verifica (sha256) address-grid.json (RegionManifest.addressGrid)
 * con lo stesso downloader e la stessa cache per versione delle guide (RegionPackageDownloader
 * + lo staging di RegionStorage, vedi GuidesInstaller): un file per versione, le altre si cancellano
 * ad ogni fetch riuscito — nessuna directory dedicata da aggiungere.
 */
class AddressGridClient @Inject constructor(
    private val downloader: RegionPackageDownloader,
    private val json: Json,
    private val signatureVerifier: ManifestSignatureVerifier,
) {
    suspend fun fetchIndex(entry: AddressGridManifestEntry): AddressGridIndex = withContext(Dispatchers.IO) {
        // Lettura, firma e validate() fuori dal thread del chiamante (spesso il Main di un viewModelScope).
        val file = RegionManifestFile(name = FILE_NAME, url = entry.url, sizeBytes = entry.sizeBytes, sha256 = entry.sha256)
        val staging = downloader.download(STAGING_ID, entry.version, listOf(file))
        val indexFile = File(staging, FILE_NAME)
        // Prima di interpretarlo; la firma verificata resta in staging (vedi ManifestSignatureVerifier.verifyFile).
        signatureVerifier.verifyFile(entry.url, indexFile)
        val index = json.decodeFromString(AddressGridIndex.serializer(), indexFile.readText())
        index.validate()
        index
    }

    internal companion object {
        const val FILE_NAME = "address-grid.json"
        // Cartella di staging dell'indice, accanto a quelle per regione (RegionStorage) e alle guide
        // (GuidesInstaller.STAGING_ID): fa anche da cache per versione, RegionPackageDownloader.download
        // cancella le versioni diverse da quella richiesta ad ogni chiamata.
        const val STAGING_ID = "_address_grid"
    }
}
