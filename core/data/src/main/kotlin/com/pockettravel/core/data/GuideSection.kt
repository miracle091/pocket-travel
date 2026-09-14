package com.pockettravel.core.data

data class GuideSection(
    val regionId: String,
    val category: GuideCategory,
    val title: String,
    val body: String,
    val sourceUrl: String,
)
