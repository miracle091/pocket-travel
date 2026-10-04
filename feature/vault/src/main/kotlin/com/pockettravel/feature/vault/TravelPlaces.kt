package com.pockettravel.feature.vault

import java.text.Normalizer

// Aeroporti e compagnie aeree dei suggerimenti del biglietto: logica pura, senza Android.
// I dati stanno in assets/airports.tsv.gz e assets/airlines.tsv.gz (generati da
// tools/data-pipeline/scripts/vault_travel_data.py).

private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{Nd}]+")
private val DIACRITICS = Regex("\\p{Mn}+")

/** Minuscolo e senza accenti: "Zürich" e "zurich" danno lo stesso testo. */
internal fun normalize(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase()

private fun tokenize(text: String): List<String> =
    normalize(text).split(NON_ALPHANUMERIC).filter { it.isNotEmpty() }

/** [detail] e' mostrato sotto il nome; [value] e' il testo che finisce nel campo del documento quando la voce e' scelta. */
internal class TravelText(val detail: String, val value: String)

/**
 * Una voce suggerita. [code] e [name] sono mostrati sulla prima riga; [altCode] si cerca ma non si mostra.
 * [english] e [italian] sono i testi nelle due lingue dell'app (uguali se la voce non ha un nome italiano);
 * la ricerca vale per entrambi.
 */
internal class TravelEntry(
    val code: String,
    val altCode: String,
    val name: String,
    val english: TravelText,
    val italian: TravelText = english,
) {
    val codes: List<String> = listOf(code, altCode).filter { it.isNotEmpty() }.map(::normalize)
    val words: List<String> = tokenize("$name ${english.detail} ${italian.detail}")
    val nameKey: String = tokenize(name).joinToString(" ")

    fun text(italian: Boolean): TravelText = if (italian) this.italian else english
}

/** Testi di un aeroporto: "Citta', PAESE" sotto il nome e "IATA citta'" nel campo (il nome se manca la citta'). */
private fun airportText(iata: String, name: String, city: String, country: String) = TravelText(
    detail = listOf(city, country).filter { it.isNotEmpty() }.joinToString(", "),
    value = "$iata ${city.ifEmpty { name }}",
)

// Colonne dei TSV: aeroporti iata, icao, nome, comune, paese, citta' in italiano; compagnie iata, icao, nome.
private const val AIRPORT_COLUMNS = 5
private const val AIRPORT_CITY_IT = 5
private const val AIRLINE_COLUMNS = 3

// La sesta colonna (citta' in italiano, al massimo due separate da " / ") puo' mancare o essere vuota.
private fun airportEntry(fields: List<String>): TravelEntry? {
    if (fields.size < AIRPORT_COLUMNS) return null
    val (iata, icao, name) = fields
    val country = fields[AIRPORT_COLUMNS - 1]
    val english = airportText(iata, name, fields[AIRPORT_COLUMNS - 2], country)
    val cityIt = fields.getOrElse(AIRPORT_CITY_IT) { "" }
    return TravelEntry(
        code = iata,
        altCode = icao,
        name = name,
        english = english,
        italian = if (cityIt.isEmpty()) {
            english
        } else {
            TravelText(
                detail = airportText(iata, name, cityIt, country).detail,
                value = airportText(iata, name, cityIt.substringBefore(" / "), country).value,
            )
        },
    )
}

private fun airlineEntry(fields: List<String>): TravelEntry? {
    if (fields.size < AIRLINE_COLUMNS) return null
    val (iata, icao, name) = fields
    return TravelEntry(
        code = iata.ifEmpty { icao },
        altCode = icao,
        name = name,
        english = TravelText(detail = if (iata.isEmpty()) "" else icao, value = name),
    )
}

private fun parseTsv(lines: List<String>, build: (List<String>) -> TravelEntry?): List<TravelEntry> =
    lines.mapNotNull { if (it.isBlank()) null else build(it.split('\t')) }

/** Righe di airports.tsv.gz: iata, icao, nome, comune, paese, citta' in italiano (facoltativa). */
internal fun parseAirports(lines: List<String>): List<TravelEntry> = parseTsv(lines, ::airportEntry)

/** Righe di airlines.tsv.gz: iata, icao, nome. */
internal fun parseAirlines(lines: List<String>): List<TravelEntry> = parseTsv(lines, ::airlineEntry)

internal const val MAX_SUGGESTIONS = 6
private const val RANK_CODE = 0
private const val RANK_CODE_PREFIX = 1
private const val RANK_NAME_PREFIX = 2
private const val RANK_WORD = 3

/** Ricerca in memoria: ogni parola scritta deve iniziare un codice o una parola di nome, comune o paese. */
internal class SuggestionIndex(private val entries: List<TravelEntry>) {

    /** Al massimo [limit] voci; a parita' di rilevanza resta l'ordine dei dati (aeroporti maggiori per primi). */
    fun search(query: String, limit: Int = MAX_SUGGESTIONS): List<TravelEntry> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        return entries
            .mapNotNull { entry -> rank(entry, tokens)?.let { it to entry } }
            .sortedBy { it.first }
            .take(limit)
            .map { it.second }
    }

    // Rilevanza crescente con il numero: codice uguale, codice che inizia cosi', nome che inizia cosi', altra
    // parola; null = non corrisponde.
    private fun rank(entry: TravelEntry, tokens: List<String>): Int? {
        val matches = tokens.all { t -> entry.codes.any { it.startsWith(t) } || entry.words.any { it.startsWith(t) } }
        if (!matches) return null
        val single = tokens.singleOrNull()
        return when {
            single != null && single in entry.codes -> RANK_CODE
            single != null && entry.codes.any { it.startsWith(single) } -> RANK_CODE_PREFIX
            entry.nameKey.startsWith(tokens.joinToString(" ")) -> RANK_NAME_PREFIX
            else -> RANK_WORD
        }
    }
}

internal const val ROUTE_SEPARATOR = " – "
private const val LEGACY_SEPARATOR = " - "

/**
 * Divide la tratta salvata in partenza e arrivo. Un testo libero senza separatore (anche gia' salvato)
 * resta intero nella partenza.
 */
internal fun splitRoute(text: String): Pair<String, String> {
    val separator = listOf(ROUTE_SEPARATOR, LEGACY_SEPARATOR).firstOrNull { it in text } ?: return text to ""
    val at = text.indexOf(separator)
    return text.substring(0, at) to text.substring(at + separator.length)
}

/** Inverso di [splitRoute]: senza arrivo resta solo la partenza, come nei testi liberi di prima. */
internal fun joinRoute(from: String, to: String): String =
    if (to.isBlank()) from else "$from$ROUTE_SEPARATOR$to"
