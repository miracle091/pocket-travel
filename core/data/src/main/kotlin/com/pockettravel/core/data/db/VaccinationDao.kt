package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** Le tabelle sono piccole (poche centinaia di righe in tutto): il motore le legge intere. */
@Dao
interface VaccinationDao {
    @Insert
    suspend fun insertYfRisk(rows: List<VaccYfRiskEntity>)

    @Insert
    suspend fun insertYfEntry(rows: List<VaccYfEntryEntity>)

    @Insert
    suspend fun insertPolioStatus(rows: List<VaccPolioStatusEntity>)

    @Insert
    suspend fun insertPolioEntry(rows: List<VaccPolioEntryEntity>)

    @Insert
    suspend fun insertSpecial(rows: List<VaccSpecialEntity>)

    @Insert
    suspend fun insertRecommended(rows: List<VaccRecommendedEntity>)

    @Insert
    suspend fun insertMeta(rows: List<VaccMetaEntity>)

    @Query("SELECT * FROM vacc_yf_risk")
    suspend fun yfRisk(): List<VaccYfRiskEntity>

    @Query("SELECT * FROM vacc_yf_entry")
    suspend fun yfEntry(): List<VaccYfEntryEntity>

    @Query("SELECT * FROM vacc_polio_status")
    suspend fun polioStatus(): List<VaccPolioStatusEntity>

    @Query("SELECT * FROM vacc_polio_entry")
    suspend fun polioEntry(): List<VaccPolioEntryEntity>

    @Query("SELECT * FROM vacc_special")
    suspend fun special(): List<VaccSpecialEntity>

    @Query("SELECT * FROM vacc_recommended")
    suspend fun recommended(): List<VaccRecommendedEntity>

    @Query("SELECT * FROM vacc_meta")
    suspend fun meta(): List<VaccMetaEntity>

    @Query("DELETE FROM vacc_yf_risk")
    suspend fun deleteYfRisk()

    @Query("DELETE FROM vacc_yf_entry")
    suspend fun deleteYfEntry()

    @Query("DELETE FROM vacc_polio_status")
    suspend fun deletePolioStatus()

    @Query("DELETE FROM vacc_polio_entry")
    suspend fun deletePolioEntry()

    @Query("DELETE FROM vacc_special")
    suspend fun deleteSpecial()

    @Query("DELETE FROM vacc_recommended")
    suspend fun deleteRecommended()

    @Query("DELETE FROM vacc_meta")
    suspend fun deleteMeta()

    /** Svuota tutte le tabelle vacc_*: l'import le sostituisce per intero a ogni pacchetto guide. */
    @Transaction
    suspend fun deleteAll() {
        deleteYfRisk()
        deleteYfEntry()
        deletePolioStatus()
        deletePolioEntry()
        deleteSpecial()
        deleteRecommended()
        deleteMeta()
    }
}
