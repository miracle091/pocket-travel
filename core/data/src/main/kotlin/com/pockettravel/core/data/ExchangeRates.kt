package com.pockettravel.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Tasso di una valuta contro l'euro (1 EUR = [rate]) e il giorno a cui si riferisce. */
data class EuroRate(val rate: Double, val date: LocalDate)

/** Tassi di exchange-rates.json, per codice ISO 4217 della valuta; l'euro non c'e' (vale 1). */
data class ExchangeRateTable(val rates: Map<String, EuroRate>)

/** Tasso indicativo: 1 [from] = [rate] [to], del giorno [date] (il piu' vecchio dei due tassi usati). */
data class IndicativeRate(val from: String, val to: String, val rate: Double, val date: LocalDate)

/**
 * I tassi di cambio pubblicati dal progetto (exchange-rates.json, firmato, dai tassi di Frankfurter v2).
 * Implementazione: core:sync HttpExchangeRates.
 */
interface ExchangeRates {
    /** Il file del sito se c'e' rete, altrimenti l'ultima copia verificata; null se non e' mai stato scaricato. */
    suspend fun latest(): ExchangeRateTable?
}

/** Oltre questi giorni il tasso non si mostra: un tasso vecchio sembrerebbe quello del giorno. */
const val EXCHANGE_RATE_MAX_AGE_DAYS = 14L

private const val EURO = "EUR"
private val CURRENCY_CODE = Regex("""[A-Z]{3}""")
private val exchangeRatesJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class ExchangeRatesFile(val base: String, val rates: Map<String, RateEntry>)

@Serializable
private data class RateEntry(val rate: Double, val date: String)

/** Legge e valida exchange-rates.json; IllegalArgumentException se non e' valido. I campi in piu' (`publishedAt`) sono ignorati. */
fun parseExchangeRates(bytes: ByteArray): ExchangeRateTable {
    val file = exchangeRatesJson.decodeFromString(ExchangeRatesFile.serializer(), bytes.decodeToString())
    require(file.base == EURO) { "base diversa da EUR: ${file.base}" }
    return ExchangeRateTable(
        file.rates.mapValues { (code, entry) ->
            require(CURRENCY_CODE.matches(code) && code != EURO) { "codice non valido: $code" }
            require(entry.rate.isFinite() && entry.rate > 0) { "$code: tasso non valido" }
            val date = try {
                LocalDate.parse(entry.date)
            } catch (error: DateTimeParseException) {
                throw IllegalArgumentException("$code: data non valida", error)
            }
            EuroRate(entry.rate, date)
        },
    )
}

/**
 * Tasso da [from] a [to] calcolato dai due tassi contro l'euro; null se le valute coincidono, se una manca o se il
 * tasso ha piu' di [EXCHANGE_RATE_MAX_AGE_DAYS] giorni a [today].
 */
fun ExchangeRateTable.indicative(from: String, to: String, today: LocalDate): IndicativeRate? {
    val fromRate = euroRate(from)
    val toRate = euroRate(to)
    if (from == to || fromRate == null || toRate == null) return null
    // Con due valute diverse almeno una non e' l'euro, quindi ha una data.
    val date = listOfNotNull(fromRate.second, toRate.second).min()
    return IndicativeRate(from, to, toRate.first / fromRate.first, date).takeIf { !date.plusDays(EXCHANGE_RATE_MAX_AGE_DAYS).isBefore(today) }
}

// L'euro vale 1 e non ha una data sua.
private fun ExchangeRateTable.euroRate(code: String): Pair<Double, LocalDate?>? =
    if (code == EURO) 1.0 to null else rates[code]?.let { it.rate to it.date }
