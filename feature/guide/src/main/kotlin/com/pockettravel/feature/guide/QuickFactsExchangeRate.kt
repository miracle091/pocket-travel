package com.pockettravel.feature.guide

import com.pockettravel.core.data.ExchangeRateTable
import com.pockettravel.core.data.IndicativeRate
import com.pockettravel.core.data.indicative
import java.math.BigDecimal
import java.math.MathContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

// Tasso di cambio indicativo nel fatto rapido "Valuta", dalla valuta di chi viaggia a quella della nazione.

// Valute con un mercato parallelo: il tasso pubblicato e' quello ufficiale, il cambio che si trova puo' essere molto
// diverso.
internal val PARALLEL_MARKET_CURRENCIES = setOf("VES", "MMK", "IRR", "ARS", "LBP", "NGN")

// Il peso cubano ha il tasso della Banca Centrale di Cuba (exchange_rates.py della pipeline), vicino al mercato
// informale ma non uguale: nota propria.
private const val CUBAN_PESO = "CUP"

// Cifre significative del tasso: abbastanza per un conto a mente, senza la falsa precisione del dato.
private const val RATE_SIGNIFICANT_DIGITS = 4

// Sotto questo tasso ("1 ARS ≈ 0,0005772 EUR") si mostra quello inverso, che si legge meglio ("1 EUR ≈ 1.733 ARS").
private const val INVERTED_BELOW = 0.01

/** Codice ISO 4217 della valuta del paese [countryCode] (ISO 3166-1 alpha-2), null se il paese non ne ha una nota. */
internal fun currencyCodeOf(countryCode: String): String? = runCatching {
    Currency.getInstance(Locale.Builder().setRegion(countryCode).build())?.currencyCode
}.getOrNull()

/** Tasso dalla valuta di [homeCountry] a quella di [destinationCountry]; null senza tassi, con la stessa valuta o un tasso troppo vecchio. */
internal fun ExchangeRateTable.rateFor(homeCountry: String?, destinationCountry: String?, today: LocalDate): IndicativeRate? {
    val from = homeCountry?.let(::currencyCodeOf)
    val to = destinationCountry?.let(::currencyCodeOf)
    return if (from == null || to == null) null else indicative(from, to, today)
}

/**
 * "1 EUR ≈ 37,52 THB (Frankfurter, 11 nov 2026), indicativo" / "1 EUR ≈ 37.52 THB (Frankfurter, Nov 11, 2026), indicative",
 * nella lingua della guida ([locale]), al contrario sotto [INVERTED_BELOW], con la nota sul cambio reale per le valute con
 * un mercato parallelo e per il peso cubano; null senza tasso.
 */
internal fun exchangeRateText(rate: IndicativeRate?, locale: Locale): String? {
    rate ?: return null
    val italian = locale.language == Locale.ITALIAN.language
    val shown = if (rate.rate < INVERTED_BELOW) rate.copy(from = rate.to, to = rate.from, rate = 1 / rate.rate) else rate
    val rounded = BigDecimal(shown.rate).round(MathContext(RATE_SIGNIFICANT_DIGITS))
    val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = rounded.scale().coerceAtLeast(0) }.format(rounded)
    val date = rate.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    val text = "1 ${shown.from} ≈ $number ${shown.to} (Frankfurter, $date), " + if (italian) "indicativo" else "indicative"
    val cuba = rate.from == CUBAN_PESO || rate.to == CUBAN_PESO
    val parallel = rate.from in PARALLEL_MARKET_CURRENCIES || rate.to in PARALLEL_MARKET_CURRENCIES
    return when {
        cuba && italian -> "$text; tasso della Banca Centrale di Cuba, può differire dal cambio reale"
        cuba -> "$text; Central Bank of Cuba rate, it can differ from the actual exchange rate"
        !parallel -> text
        italian -> "$text; tasso ufficiale, al cambio reale può differire molto"
        else -> "$text; official rate, the actual exchange rate can differ greatly"
    }
}
