package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/*
 * Tabelle vacc_* di guides.db (tools/data-pipeline, GenerateVaccinations), copiate cosi' come sono:
 * una riga per riga dei file curati a mano, senza chiave naturale (da qui l'id generato). I codici
 * (rule, transit, category...) restano testo: li interpreta il motore delle regole, cosi' un valore
 * nuovo pubblicato da una pipeline piu' recente non fa fallire l'import. iso2 e' ISO 3166-1 alpha-2
 * minuscolo; sources sono sigle separate da "+"; verified e' una data ISO.
 */

@Entity(tableName = "vacc_yf_risk")
data class VaccYfRiskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val iso2: String,
    val scope: String,
    val areasIt: String,
    val areasEn: String,
    val sources: String,
    val verified: String,
)

@Entity(tableName = "vacc_yf_entry")
data class VaccYfEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val iso2: String,
    val rule: String,
    val minAgeMonths: Int?,
    val transit: String,
    val fromList: String,
    val exitRequired: Boolean,
    val noteIt: String,
    val noteEn: String,
    val sources: String,
    val verified: String,
)

@Entity(tableName = "vacc_polio_status")
data class VaccPolioStatusEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val iso2: String,
    val category: String,
    val statement: String,
    val sources: String,
    val verified: String,
)

@Entity(tableName = "vacc_polio_entry")
data class VaccPolioEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val iso2: String,
    val origin: String,
    val vaccine: String,
    val timeWindow: String,
    val applies: String,
    val noteIt: String,
    val noteEn: String,
    val sources: String,
    val verified: String,
)

@Entity(tableName = "vacc_special")
data class VaccSpecialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val iso2: String,
    val purpose: String,
    val vaccine: String,
    val minAgeMonths: Int?,
    val minDaysBefore: Int?,
    val validityYears: Int?,
    val noteIt: String,
    val noteEn: String,
    val sources: String,
    val verified: String,
)

@Entity(tableName = "vacc_recommended")
data class VaccRecommendedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val iso2: String,
    val vaccine: String,
    val level: String,
    val conditionIt: String,
    val conditionEn: String,
    val sources: String,
    val verified: String,
)

/** Chiave/valore: polio_statement, polio_verified, last_review. */
@Entity(tableName = "vacc_meta")
data class VaccMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
