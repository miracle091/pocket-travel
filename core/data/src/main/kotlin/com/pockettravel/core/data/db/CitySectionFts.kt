package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

// Stesso tokenizer di GuideSectionFts (unicode61, casefolda anche le maiuscole accentate).
@Fts4(contentEntity = CitySectionEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "city_sections_fts")
data class CitySectionFts(
    val title: String,
    val body: String,
)
