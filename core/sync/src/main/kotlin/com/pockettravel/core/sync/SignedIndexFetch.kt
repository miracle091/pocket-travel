package com.pockettravel.core.sync

import java.io.File

/**
 * Scarica (con cache per versione nello staging [stagingId]) l'indice [file], ne verifica la firma prima di interpretarlo
 * (la firma verificata resta in staging, vedi ManifestSignatureVerifier.verifyFile) e lo legge con [parse]. Condivisa da
 * HttpTransitClient e HttpAddressGridClient.
 */
internal suspend fun <T> RegionPackageDownloader.fetchSignedIndex(
    signatureVerifier: ManifestSignatureVerifier,
    stagingId: String,
    version: String,
    file: RegionManifestFile,
    parse: (String) -> T,
): T {
    val staging = downloadLatest(stagingId, version, listOf(file))
    val indexFile = File(staging, file.name)
    signatureVerifier.verifyFile(file.url, indexFile)
    return parse(indexFile.readText())
}
