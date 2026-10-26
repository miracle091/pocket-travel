package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// I turni si cancellano con la loro conversazione (ForeignKey.CASCADE in ConversationTurnEntity).
@Dao
interface ConversationDao {
    @Insert
    suspend fun insertConversation(conversation: ConversationEntity)

    // Un turno con l'id di uno salvato prende il suo posto in un'istruzione sola (risposta rifatta); id 0 = turno nuovo.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: ConversationTurnEntity)

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE conversations SET archiveCount = archiveCount + 1 WHERE id = :id")
    suspend fun markArchived(id: String)

    // Una ripresa segue sempre un'archiviazione, anche quando l'app e' stata chiusa senza registrarla (processo terminato).
    @Query("UPDATE conversations SET resumeCount = resumeCount + 1, archiveCount = MAX(archiveCount, resumeCount + 1), lastResumedAt = :at WHERE id = :id")
    suspend fun markResumed(id: String, at: Long)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun all(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun find(id: String): ConversationEntity?

    @Query("SELECT * FROM conversation_turns WHERE conversationId = :conversationId ORDER BY id")
    suspend fun turns(conversationId: String): List<ConversationTurnEntity>

    @Query("SELECT COUNT(*) FROM conversations")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    // Con Long.MAX_VALUE cancella tutta la cronologia.
    @Query("DELETE FROM conversations WHERE updatedAt < :cutoff")
    suspend fun deleteUpdatedBefore(cutoff: Long)
}
