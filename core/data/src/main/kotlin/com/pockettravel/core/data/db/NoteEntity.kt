package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// title e body sono cifrati con KeystoreCipher (vedi NoteRepository), come encryptedPayload in
// PassportEntity; updatedAt resta in chiaro per ordinare la lista senza dover decifrare tutto.
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val updatedAt: Long,
)
