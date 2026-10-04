package com.pockettravel.core.data

import java.text.Normalizer
import java.util.Locale

/** Indirizzo trovato: [number] e' null per una via cercata senza civico (posizionata a meta' della via). */
data class AddressResult(
    val regionId: String,
    val street: String,
    val number: String?,
    val city: String?,
    val latitude: Double,
    val longitude: Double,
) {
    /** "Via Roma 12, Torino"; senza civico o senza citta' le parti mancano. */
    val displayName: String
        get() = listOfNotNull(listOfNotNull(street, number).joinToString(" "), city?.takeIf { it.isNotBlank() }).joinToString(", ")
}

/** Ricerca scritta dall'utente, divisa in via e civico ([number] null se non ne ha). */
data class AddressQuery(val street: String, val number: String?)

/**
 * Chiave di ricerca di una via, la stessa che la pipeline scrive in addresses-search.db (colonna
 * street.key): minuscolo (Locale.ROOT), NFD senza i segni combinanti, ogni gruppo di caratteri che non
 * sono lettere o cifre ridotto a uno spazio, senza spazi ai lati. Se cambia qui, cambia anche li'.
 */
fun streetKey(text: String): String =
    Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .replace(NON_ALPHANUMERIC, " ")
        .trim()

private val COMBINING_MARKS = Regex("\\p{M}+")
// Solo cifre decimali (Nd), come Character.isLetterOrDigit della pipeline: le chiavi devono coincidere.
private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{Nd}]+")

// 12, 12a, 12/3, 12-14, 12a/3b: cifre con al piu' una lettera, ripetibili dopo / o -.
private val HOUSE_NUMBER = Regex("\\d+\\p{L}?(?:[/-]\\d+\\p{L}?)?")

/**
 * Divide "Via Roma 12a" o "12 Rue de Rivoli" in via e civico: il civico e' l'ultima parola (se somiglia a
 * un numero civico, vedi [HOUSE_NUMBER]) o altrimenti la prima. Una sola parola non e' mai un civico
 * ("12" cerca le vie che iniziano per 12), e senza civico si cercano comunque le vie.
 */
fun parseAddressQuery(text: String): AddressQuery {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size >= 2) {
        if (HOUSE_NUMBER.matches(words.last())) return AddressQuery(words.dropLast(1).joinToString(" "), words.last())
        if (HOUSE_NUMBER.matches(words.first())) return AddressQuery(words.drop(1).joinToString(" "), words.first())
    }
    return AddressQuery(words.joinToString(" "), null)
}

/** Una riga di risultato, per non legare la logica ne' ad android.database ne' a JDBC (come TransitRow). */
internal interface AddressRow {
    fun string(column: Int): String?
    fun long(column: Int): Long
}

internal interface AddressQueryRunner {
    fun <T> query(sql: String, args: List<String>, read: (AddressRow) -> T): List<T>
}

/** Schema di addresses-search.db che l'app legge (colonna meta 'format'). */
internal const val ADDRESS_SEARCH_DB_FORMAT = 2

/** Origine in microgradi del database: lat = (latE6 + dlat) / 1e6, come lon. */
internal class AddressOrigin(val latE6: Long, val lonE6: Long)

/** L'origine del database, null se il formato non e' [ADDRESS_SEARCH_DB_FORMAT] o la meta e' incompleta. */
internal fun readAddressOrigin(db: AddressQueryRunner): AddressOrigin? {
    val meta = db.query("SELECT key, value FROM meta WHERE key IN ('format', 'origin_lat_e6', 'origin_lon_e6')", emptyList()) { it.string(0) to it.string(1) }.toMap()
    if (meta["format"]?.toIntOrNull() != ADDRESS_SEARCH_DB_FORMAT) return null
    return AddressOrigin(meta["origin_lat_e6"]?.toLongOrNull() ?: return null, meta["origin_lon_e6"]?.toLongOrNull() ?: return null)
}

// street.key e' fatta solo di lettere, cifre e spazi (streetKey): niente % o _ da proteggere nel LIKE.
// Prima le vie che iniziano per la chiave, con un intervallo sull'indice street_key (un LIKE non lo userebbe: e'
// insensibile alle maiuscole); poi, solo se mancano risultati, quelle in cui una parola inizia cosi' (es. "rivoli"
// per "rue de rivoli"), con una scansione della tabella (una riga per via e citta'). Gli indirizzi si cercano poi per chiave primaria.
private const val STREETS_PREFIX_QUERY =
    "SELECT id, name, city FROM street WHERE key >= ? AND key < ? ORDER BY key, id LIMIT "
