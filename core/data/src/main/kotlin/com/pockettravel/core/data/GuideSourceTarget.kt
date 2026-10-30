package com.pockettravel.core.data

/**
 * Sezione della guida a cui porta una fonte sotto una risposta dell'assistente: [city] è null per la guida della
 * nazione, altrimenti la guida di quella città. [answer] è il testo della risposta, da cui la guida evidenzia le frasi usate.
 */
data class GuideSourceTarget(
    val city: String?,
    val category: GuideCategory,
    val title: String,
    val answer: String,
)
