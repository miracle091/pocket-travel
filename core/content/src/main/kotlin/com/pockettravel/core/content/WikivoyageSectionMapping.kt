package com.pockettravel.core.content

import com.pockettravel.core.data.GuideCategory

internal val wikivoyageHeadingToCategory: Map<String, GuideCategory> = mapOf(
    "respect" to GuideCategory.USI_COSTUMI,
    "get in" to GuideCategory.DOGANE,
    "stay healthy" to GuideCategory.SALUTE,
    "stay safe" to GuideCategory.SICUREZZA,
    "get around" to GuideCategory.TRASPORTI,
    "talk" to GuideCategory.FRASI_UTILI,
)
