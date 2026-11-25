package com.pockettravel.core.data.db

import androidx.room3.Entity
import androidx.room3.Fts4
import androidx.room3.FtsOptions

// unicode61: casefolda anche le maiuscole accentate indicizzate (il tokenizer di default "simple" le
// lascia intatte, cosi' una query in minuscolo come "perù" non trova "PERÙ" nel testo).
@Fts4(contentEntity = GuideSectionEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "guide_sections_fts")
data class GuideSectionFts(
    val title: String,
    val body: String,
)
