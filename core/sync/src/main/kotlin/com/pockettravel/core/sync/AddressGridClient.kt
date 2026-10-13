package com.pockettravel.core.sync

/**
 * Scarica e verifica (sha256) address-grid.json (RegionManifest.addressGrid)
 * con lo stesso downloader e la stessa cache per versione delle guide (RegionPackageDownloader
 * + lo staging di RegionStorage, vedi GuidesInstaller): un file per versione, le altre si cancellano
 * ad ogni fetch riuscito — nessuna directory dedicata da aggiungere. Implementazione: [HttpAddressGridClient].
 */
interface AddressGridClient {
    suspend fun fetchIndex(entry: AddressGridManifestEntry): AddressGridIndex

    companion object {
        internal const val FILE_NAME = "address-grid.json"
        // Cartella di staging dell'indice, accanto a quelle per regione (RegionStorage) e alle guide
        // (GuidesInstaller.STAGING_ID): fa anche da cache per versione, RegionPackageDownloader.download
        // cancella le versioni diverse da quella richiesta ad ogni chiamata.
        internal const val STAGING_ID = "_address_grid"
    }
}
