package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DiplomaticMissionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(missions: List<DiplomaticMissionEntity>)

    @Query("SELECT * FROM diplomatic_missions WHERE sending = :sending AND host = :host ORDER BY kind, name")
    suspend fun missions(sending: String, host: String): List<DiplomaticMissionEntity>

    @Query("DELETE FROM diplomatic_missions")
    suspend fun deleteAll()
}
