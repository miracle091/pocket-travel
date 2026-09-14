package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GuideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sections: List<GuideSectionEntity>)

    @Query("SELECT * FROM guide_sections WHERE regionId = :regionId ORDER BY category")
    suspend fun sectionsForRegion(regionId: String): List<GuideSectionEntity>

    @Query(
        """
        SELECT guide_sections.* FROM guide_sections
        JOIN guide_sections_fts ON guide_sections.id = guide_sections_fts.rowid
        WHERE guide_sections_fts MATCH :query
        """
    )
    suspend fun search(query: String): List<GuideSectionEntity>

    @Query(
        """
        SELECT guide_sections.* FROM guide_sections
        JOIN guide_sections_fts ON guide_sections.id = guide_sections_fts.rowid
        WHERE guide_sections_fts MATCH :query AND guide_sections.regionId = :regionId
        """
    )
    suspend fun searchInRegion(regionId: String, query: String): List<GuideSectionEntity>

    @Query("DELETE FROM guide_sections WHERE regionId = :regionId")
    suspend fun deleteForRegion(regionId: String)
}
