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

    suspend fun installedVersion(regionId: String, kind: PackageKind): String? =
        regionPackageDao.findById(regionId)?.toDomain()?.versionOf(kind)

    suspend fun displayName(regionId: String): String? =
        regionPackageDao.findById(regionId)?.displayName

    suspend fun markInstalled(pkg: RegionPackage) =
        regionPackageDao.upsert(pkg.toEntity(installedAt = System.currentTimeMillis()))

    /** Versione del pacchetto guide installato, null se non ancora scaricato. */
    suspend fun installedGuidesVersion(): String? = regionPackageDao.guidesVersion()

    suspend fun markGuidesInstalled(version: String) =
        regionPackageDao.upsertGuides(InstalledGuidesEntity(version = version))

    suspend fun <T> inInstallTransaction(block: suspend () -> T): T = database.withTransaction { block() }

    // Le guide restano: sono un pacchetto unico per tutte le regioni, non di questa regione.
    suspend fun remove(regionId: String) {
        check(regionStorage.delete(regionId)) { "Impossibile eliminare la regione $regionId" }
        database.withTransaction {
            regionPackageDao.deleteById(regionId)
            poiDao.deleteForRegion(regionId)
        }
    }

    fun availableStorageBytes(): Long = regionStorage.availableBytes()
}

private fun InstalledRegionEntity.toDomain() = RegionPackage(
    regionId = regionId,
    displayName = displayName,
    mapVersion = mapVersion,
    routingVersion = routingVersion,
    poiVersion = poiVersion,
    sizeBytes = sizeBytes,
)

private fun RegionPackage.toEntity(installedAt: Long) = InstalledRegionEntity(
    regionId = regionId,
    displayName = displayName,
    mapVersion = mapVersion,
    routingVersion = routingVersion,
    poiVersion = poiVersion,
    sizeBytes = sizeBytes,
    installedAt = installedAt,
)
