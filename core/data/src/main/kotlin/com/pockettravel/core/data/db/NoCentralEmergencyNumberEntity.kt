package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Regione senza numero di emergenza centralizzato: la guida lo dichiara al posto dei numeri. */
@Entity(tableName = "emergency_numbers_none")
data class NoCentralEmergencyNumberEntity(
    @PrimaryKey val regionId: String,
)
