package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ambasciata o consolato da Wikidata (tabella diplomatic_missions di guides.db): [sending] e [host] sono
 * codici ISO 3166-1 alpha-2 minuscoli, [kind] e' embassy, consulate_general o consulate (anche onorario).
 */
@Entity(tableName = "diplomatic_missions", indices = [Index(value = ["sending", "host"])])
data class DiplomaticMissionEntity(
    @PrimaryKey val wikidata: String,
    val sending: String,
    val host: String,
    val kind: String,
    val name: String,
    val nameEn: String?,
    val city: String?,
    val address: String?,
    val phone: String?,
    val website: String?,
    val email: String?,
    val lat: Double?,
    val lon: Double?,
)
