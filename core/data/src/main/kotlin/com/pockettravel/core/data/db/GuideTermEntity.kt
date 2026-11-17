package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Termine locale di una guida (piatto, bevanda, festa, usanza, abito, strumento): voce di Wikipedia del paese che la guida
 * nomina, con l'introduzione della voce come spiegazione. Dalla tabella guide_terms di guides.db; [aliases] sono gli altri
 * nomi con cui il termine compare nel testo, separati da "\n".
 */
@Entity(tableName = "guide_terms", indices = [Index("regionId")])
data class GuideTermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val regionId: String,
    val term: String,
    val aliases: String,
    val title: String,
    val summary: String,
    val sourceUrl: String,
    val revision: Long?,
)