private const val STREETS_WORD_QUERY =
    "SELECT id, name, city FROM street WHERE key LIKE ? AND NOT (key >= ? AND key < ?) ORDER BY key, id LIMIT "

// Fine (esclusa) dell'intervallo delle chiavi che iniziano per [key]: chiavi e confronto sono binari su UTF-8.
private fun prefixUpperBound(key: String) = key + '￿'

// Con il civico si guardano piu' vie di [limit]: non tutte hanno quel civico.
private const val MAX_STREET_CANDIDATES = 500

// Testo SQL costante: SQLite riusa la query preparata a ogni battuta (cache delle istruzioni della connessione).
private const val STREET_POINTS_QUERY = "SELECT dlat, dlon FROM address WHERE street_id = ?"

private class FoundStreet(val id: Long, val name: String, val city: String?)

/**
 * Cerca in un addresses-search.db: prima le vie (chiave che inizia per quella cercata o con una parola che
 * inizia cosi'), poi i loro indirizzi con la chiave primaria (street_id, number). Con il civico l'indirizzo
 * esatto (civico senza badare alle maiuscole); senza, una voce per ogni via (e citta'), posizionata
 * sull'indirizzo piu' vicino alla mediana delle coordinate. Al piu' [limit] risultati, uno per via.
 */
internal fun searchAddressDb(db: AddressQueryRunner, origin: AddressOrigin, regionId: String, query: AddressQuery, limit: Int): List<AddressResult> {
    val key = streetKey(query.street)
    if (key.isEmpty()) return emptyList()
    val number = query.number
    val maxStreets = if (number != null) MAX_STREET_CANDIDATES else limit
    val upperBound = prefixUpperBound(key)
    val streets = db.query(STREETS_PREFIX_QUERY + maxStreets, listOf(key, upperBound)) {
        FoundStreet(it.long(0), it.string(1).orEmpty(), it.string(2))
    }.let { byPrefix ->
        if (byPrefix.size >= maxStreets) byPrefix
        else byPrefix + db.query(STREETS_WORD_QUERY + (maxStreets - byPrefix.size), listOf("% $key%", key, upperBound)) {
            FoundStreet(it.long(0), it.string(1).orEmpty(), it.string(2))
        }
    }
    if (streets.isEmpty()) return emptyList()
    if (number != null) {
        // Gli id sono interi letti dal database: niente da proteggere nell'elenco.
        val byStreet = db.query(
            "SELECT street_id, number, dlat, dlon FROM address WHERE street_id IN (${streets.joinToString(",") { it.id.toString() }}) AND number = ? ORDER BY street_id",
            listOf(number),
        ) { Triple(it.long(0), it.string(1), it.long(2) to it.long(3)) }.groupBy { it.first }
        return streets.mapNotNull { street ->
            val (_, foundNumber, delta) = byStreet[street.id]?.first() ?: return@mapNotNull null
            result(origin, regionId, street, foundNumber, delta)
        }.take(limit)
    }
    return streets.mapNotNull { street ->
        val delta = medianPoint(db, street.id) ?: return@mapNotNull null
        result(origin, regionId, street, null, delta)
    }
}

private fun result(origin: AddressOrigin, regionId: String, street: FoundStreet, number: String?, delta: Pair<Long, Long>) =
    AddressResult(regionId, street.name, number, street.city, (origin.latE6 + delta.first) / 1e6, (origin.lonE6 + delta.second) / 1e6)

// Scarti di tutti i civici della via: quello piu' vicino alla mediana di latitudine e longitudine
// (la mediana da sola puo' cadere fuori dalla via se questa e' curva). null per una via senza indirizzi.
private fun medianPoint(db: AddressQueryRunner, streetId: Long): Pair<Long, Long>? {
    val points = db.query(STREET_POINTS_QUERY, listOf(streetId.toString())) { it.long(0) to it.long(1) }
    if (points.isEmpty()) return null
    val medianLat = points.map { it.first }.sorted().let { it[it.size / 2] }
    val medianLon = points.map { it.second }.sorted().let { it[it.size / 2] }
    return points.minBy { (lat, lon) -> (lat - medianLat) * (lat - medianLat) + (lon - medianLon) * (lon - medianLon) }
}
