package com.pockettravel.core.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

/** Poche decine di termini per regione: l'assistente li legge tutti e cerca i nomi nella domanda in Kotlin. */
@Dao
interface GuideTermDao {
    @Insert
    suspend fun insertAll(terms: List<GuideTermEntity>)

    @Query("SELECT * FROM guide_terms WHERE regionId IN (:regionIds) ORDER BY id")
    suspend fun termsForRegions(regionIds: List<String>): List<GuideTermEntity>

    @Query("DELETE FROM guide_terms")
    suspend fun deleteAll()
}
