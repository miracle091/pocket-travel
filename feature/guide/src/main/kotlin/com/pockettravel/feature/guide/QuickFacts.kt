package com.pockettravel.feature.guide

import android.icu.util.ULocale
import java.util.Currency
import java.util.Locale

// Fatti rapidi della guida, ricomposti per la schermata: il testo pubblicato (Quickbar di
// Wikivoyage, GenerateGuideContent.quickFactsSection) resta com'e' per l'assistente IA, qui si
// aggiungono i dati che il Quickbar non ha e si tolgono quelli che la guida mostra gia' altrove.

/** Dati aggiunti ai fatti rapidi quando la guida non li ha. */
internal data class QuickFactsExtra(val language: String?, val currency: String?, val transport: String?)

private val FIELD_ORDER = listOf("Lingua", "Elettricità", "Fuso orario", "Valuta", "Trasporti")

// Hanno gia' la loro scheda, con i pulsanti per chiamare.
private const val EMERGENCY_FIELD = "Numeri di emergenza"

internal fun quickFactsBody(body: String, extra: QuickFactsExtra): String {
    val fields = LinkedHashMap<String, String>()
    val loose = mutableListOf<String>()
    for (line in body.lines().filter { it.isNotBlank() }) {
        val separator = line.indexOf(": ")
        if (separator > 0) fields[line.substring(0, separator)] = line.substring(separator + 2) else loose += line
    }
    fields.remove(EMERGENCY_FIELD)
    fields["Fuso orario"]?.let { fields["Fuso orario"] = it.replace(Regex("""\bUTC\b"""), "GMT") }
    if ("Lingua" !in fields) extra.language?.let { fields["Lingua"] = it }
    if ("Valuta" !in fields) extra.currency?.let { fields["Valuta"] = it }
    extra.transport?.let { fields["Trasporti"] = it }
    val ordered = FIELD_ORDER.mapNotNull { key -> fields[key]?.let { "$key: $it" } }
    val others = fields.filterKeys { it !in FIELD_ORDER }.map { (key, value) -> "$key: $value" }
    return (ordered + others + loose).joinToString("\n")
}

/** "yen giapponese (JPY)" dal codice paese ISO, null se il paese non ha una valuta nota. */
internal fun currencyOf(countryCode: String): String? = runCatching {
    val currency = Currency.getInstance(Locale.Builder().setRegion(countryCode).build())
    "${currency.getDisplayName(Locale.ITALIAN)} (${currency.currencyCode})"
}.getOrNull()

/** Lingua principale del paese ("Giapponese"), dai dati CLDR di Android; null se sconosciuta. */
internal fun languageOf(countryCode: String): String? = runCatching {
    val likely = ULocale.addLikelySubtags(ULocale("und_$countryCode"))
    likely.getDisplayLanguage(ULocale.ITALIAN).takeIf { likely.language != "und" && it.isNotBlank() }
        ?.replaceFirstChar { it.uppercase(Locale.ITALIAN) }
}.getOrNull()
