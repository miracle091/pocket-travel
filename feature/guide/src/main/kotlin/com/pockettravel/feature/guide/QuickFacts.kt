package com.pockettravel.feature.guide

import android.icu.util.ULocale
import java.util.Currency
import java.util.Locale

// Fatti rapidi della guida, ricomposti per la schermata: il testo pubblicato (dai Fatti rapidi di
// Wikivoyage, GenerateGuideContent.quickFactsSection) resta com'e' per l'assistente IA, qui si
// aggiungono i dati che i Fatti rapidi pubblicati non hanno e si tolgono quelli che la guida mostra gia' altrove.

/** Dati aggiunti ai fatti rapidi quando la guida non li ha. */
internal data class QuickFactsExtra(val language: String?, val currency: String?, val transport: String?)

/**
 * Nomi dei campi nella lingua delle guide installate (italiane o inglesi, vedi englishQuickFactsSection
 * nella pipeline), con [locale] per i valori aggiunti dall'app (lingua e valuta).
 */
internal data class QuickFactsLabels(
    val language: String,
    val electricity: String,
    val timeZone: String,
    val currency: String,
    val transport: String,
    val emergency: String,
    val locale: Locale,
) {
    val order get() = listOf(language, electricity, timeZone, currency, transport)

    companion object {
        val ITALIAN = QuickFactsLabels("Lingua", "Elettricità", "Fuso orario", "Valuta", "Trasporti principali", "Numeri di emergenza", Locale.ITALIAN)
        val ENGLISH = QuickFactsLabels("Language", "Electricity", "Time zone", "Currency", "Main transport", "Emergency numbers", Locale.ENGLISH)

        /** Dai campi del testo pubblicato; senza campi riconoscibili, dalla lingua dell'interfaccia. */
        fun of(body: String, uiLanguage: String): QuickFactsLabels {
            val keys = body.lines().map { it.substringBefore(": ") }.toSet()
            return when {
                // anche i soli numeri di emergenza (guide inglesi senza Fatti rapidi italiani) bastano a riconoscere la lingua
                keys.any { it in ENGLISH.order || it == ENGLISH.emergency } -> ENGLISH
                keys.any { it in ITALIAN.order || it == ITALIAN.emergency } -> ITALIAN
                uiLanguage == "en" -> ENGLISH
                else -> ITALIAN
            }
        }
    }
}

internal fun quickFactsBody(body: String, extra: QuickFactsExtra, labels: QuickFactsLabels = QuickFactsLabels.of(body, "it")): String {
    val fields = LinkedHashMap<String, String>()
    val loose = mutableListOf<String>()
    for (line in body.lines().filter { it.isNotBlank() }) {
        val separator = line.indexOf(": ")
        if (separator > 0) fields[line.substring(0, separator)] = line.substring(separator + 2) else loose += line
    }
    // Hanno gia' il loro riquadro, con i pulsanti per chiamare.
    fields.remove(labels.emergency)
    fields[labels.timeZone]?.let { fields[labels.timeZone] = it.replace(Regex("""\bUTC\b"""), "GMT") }
    if (labels.language !in fields) extra.language?.let { fields[labels.language] = it }
    if (labels.currency !in fields) extra.currency?.let { fields[labels.currency] = it }
    extra.transport?.let { fields[labels.transport] = it }
    // un valore su piu' righe (l'elenco dei trasporti) va a capo dopo l'etichetta
    val line = { key: String, value: String -> if ('\n' in value) "$key:\n$value" else "$key: $value" }
    val ordered = labels.order.mapNotNull { key -> fields[key]?.let { line(key, it) } }
    val others = fields.filterKeys { it !in labels.order }.map { (key, value) -> line(key, value) }
    return (ordered + others + loose).joinToString("\n")
}

/** "yen giapponese (JPY)" / "Japanese yen (JPY)" dal codice paese ISO, null se il paese non ha una valuta nota. */
internal fun currencyOf(countryCode: String, locale: Locale = Locale.ITALIAN): String? = runCatching {
    val currency = Currency.getInstance(Locale.Builder().setRegion(countryCode).build())
    "${currency.getDisplayName(locale)} (${currency.currencyCode})"
}.getOrNull()

/** Lingua principale del paese ("Giapponese"/"Japanese"), dai dati CLDR di Android; null se sconosciuta. */
internal fun languageOf(countryCode: String, locale: Locale = Locale.ITALIAN): String? = runCatching {
    val likely = ULocale.addLikelySubtags(ULocale("und_$countryCode"))
    likely.getDisplayLanguage(ULocale.forLocale(locale)).takeIf { likely.language != "und" && it.isNotBlank() }
        ?.replaceFirstChar { it.uppercase(locale) }
}.getOrNull()
