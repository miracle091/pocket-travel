package com.pockettravel.core.data

data class GuideSection(
    val regionId: String,
    val category: GuideCategory,
    val title: String,
    val body: String,
    val sourceUrl: String,
    // Tradotta automaticamente dalla pagina in sourceUrl, nell'altra lingua.
    val translated: Boolean = false,
)
