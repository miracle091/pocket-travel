package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_regions")
data class InstalledRegionEntity(
    @PrimaryKey val regionId: String,
    val displayName: String,
    val mapVersion: String?,
    val routingVersion: String?,
    val poiVersion: String?,
    val sizeBytes: Long,
    val installedAt: Long,
)
