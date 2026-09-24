package com.pockettravel.core.data

import androidx.room.withTransaction
import com.pockettravel.core.data.db.InstalledGuidesEntity
import com.pockettravel.core.data.db.InstalledRegionEntity
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.RegionPackageDao
import com.pockettravel.core.data.db.RegionDatabase
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RegionRepository @Inject constructor(
    private val regionPackageDao: RegionPackageDao,
    private val poiDao: PoiDao,
    private val regionStorage: RegionStorage,
    private val database: RegionDatabase,
) {
    fun observeInstalled(): Flow<List<RegionPackage>> =
        regionPackageDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun displayName(regionId: String): String? =
        regionPackageDao.findById(regionId)?.displayName

    suspend fun installed(regionId: String): RegionPackage? = regionPackageDao.findById(regionId)?.toDomain()

    /**
     * Registra i pacchetti appena installati ([versions]), conservando gli altri gia' presenti.
     * [poiSizeBytes] e' richiesto quando tra i pacchetti c'e' [PackageKind.POI].
     */
    suspend fun markPackagesInstalled(
        regionId: String,
        displayName: String,
        countryCode: String?,
        versions: Map<PackageKind, String>,
        poiSizeBytes: Long? = null,
    ) {
        require(PackageKind.POI !in versions || poiSizeBytes != null) { "poiSizeBytes mancante per $regionId" }
        val current = installed(regionId)
        save(
            regionId, displayName, countryCode ?: current?.countryCode,
            versionOf = { kind -> versions[kind] ?: current?.versionOf(kind) },
            poiSizeBytes = if (PackageKind.POI in versions) poiSizeBytes else current?.poiSizeBytes,
        )
    }

    /** Elimina un solo pacchetto della regione; tolto l'ultimo, la regione non risulta piu' installata. */
    suspend fun removePackage(regionId: String, kind: PackageKind) {
        val current = installed(regionId) ?: return
        database.withTransaction {
            when (kind) {
                PackageKind.MAP -> check(regionStorage.deletePackage(regionId, RegionStorage.MAP_FILE)) { "Impossibile eliminare la mappa di $regionId" }
                PackageKind.ROUTING -> check(regionStorage.deletePackage(regionId, RegionStorage.ROUTING_DIR)) { "Impossibile eliminare il routing di $regionId" }
                PackageKind.POI -> poiDao.deleteForRegion(regionId)
            }
            save(
                regionId, current.displayName, current.countryCode,
                versionOf = { if (it == kind) null else current.versionOf(it) },
                poiSizeBytes = if (kind == PackageKind.POI) null else current.poiSizeBytes,
            )
        }
    }

    private suspend fun save(regionId: String, displayName: String, countryCode: String?, versionOf: (PackageKind) -> String?, poiSizeBytes: Long?) {
        if (PackageKind.entries.all { versionOf(it) == null }) {
            remove(regionId)
            return
        }
        val diskBytes = regionStorage.packageBytes(regionId, RegionStorage.MAP_FILE) + regionStorage.packageBytes(regionId, RegionStorage.ROUTING_DIR)
        regionPackageDao.upsert(
            InstalledRegionEntity(
                regionId = regionId,
                displayName = displayName,
                countryCode = countryCode,
                mapVersion = versionOf(PackageKind.MAP),
                routingVersion = versionOf(PackageKind.ROUTING),
                poiVersion = versionOf(PackageKind.POI),
                poiSizeBytes = poiSizeBytes,
                sizeBytes = diskBytes + (poiSizeBytes ?: 0L),
                installedAt = System.currentTimeMillis(),
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

    // Le guide restano: sono un pacchetto unico per tutte le regioni, non di questa regione.
    suspend fun remove(regionId: String) {
        check(regionStorage.delete(regionId)) { "Impossibile eliminare la regione $regionId" }
        database.withTransaction {
            regionPackageDao.deleteById(regionId)
            poiDao.deleteForRegion(regionId)
        }
    }

    /** Byte occupati da un pacchetto installato: mappa e routing dal disco, POI dalla dimensione registrata. */
    fun packageBytes(region: RegionPackage, kind: PackageKind): Long? = when {
        region.versionOf(kind) == null -> null
        kind == PackageKind.MAP -> regionStorage.packageBytes(region.regionId, RegionStorage.MAP_FILE)
        kind == PackageKind.ROUTING -> regionStorage.packageBytes(region.regionId, RegionStorage.ROUTING_DIR)
        else -> region.poiSizeBytes
    }

    fun availableStorageBytes(): Long = regionStorage.availableBytes()
}

private fun InstalledRegionEntity.toDomain() = RegionPackage(
    regionId = regionId,
    displayName = displayName,
    countryCode = countryCode,
    mapVersion = mapVersion,
    routingVersion = routingVersion,
    poiVersion = poiVersion,
    poiSizeBytes = poiSizeBytes,
    sizeBytes = sizeBytes,
)
