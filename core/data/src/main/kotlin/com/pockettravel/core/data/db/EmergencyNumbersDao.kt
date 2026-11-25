package com.pockettravel.core.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface EmergencyNumbersDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(numbers: List<EmergencyNumbersEntity>)

    @Query("SELECT * FROM emergency_numbers WHERE regionId = :regionId")
    suspend fun forRegion(regionId: String): EmergencyNumbersEntity?

    @Query("DELETE FROM emergency_numbers")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoCentralNumber(regions: List<NoCentralEmergencyNumberEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM emergency_numbers_none WHERE regionId = :regionId)")
    suspend fun hasNoCentralNumber(regionId: String): Boolean

    @Query("DELETE FROM emergency_numbers_none")
    suspend fun deleteAllNoCentralNumber()
}
