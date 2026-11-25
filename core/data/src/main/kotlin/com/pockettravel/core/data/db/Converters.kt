package com.pockettravel.core.data.db

import androidx.room3.ColumnTypeConverter
import com.pockettravel.core.data.GuideCategory

class Converters {
    @ColumnTypeConverter
    fun fromCategory(category: GuideCategory): String = category.name

    @ColumnTypeConverter
    fun toCategory(value: String): GuideCategory = GuideCategory.valueOf(value)
}
