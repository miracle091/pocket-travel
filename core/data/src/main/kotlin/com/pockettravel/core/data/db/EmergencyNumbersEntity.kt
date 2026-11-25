package com.pockettravel.core.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "emergency_numbers")
data class EmergencyNumbersEntity(
    @PrimaryKey val regionId: String,
    val general: String?,
    val police: String,
    val ambulance: String,
    val fire: String,
)
