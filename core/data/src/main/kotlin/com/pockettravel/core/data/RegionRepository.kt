package com.pockettravel.core.data

import kotlinx.coroutines.flow.Flow

/** Le regioni installate e i loro pacchetti: registro nel database, file su disco, guide. Implementazione: [RoomRegionRepository]. */
interface RegionRepository {
    fun observeInstalled(): Flow<List<InstalledRegion>>

    suspend fun displayName(regionId: String): String?

    suspend fun installed(regionId: String): InstalledRegion?

    /**
     * Registra i pacchetti appena installati ([versions]), conservando gli altri gia' presenti.
     * [poiSizeBytes] e [poiExtraSizeBytes] sono richiesti quando tra i pacchetti c'e' [PackageKind.POI] o
     * [PackageKind.POI_EXTRA]. [previewVersion], se presente, aggiorna l'anteprima installata da sola
     * insieme a questo download (non e' un [PackageKind], vedi RegionPackageInstaller).
     */
    suspend fun markPackagesInstalled(
        regionId: String,
        displayName: String,
        countryCode: String?,
        versions: Map<PackageKind, String>,
        poiSizeBytes: Long? = null,
        poiExtraSizeBytes: Long? = null,
        previewVersion: String? = null,
        citiesSizeBytes: Long? = null,
    )

    /** Elimina un solo pacchetto della regione; tolto l'ultimo, la regione non risulta piu' installata. */
    suspend fun removePackage(regionId: String, kind: PackageKind)

    /**
     * Toglie dal database i pacchetti su file (mappa, rete stradale, civici, orari dei mezzi) registrati ma
     * senza file su disco: restano cosi' se l'app si chiude fra la cancellazione dei file e la
     * transazione di [removePackage], e l'app li crederebbe installati ("Installato · 0 B") invece di
     * proporre di scaricarli. Ritorna i pacchetti tolti.
     */
    suspend fun forgetMissingPackages(regionId: String): Set<PackageKind>

    /** Codice paese dal manifest per le regioni installate prima che il database lo salvasse. */
    suspend fun fillCountryCode(regionId: String, countryCode: String)

    /** Versione del pacchetto guide installato, null se non ancora scaricato. */
    suspend fun installedGuidesVersion(): String?

    fun observeInstalledGuides(): Flow<InstalledGuides?>

    suspend fun markGuidesInstalled(version: String, sizeBytes: Long)

    /** VACUUM di region.db se ha tante pagine libere (vedi [DatabaseCompactor]): all'avvio, dopo una migrazione che ne ha lasciate. */
    suspend fun compactDatabase()

    /** Esegue [block] in una transazione del database, senza che un VACUUM parta nel frattempo. */
    suspend fun <T> inInstallTransaction(block: suspend () -> T): T

    /** Riallinea i pacchetti su disco di [regionId] alle versioni registrate dopo un'installazione interrotta da un crash. */
    suspend fun recoverInterruptedActivations(regionId: String)

    /** Elimina la regione, database e file; le guide restano: sono un pacchetto unico per tutte le regioni. */
    suspend fun remove(regionId: String)

    /** Byte occupati da un pacchetto installato: mappa, routing, civici e orari dei mezzi dal disco, POI (base ed extra) e citta' dalla dimensione registrata. */
    fun packageBytes(region: InstalledRegion, kind: PackageKind): Long?

    fun availableStorageBytes(): Long

    /** Celle dei civici a griglia gia' installate (id -> version), per la dimensione da scaricare (RegionListViewModel). */
    fun installedAddressCells(regionId: String): Map<String, String>
}
