package com.pockettravel.core.sync

/**
 * Scarica e verifica (sha256) transit.json (RegionManifest.transit) con lo stesso downloader e la
 * stessa cache per versione dell'indice dei civici, vedi [AddressGridClient]. Implementazione: [HttpTransitClient].
 */
interface TransitClient {
    suspend fun fetchIndex(entry: TransitManifestEntry): TransitIndex

    companion object {
        internal const val FILE_NAME = "transit.json"
        // Come AddressGridClient.STAGING_ID: accanto alle cartelle per regione, fa anche da cache per versione.
        internal const val STAGING_ID = "_transit"
    }
}
