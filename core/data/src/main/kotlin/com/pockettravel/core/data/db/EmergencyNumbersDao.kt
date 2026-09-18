package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmergencyNumbersDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(numbers: List<EmergencyNumbersEntity>)

    @Query("SELECT * FROM emergency_numbers WHERE regionId = :regionId")
    suspend fun forRegion(regionId: String): EmergencyNumbersEntity?

    @Query("DELETE FROM emergency_numbers WHERE regionId = :regionId")
    suspend fun deleteForRegion(regionId: String)
}
