package com.pockettravel.core.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.pockettravel.core.data.GuideCategory

@Entity(tableName = "guide_sections", indices = [Index("regionId")])
data class GuideSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val regionId: String,
    val category: GuideCategory,
    val title: String,
    val body: String,
    val sourceUrl: String,
    // Tradotta automaticamente dalla pagina nell'altra lingua (pipeline: colonna translated di guides.db).
    @ColumnInfo(defaultValue = "0") val translated: Boolean = false,
)
