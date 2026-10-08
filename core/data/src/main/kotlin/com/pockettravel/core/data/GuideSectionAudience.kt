package com.pockettravel.core.data

/*
 * Sezioni di guida per una sola nazionalita': il pipeline lo scrive in fondo al link della sezione, "#for-nationality=IL"
 * (solo per chi ha nazionalita' israeliana) o "#not-for-nationality=IL" (per tutti gli altri). Oggi le usano solo i
 * consigli FCDO della Palestina, la cui pagina copre anche Israele.
 */
private const val FOR_NATIONALITY = "#for-nationality="
private const val NOT_FOR_NATIONALITY = "#not-for-nationality="

/** True se la sezione col link [sourceUrl] va mostrata a chi ha nazionalita' [nationality] (codice ISO, null se non scelta). */
fun isGuideSectionFor(sourceUrl: String, nationality: String?): Boolean {
    val forOnly = sourceUrl.substringAfter(FOR_NATIONALITY, "").takeIf { it.isNotEmpty() }
    val notFor = sourceUrl.substringAfter(NOT_FOR_NATIONALITY, "").takeIf { it.isNotEmpty() }
    return when {
        forOnly != null -> forOnly.equals(nationality, ignoreCase = true)
        notFor != null -> !notFor.equals(nationality, ignoreCase = true)
        else -> true
    }
}

/** Il link della sezione senza il suffisso della nazionalita', per mostrarlo e aprirlo. */
fun guideSourceUrlWithoutAudience(sourceUrl: String): String =
    sourceUrl.substringBefore(FOR_NATIONALITY).substringBefore(NOT_FOR_NATIONALITY)
