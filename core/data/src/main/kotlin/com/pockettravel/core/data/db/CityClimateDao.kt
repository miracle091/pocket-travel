package com.pockettravel.core.data.db

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Query del clima mensile delle citta': CityDao le eredita, cosi' RegionDatabase espone un solo DAO per le citta'. */
interface CityClimateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClimate(rows: List<CityClimateEntity>)

    @Query("SELECT * FROM city_climate WHERE regionId = :regionId AND city = :city ORDER BY month")
    suspend fun climateFor(regionId: String, city: String): List<CityClimateEntity>

    @Query("DELETE FROM city_climate WHERE regionId = :regionId")
    suspend fun deleteClimateForRegion(regionId: String)
}
