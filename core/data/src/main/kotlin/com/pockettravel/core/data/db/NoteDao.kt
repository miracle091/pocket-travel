package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    // id 0 (nuova nota) fa autogenerare il rowid; un id gia' esistente sostituisce quella nota
    // (vedi NoteRepository.save). Ritorna l'id della riga inserita.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity): Long

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    // Per la ricerca (NoteRepository.search): decifrare richiede di leggere tutte le note, la
    // tabella non ha un indice full-text (sono poche, cifrate campo per campo, niente FTS).
    @Query("SELECT * FROM notes")
    suspend fun all(): List<NoteEntity>

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)
}
