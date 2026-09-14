package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PassportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(passport: PassportEntity)

    @Query("SELECT * FROM passport_vault ORDER BY createdAt")
    fun observeAll(): Flow<List<PassportEntity>>

    @Query("SELECT * FROM passport_vault WHERE id = :id")
    suspend fun findById(id: String): PassportEntity?

    @Query("DELETE FROM passport_vault WHERE id = :id")
    suspend fun deleteById(id: String)
}
