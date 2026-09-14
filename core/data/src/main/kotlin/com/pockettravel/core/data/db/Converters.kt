package com.pockettravel.core.data.db

import androidx.room.TypeConverter
import com.pockettravel.core.data.GuideCategory

class Converters {
    @TypeConverter
    fun fromCategory(category: GuideCategory): String = category.name

    @TypeConverter
    fun toCategory(value: String): GuideCategory = GuideCategory.valueOf(value)
}
