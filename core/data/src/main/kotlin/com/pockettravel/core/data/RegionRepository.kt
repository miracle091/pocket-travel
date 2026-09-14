package com.pockettravel.core.data

import androidx.room.withTransaction
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.InstalledRegionEntity
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.RegionPackageDao
import com.pockettravel.core.data.db.RegionDatabase
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RegionRepository @Inject constructor(
    private val regionPackageDao: RegionPackageDao,
    private val guideDao: GuideDao,
    private val poiDao: PoiDao,
    private val regionStorage: RegionStorage,
    private val database: RegionDatabase,
) {
    fun observeInstalled(): Flow<List<RegionPackage>> =
        regionPackageDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun installedVersion(regionId: String): String? =
        regionPackageDao.findById(regionId)?.version

    suspend fun displayName(regionId: String): String? =
        regionPackageDao.findById(regionId)?.displayName

    suspend fun markInstalled(pkg: RegionPackage) =
        regionPackageDao.upsert(pkg.toEntity(installedAt = System.currentTimeMillis()))

    suspend fun <T> inInstallTransaction(block: suspend () -> T): T = database.withTransaction { block() }

    suspend fun remove(regionId: String) {
        check(regionStorage.delete(regionId)) { "Impossibile eliminare la regione $regionId" }
        database.withTransaction {
            regionPackageDao.deleteById(regionId)
            guideDao.deleteForRegion(regionId)
            poiDao.deleteForRegion(regionId)
        }
    }

    fun availableStorageBytes(): Long = regionStorage.availableBytes()
}

private fun InstalledRegionEntity.toDomain() = RegionPackage(
    regionId = regionId,
    displayName = displayName,
    version = version,
    sizeBytes = sizeBytes,
)

private fun RegionPackage.toEntity(installedAt: Long) = InstalledRegionEntity(
    regionId = regionId,
    displayName = displayName,
    version = version,
    sizeBytes = sizeBytes,
    installedAt = installedAt,
)
