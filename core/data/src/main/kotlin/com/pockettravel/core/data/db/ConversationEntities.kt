package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Una conversazione della cronologia dell'assistente (solo motore Sul dispositivo). Il contenuto (titolo, regione) e'
 * in [encryptedSummary], cifrato con la chiave della conversazione; la chiave e' in [wrappedKey], avvolta con la chiave
 * pubblica della cronologia (HistoryKeys). Restano in chiaro solo gli orari, per ordinare e cancellare senza sblocco,
 * e i contatori del debug.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val wrappedKey: String,
    val encryptedSummary: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archiveCount: Int = 0,
    val resumeCount: Int = 0,
    val lastResumedAt: Long? = null,
)

/** Un turno (domanda e risposta) di una conversazione, cifrato con la chiave della conversazione. */
@Entity(
    tableName = "conversation_turns",
    foreignKeys = [
        ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("conversationId")],
)
data class ConversationTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val encryptedPayload: String,
    val createdAt: Long,
)
