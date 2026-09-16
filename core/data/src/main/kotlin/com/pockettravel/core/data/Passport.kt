package com.pockettravel.core.data

import kotlinx.serialization.Serializable

// I record esistenti (senza documentType nel JSON cifrato) deserializzano con il default
// PASSPORT: nessuna migrazione necessaria, coerente con come kotlinx.serialization gestisce i
// campi mancanti nei @Serializable.
enum class DocumentType { PASSPORT, TICKET, OTHER }

// Campi tipizzati minimi. photoFileNames referenzia file cifrati a parte (vedi PassportPhotoStore
// + PassportRepository.savePhoto/loadPhoto): un'immagine e' categoricamente piu' sensibile di un
// campo testuale (metadati EXIF/posizione, ripuliti alla cattura — vedi PassportPhotoCapture nel
// modulo feature:vault) e ha un proprio ciclo di vita file-su-disco, non entra nel blob JSON.
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
    val photoFileNames: List<String> = emptyList(),
    val documentType: DocumentType = DocumentType.PASSPORT,
)
