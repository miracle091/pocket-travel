package com.pockettravel.pipeline

import java.util.Locale

/**
 * Dati di una regione da Wikidata (CC0), da resources/countries.tsv: file curato, rigenerato a mano con
 * tools/data-pipeline/scripts/wikidata_countries.py (colonne, dati per regione e correzioni descritti li'). Piu' valori
 * separati da "; ", campo vuoto = dato assente. I numeri di emergenza del TSV non si usano: quelli delle guide restano
 * quelli curati di emergency-numbers.tsv.
 */
data class CountryFacts(
    val capitalIt: String,
    val capitalEn: String,
    val currency: String,
    val currencyIt: String,
    val currencyEn: String,
    val driving: String,
    val callingCode: String,
    val languagesIt: String,
    val languagesEn: String,
    val timeZones: String,
    val plugs: String,
    val voltage: String,
)

private const val COUNTRY_FACTS_COLUMNS = 15

internal fun parseCountryFacts(tsv: String): Map<String, CountryFacts> =
    tsv.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split("\t") }
        .onEach { require(it.size == COUNTRY_FACTS_COLUMNS) { "countries.tsv: riga con ${it.size} colonne: ${it.first()}" } }
        .associate { c ->
            c[0] to CountryFacts(
                capitalIt = c[2], capitalEn = c[3], currency = c[4], currencyIt = c[5], currencyEn = c[6], driving = c[7],
                callingCode = c[8], languagesIt = c[9], languagesEn = c[10], timeZones = c[11], plugs = c[13], voltage = c[14],
            )
        }

private val countryFactsByRegion: Map<String, CountryFacts> by lazy {
    parseCountryFacts(requireNotNull(CountryFacts::class.java.getResource("/countries.tsv")) { "countries.tsv mancante" }.readText())
}

/** Nomi dei campi dei Fatti rapidi nella lingua della guida (gli stessi che l'app riconosce in QuickFactsLabels). */
internal class QuickFactLabels(
    val capital: String,
    val language: String,
    val electricity: String,
    val timeZone: String,
    val currency: String,
    val callingCode: String,
    val drivingSide: String,
) {
    val order get() = listOf(capital, language, electricity, timeZone, currency, callingCode, drivingSide)

    companion object {
        val ITALIAN = QuickFactLabels("Capitale", "Lingua", "Elettricità", "Fuso orario", "Valuta", "Prefisso telefonico", "Lato di guida")
        val ENGLISH = QuickFactLabels("Capital", "Language", "Electricity", "Time zone", "Currency", "Calling code", "Driving side")
    }
}

private fun String.values(): List<String> = split("; ").filter { it.isNotBlank() }

// "UTC+01:00" -> "UTC+1", "UTC+05:30" -> "UTC+5:30", "UTC-03:30" -> "UTC-3:30": come nei Fatti rapidi di Wikivoyage.
private val utcOffsetRegex = Regex("""UTC([+-])(\d{2}):(\d{2})""")

internal fun shortUtcOffset(value: String): String = utcOffsetRegex.matchEntire(value)?.destructured?.let { (sign, hours, minutes) ->
    "UTC$sign${hours.toInt()}" + if (minutes == "00") "" else ":$minutes"
} ?: value

/**
 * Campi dei Fatti rapidi da Wikidata per [regionId], nella lingua della guida ([english]), in ordine: capitale, lingua,
 * elettricita', fuso orario, valuta, prefisso, lato di guida. Senza i campi assenti; vuoto per una regione che non e'
 * nel TSV.
 */
internal fun countryFactFields(regionId: String, english: Boolean, facts: CountryFacts? = countryFactsByRegion[regionId]): Map<String, String> {
    facts ?: return emptyMap()
    val labels = if (english) QuickFactLabels.ENGLISH else QuickFactLabels.ITALIAN
    val locale = if (english) Locale.ENGLISH else Locale.ITALIAN
    val plugs = facts.plugs.split(", ").filter { it.isNotBlank() }
    val plugText = when {
        plugs.isEmpty() -> null
        english -> (if (plugs.size == 1) "plug type " else "plug types ") + plugs.joinToString(", ")
        else -> (if (plugs.size == 1) "presa " else "prese ") + plugs.joinToString(", ")
    }
    val voltage = facts.voltage.values().takeIf { it.isNotEmpty() }?.joinToString("/", postfix = " V")
    val currencyNames = (if (english) facts.currencyEn else facts.currencyIt).values()
    val currencyCodes = facts.currency.values()
    val currency = when {
        currencyNames.isEmpty() -> null
        currencyCodes.isEmpty() -> currencyNames.joinToString(", ")
        else -> currencyNames.joinToString(", ") + currencyCodes.joinToString(", ", prefix = " (", postfix = ")")
    }
    val driving = when (facts.driving) {
        "right" -> if (english) "right" else "destra"
        "left" -> if (english) "left" else "sinistra"
        else -> null
    }
    val values = listOf(
        (if (english) facts.capitalEn else facts.capitalIt).values().joinToString(", "),
        (if (english) facts.languagesEn else facts.languagesIt).values().joinToString(", ").replaceFirstChar { it.titlecase(locale) },
        listOfNotNull(voltage, plugText).joinToString(", "),
        facts.timeZones.values().joinToString(", ") { shortUtcOffset(it) },
        currency.orEmpty(),
        facts.callingCode.values().joinToString(", "),
        driving.orEmpty(),
    )
    return labels.order.zip(values).filter { it.second.isNotEmpty() }.toMap()
}
