package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = GuideSectionEntity::class)
@Entity(tableName = "guide_sections_fts")
data class GuideSectionFts(
    val title: String,
    val body: String,
)
