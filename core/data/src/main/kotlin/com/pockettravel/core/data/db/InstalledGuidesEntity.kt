package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Versione del pacchetto guide (unico per tutte le regioni): al piu' una riga, id 0. */
@Entity(tableName = "installed_guides")
data class InstalledGuidesEntity(
    @PrimaryKey val id: Int = 0,
    val version: String,
)
