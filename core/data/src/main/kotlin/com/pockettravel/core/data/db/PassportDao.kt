package com.pockettravel.core.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
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

    @Query("DELETE FROM passport_vault")
    suspend fun deleteAll()
}
