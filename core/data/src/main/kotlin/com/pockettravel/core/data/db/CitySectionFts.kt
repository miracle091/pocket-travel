package com.pockettravel.core.data.db

import androidx.room3.Entity
import androidx.room3.Fts4
import androidx.room3.FtsOptions

// Stesso tokenizer di GuideSectionFts (unicode61, casefolda anche le maiuscole accentate).
@Fts4(contentEntity = CitySectionEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "city_sections_fts")
data class CitySectionFts(
    val title: String,
    val body: String,
)
