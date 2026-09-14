package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pockettravel.core.data.GuideCategory

@Entity(tableName = "guide_sections")
data class GuideSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val regionId: String,
    val category: GuideCategory,
    val title: String,
    val body: String,
    val sourceUrl: String,
)
