package com.pockettravel.core.data

import androidx.room.withTransaction
import com.pockettravel.core.data.db.CityDao
import com.pockettravel.core.data.db.InstalledGuidesEntity
import com.pockettravel.core.data.db.InstalledRegionEntity
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.db.RegionPackageDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RegionRepository @Inject constructor(
    private val regionPackageDao: RegionPackageDao,
    private val poiDao: PoiDao,
    private val regionStorage: RegionStorage,
    private val database: RegionDatabase,
    private val cityDao: CityDao,
) {
    fun observeInstalled(): Flow<List<RegionPackage>> =
        regionPackageDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun displayName(regionId: String): String? =
        regionPackageDao.findById(regionId)?.displayName

    suspend fun installed(regionId: String): RegionPackage? = regionPackageDao.findById(regionId)?.toDomain()

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
    ) {
        require(PackageKind.POI !in versions || poiSizeBytes != null) { "poiSizeBytes mancante per $regionId" }
        require(PackageKind.POI_EXTRA !in versions || poiExtraSizeBytes != null) { "poiExtraSizeBytes mancante per $regionId" }
        require(PackageKind.CITIES !in versions || citiesSizeBytes != null) { "citiesSizeBytes mancante per $regionId" }
        val current = installed(regionId)
        save(
            regionId, displayName, countryCode ?: current?.countryCode,
            versionOf = { kind -> versions[kind] ?: current?.versionOf(kind) },
            poiSizeBytes = if (PackageKind.POI in versions) poiSizeBytes else current?.poiSizeBytes,
            poiExtraSizeBytes = if (PackageKind.POI_EXTRA in versions) poiExtraSizeBytes else current?.poiExtraSizeBytes,
            previewVersion = previewVersion ?: current?.previewVersion,
            citiesSizeBytes = if (PackageKind.CITIES in versions) citiesSizeBytes else current?.citiesSizeBytes,
        )
    }

    /** Elimina un solo pacchetto della regione; tolto l'ultimo, la regione non risulta piu' installata. */
    suspend fun removePackage(regionId: String, kind: PackageKind) {
        val current = installed(regionId) ?: return
        // Ultimo pacchetto: si elimina l'intera regione, con i file fuori dalla transazione come in remove().
        if (PackageKind.entries.all { it == kind || current.versionOf(it) == null }) {
            remove(regionId)
            return
        }
        // File su disco fuori dalla transazione (come in remove()); prima di save(), che ne legge le dimensioni.
        when (kind) {
            PackageKind.MAP -> check(regionStorage.deletePackage(regionId, RegionStorage.MAP_FILE)) { "Impossibile eliminare la mappa di $regionId" }
            PackageKind.ROUTING -> check(regionStorage.deletePackage(regionId, RegionStorage.ROUTING_DIR)) { "Impossibile eliminare il routing di $regionId" }
            PackageKind.ADDRESSES -> {
                check(regionStorage.deletePackage(regionId, RegionStorage.ADDRESSES_FILE)) { "Impossibile eliminare i civici di $regionId" }
                // Percorso a griglia: elenco delle celle installate accanto ad ADDRESSES_FILE, assente
                // (deletePackage torna comunque true) per il percorso di oggi.
                regionStorage.deletePackage(regionId, RegionStorage.ADDRESSES_CELLS_FILE)
                // Database di ricerca degli indirizzi: assente per i manifest senza ricerca. I file aperti da
                // AddressSearchRepository li chiude lei alla ricerca successiva (file sparito).
                regionStorage.deletePackage(regionId, RegionStorage.ADDRESSES_SEARCH_DIR)
            }
            PackageKind.TRANSIT -> check(regionStorage.deletePackage(regionId, RegionStorage.TRANSIT_DIR)) { "Impossibile eliminare gli orari dei mezzi di $regionId" }
            PackageKind.POI, PackageKind.POI_EXTRA, PackageKind.CITIES -> Unit
        }
        database.withTransaction {
            when (kind) {
                PackageKind.POI -> poiDao.deletePackageForRegion(regionId, extra = false)
                PackageKind.POI_EXTRA -> poiDao.deletePackageForRegion(regionId, extra = true)
                PackageKind.CITIES -> cityDao.deleteForRegion(regionId)
                PackageKind.MAP, PackageKind.ROUTING, PackageKind.ADDRESSES, PackageKind.TRANSIT -> Unit
            }
            save(
                regionId, current.displayName, current.countryCode,
                versionOf = { if (it == kind) null else current.versionOf(it) },
                poiSizeBytes = if (kind == PackageKind.POI) null else current.poiSizeBytes,
                poiExtraSizeBytes = if (kind == PackageKind.POI_EXTRA) null else current.poiExtraSizeBytes,
                previewVersion = current.previewVersion,
                citiesSizeBytes = if (kind == PackageKind.CITIES) null else current.citiesSizeBytes,
            )
        }
    }

    /**
     * Toglie dal database i pacchetti su file (mappa, percorsi, civici, orari dei mezzi) registrati ma
     * senza file su disco: restano cosi' se l'app si chiude fra la cancellazione dei file e la
     * transazione di [removePackage], e l'app li crederebbe installati ("Installato · 0 B") invece di
     * proporre di scaricarli. Ritorna i pacchetti tolti.
     */
    suspend fun forgetMissingPackages(regionId: String): Set<PackageKind> {
        val current = installed(regionId) ?: return emptySet()
        val missing = FILE_PACKAGES.filter { (kind, packageName) ->
            current.versionOf(kind) != null && regionStorage.packageBytes(regionId, packageName) == 0L
        }.keys
        if (missing.isEmpty()) return missing
        save(
            regionId, current.displayName, current.countryCode,
            versionOf = { if (it in missing) null else current.versionOf(it) },
            poiSizeBytes = current.poiSizeBytes,
            poiExtraSizeBytes = current.poiExtraSizeBytes,
            previewVersion = current.previewVersion,
            citiesSizeBytes = current.citiesSizeBytes,
        )
        return missing
    }

    private suspend fun save(
        regionId: String,
        displayName: String,
        countryCode: String?,
        versionOf: (PackageKind) -> String?,
        poiSizeBytes: Long?,
        poiExtraSizeBytes: Long?,
        previewVersion: String?,
        citiesSizeBytes: Long?,
    ) {
        if (PackageKind.entries.all { versionOf(it) == null }) {
            remove(regionId)
            return
        }
        val diskBytes = regionStorage.packageBytes(regionId, RegionStorage.MAP_FILE) + regionStorage.packageBytes(regionId, RegionStorage.ROUTING_DIR) +
            regionStorage.packageBytes(regionId, RegionStorage.ADDRESSES_FILE) + regionStorage.packageBytes(regionId, RegionStorage.PREVIEW_FILE) +
            regionStorage.packageBytes(regionId, RegionStorage.ADDRESSES_CELLS_FILE) + regionStorage.packageBytes(regionId, RegionStorage.TRANSIT_DIR) +
            regionStorage.packageBytes(regionId, RegionStorage.ADDRESSES_SEARCH_DIR)
        regionPackageDao.upsert(
            InstalledRegionEntity(
                regionId = regionId,
                displayName = displayName,
                countryCode = countryCode,
                mapVersion = versionOf(PackageKind.MAP),
                routingVersion = versionOf(PackageKind.ROUTING),
                poiVersion = versionOf(PackageKind.POI),
                poiExtraVersion = versionOf(PackageKind.POI_EXTRA),
                addressesVersion = versionOf(PackageKind.ADDRESSES),
                previewVersion = previewVersion,
                poiSizeBytes = poiSizeBytes,
                poiExtraSizeBytes = poiExtraSizeBytes,
                sizeBytes = diskBytes + (poiSizeBytes ?: 0L) + (poiExtraSizeBytes ?: 0L) + (citiesSizeBytes ?: 0L),
                installedAt = System.currentTimeMillis(),
                citiesVersion = versionOf(PackageKind.CITIES),
                citiesSizeBytes = citiesSizeBytes,
                transitVersion = versionOf(PackageKind.TRANSIT),
            ),
        )
    }

    /** Codice paese dal manifest per le regioni installate prima che il database lo salvasse. */
    suspend fun fillCountryCode(regionId: String, countryCode: String) = regionPackageDao.fillCountryCode(regionId, countryCode)

    /** Versione del pacchetto guide installato, null se non ancora scaricato. */
    suspend fun installedGuidesVersion(): String? = regionPackageDao.guidesVersion()

    fun observeInstalledGuides(): Flow<InstalledGuides?> =
        regionPackageDao.observeGuides().map { it?.let { entity -> InstalledGuides(entity.version, entity.sizeBytes) } }

    suspend fun markGuidesInstalled(version: String, sizeBytes: Long) =
        regionPackageDao.upsertGuides(InstalledGuidesEntity(version = version, sizeBytes = sizeBytes))

    suspend fun <T> inInstallTransaction(block: suspend () -> T): T = database.withTransaction { block() }

    /** Riallinea i pacchetti su disco di [regionId] alle versioni registrate dopo un'installazione interrotta da un crash. */
    suspend fun recoverInterruptedActivations(regionId: String) {
        val current = installed(regionId)
        regionStorage.recoverInterruptedActivations(regionId) { packageName ->
            when (packageName) {
                RegionStorage.MAP_FILE -> current?.mapVersion
                RegionStorage.ROUTING_DIR -> current?.routingVersion
                RegionStorage.ADDRESSES_FILE -> current?.addressesVersion
                RegionStorage.ADDRESSES_CELLS_FILE -> current?.addressesVersion
                RegionStorage.ADDRESSES_SEARCH_DIR -> current?.addressesVersion
                RegionStorage.PREVIEW_FILE -> current?.previewVersion
                RegionStorage.TRANSIT_DIR -> current?.transitVersion
                else -> null
            }
        }
    }

    // Le guide restano: sono un pacchetto unico per tutte le regioni, non di questa regione.
    suspend fun remove(regionId: String) {
        check(regionStorage.delete(regionId)) { "Impossibile eliminare la regione $regionId" }
        regionStorage.deleteStaging(regionId)
        database.withTransaction {
            regionPackageDao.deleteById(regionId)
            poiDao.deleteForRegion(regionId)
            cityDao.deleteForRegion(regionId)
        }
    }

    /** Byte occupati da un pacchetto installato: mappa, routing, civici e orari dei mezzi dal disco, POI (base ed extra) e citta' dalla dimensione registrata. */
    fun packageBytes(region: RegionPackage, kind: PackageKind): Long? = when {
        region.versionOf(kind) == null -> null
        kind == PackageKind.MAP -> regionStorage.packageBytes(region.regionId, RegionStorage.MAP_FILE)
        kind == PackageKind.ROUTING -> regionStorage.packageBytes(region.regionId, RegionStorage.ROUTING_DIR)
        kind == PackageKind.ADDRESSES -> regionStorage.packageBytes(region.regionId, RegionStorage.ADDRESSES_FILE) +
            regionStorage.packageBytes(region.regionId, RegionStorage.ADDRESSES_SEARCH_DIR)
        kind == PackageKind.TRANSIT -> regionStorage.packageBytes(region.regionId, RegionStorage.TRANSIT_DIR)
        kind == PackageKind.POI_EXTRA -> region.poiExtraSizeBytes
        kind == PackageKind.CITIES -> region.citiesSizeBytes
        else -> region.poiSizeBytes
    }

    fun availableStorageBytes(): Long = regionStorage.availableBytes()

    /** Celle dei civici a griglia gia' installate (id -> version), per la dimensione da scaricare (RegionListViewModel). */
    fun installedAddressCells(regionId: String): Map<String, String> = regionStorage.installedAddressCells(regionId)
}

private val FILE_PACKAGES = mapOf(
    PackageKind.MAP to RegionStorage.MAP_FILE,
    PackageKind.ROUTING to RegionStorage.ROUTING_DIR,
    PackageKind.ADDRESSES to RegionStorage.ADDRESSES_FILE,
    PackageKind.TRANSIT to RegionStorage.TRANSIT_DIR,
)

private fun InstalledRegionEntity.toDomain() = RegionPackage(
    regionId = regionId,
    displayName = displayName,
    countryCode = countryCode,
    mapVersion = mapVersion,
    routingVersion = routingVersion,
    poiVersion = poiVersion,
    poiExtraVersion = poiExtraVersion,
    addressesVersion = addressesVersion,
    poiSizeBytes = poiSizeBytes,
    poiExtraSizeBytes = poiExtraSizeBytes,
    sizeBytes = sizeBytes,
    previewVersion = previewVersion,
    citiesVersion = citiesVersion,
    citiesSizeBytes = citiesSizeBytes,
    transitVersion = transitVersion,
)
