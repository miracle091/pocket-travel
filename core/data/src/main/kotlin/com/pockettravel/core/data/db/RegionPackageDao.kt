package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RegionPackageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(region: InstalledRegionEntity)

    @Query("SELECT * FROM installed_regions ORDER BY displayName")
    fun observeAll(): Flow<List<InstalledRegionEntity>>

    @Query("SELECT * FROM installed_regions WHERE regionId = :regionId")
    suspend fun findById(regionId: String): InstalledRegionEntity?

    @Query("DELETE FROM installed_regions WHERE regionId = :regionId")
    suspend fun deleteById(regionId: String)

    @Query("UPDATE installed_regions SET countryCode = :countryCode WHERE regionId = :regionId AND countryCode IS NULL")
    suspend fun fillCountryCode(regionId: String, countryCode: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGuides(guides: InstalledGuidesEntity)

    @Query("SELECT version FROM installed_guides WHERE id = 0")
    suspend fun guidesVersion(): String?

    @Query("SELECT * FROM installed_guides WHERE id = 0")
    fun observeGuides(): Flow<InstalledGuidesEntity?>
}
