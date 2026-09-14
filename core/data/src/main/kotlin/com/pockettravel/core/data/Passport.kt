package com.pockettravel.core.data

import kotlinx.serialization.Serializable

// Campi tipizzati minimi per essere utile in v1 — niente foto/scansione del documento: un'immagine
// e' categoricamente piu' sensibile (metadati EXIF/posizione) e richiederebbe un proprio ciclo di
// vita file-su-disco cifrato, feature a parte non necessaria per un primo vault funzionante.
@Serializable
data class Passport(
    val id: String,
    val fullName: String,
    val documentNumber: String,
    val nationality: String,
    val dateOfBirth: String = "",
    val issueDate: String = "",
    val expiryDate: String = "",
    val note: String = "",
)
