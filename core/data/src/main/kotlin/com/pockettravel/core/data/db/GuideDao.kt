package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Candidato di ricerca con il matchinfo FTS4 grezzo (formato 'pcx'), per ordinare per rilevanza
 * lato Kotlin: FTS4 non ha bm25() come FTS5. Vedi GuideRepository.searchInRegion per la lettura
 * del blob.
 */
data class GuideSectionMatch(
    @Embedded val section: GuideSectionEntity,
    val matchinfo: ByteArray,
)

@Dao
interface GuideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sections: List<GuideSectionEntity>)

    @Query("SELECT * FROM guide_sections WHERE regionId = :regionId ORDER BY category")
    suspend fun sectionsForRegion(regionId: String): List<GuideSectionEntity>

    // Nessun ORDER BY per rilevanza: FTS4 non ha bm25(), quindi si prendono fino a candidateLimit
    // candidati (in ordine di rowid) col loro matchinfo, e GuideRepository.searchInRegion li
    // riordina in Kotlin prima di tagliare al limite richiesto dal chiamante.
    @Query(
        """
        SELECT guide_sections.*, matchinfo(guide_sections_fts, 'pcx') AS matchinfo FROM guide_sections
        JOIN guide_sections_fts ON guide_sections.id = guide_sections_fts.rowid
        WHERE guide_sections_fts MATCH :query AND guide_sections.regionId = :regionId
        LIMIT :candidateLimit
        """
    )
    suspend fun searchInRegionRanked(regionId: String, query: String, candidateLimit: Int): List<GuideSectionMatch>

    @Query("DELETE FROM guide_sections")
    suspend fun deleteAll()

    // Da chiamare dopo un reimport completo (GuidesImporter): 'optimize' fonde i segmenti dell'indice
    // FTS accumulati da delete+insert in uno solo, invece di lasciarli frammentarsi nel tempo.
    @Query("INSERT INTO guide_sections_fts(guide_sections_fts) VALUES('optimize')")
    suspend fun optimizeFts()
}
