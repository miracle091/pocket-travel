package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// I dati sensibili (nome, numero documento, ecc.) vivono solo dentro encryptedPayload, cifrati
// con KeystoreCipher — vedi PassportRepository. id/createdAt/updatedAt restano in chiaro perche'
// non sensibili e servono per ordinare la lista senza dover decifrare tutto.
@Entity(tableName = "passport_vault")
data class PassportEntity(
    @PrimaryKey val id: String,
    val encryptedPayload: String,
    val createdAt: Long,
    val updatedAt: Long,
)
