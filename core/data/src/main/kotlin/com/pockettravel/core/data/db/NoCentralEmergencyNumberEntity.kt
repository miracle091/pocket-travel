package com.pockettravel.core.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** Regione senza numero di emergenza centralizzato: la guida lo dichiara al posto dei numeri. */
@Entity(tableName = "emergency_numbers_none")
data class NoCentralEmergencyNumberEntity(
    @PrimaryKey val regionId: String,
)
