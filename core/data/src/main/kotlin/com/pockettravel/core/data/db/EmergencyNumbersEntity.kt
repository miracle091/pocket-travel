package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "emergency_numbers")
data class EmergencyNumbersEntity(
    @PrimaryKey val regionId: String,
    val general: String?,
    val police: String,
    val ambulance: String,
    val fire: String,
)
