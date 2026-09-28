package com.pockettravel.core.data

enum class GuideCategory {
    USI_COSTUMI,
    DOGANE,
    SALUTE,
    SICUREZZA,
    TRASPORTI,
    FRASI_UTILI,
    ALLOGGIO,
    CIBO_BEVANDE,
    ACQUISTI,
    CONNETTIVITA,
    VITA_QUOTIDIANA,
    // Aggiunte per le guide delle citta' e i fatti rapidi.
    DA_SAPERE,
    COSA_VEDERE,
    FATTI_RAPIDI,
}

/**
 * [value] come categoria, o null se non e' (piu') una delle costanti note: l'import dei pacchetti
 * (GuidesImporter, CityImporter in core/sync) salta la sezione invece di far fallire l'intero file
 * quando la pipeline pubblica una categoria che questa build dell'app non conosce ancora.
 */
fun guideCategoryOrNull(value: String): GuideCategory? = runCatching { GuideCategory.valueOf(value) }.getOrNull()
