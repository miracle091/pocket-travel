// Un lettore per forma (gradi decimali, geo:, Google Maps, OpenStreetMap) e i loro aiuti: divisi in piu' file si perderebbe
// l'ordine in cui si provano.
@file:Suppress("TooManyFunctions")

package com.pockettravel.feature.map

import java.net.URLDecoder
import java.util.Locale

/**
 * Legge il posto di [text], che viene dal campo di ricerca, da un URI `geo:` o da un testo condiviso da un'altra app: e'
 * un input non fidato, quindi si leggono solo le forme note e i valori fuori intervallo si scartano. Forme: gradi decimali
 * ("43,6 1,44", "43.6, 1.44"), `geo:lat,lon` e `geo:0,0?q=lat,lon(nome)`, link di Google Maps (`@lat,lon`, `!3d..!4d..`,
 * `q`, `query`, `ll`, `destination`, `daddr`) e di OpenStreetMap (`mlat`/`mlon`, `#map=zoom/lat/lon`, `/go/codice`). Un testo con piu'
 * parole cerca il link tra le parole. Gradi, primi e secondi non si leggono.
 */
fun readPlace(text: String): PlaceReading {
    val trimmed = text.trim().take(MAX_TEXT)
    val plain = plainCoordinates(trimmed)
    return when {
        plain != null -> plain
        trimmed.startsWith("geo:", ignoreCase = true) -> readGeo(trimmed)
        else -> {
            val readings = trimmed.split(WHITESPACE).map { readLink(it.trim(*LINK_EDGE)) }
            readings.firstOrNull { it is PlaceReading.Coordinates } ?: readings.firstOrNull { it != PlaceReading.Unreadable } ?: PlaceReading.Unreadable
        }
    }
}

/** "43.60000, 1.44000": il nome di un punto senza nome, col punto decimale in ogni lingua perche' la virgola separa i due numeri. */
internal fun coordinatesText(latitude: Double, longitude: Double): String = String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)

/** Il testo e' un link o un URI `geo:`: la ricerca per nome non ha niente da trovare. */
fun looksLikeLink(text: String): Boolean = "://" in text || text.trimStart().startsWith("geo:", ignoreCase = true)

private const val MAX_TEXT = 2_000
private const val MAX_NAME = 100
private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0
private val WHITESPACE = Regex("\\s+")
private val LINK_EDGE = charArrayOf('(', ')', '<', '>', '"', '\'', '.', ',', ';')
private val CONTROL = Regex("\\p{Cntrl}")

private const val NUMBER = "[+-]?\\d{1,3}(?:[.,]\\d+)?"

// "lat lon", "lat, lon" o "lat;lon", con la virgola o il punto decimale.
private val PLAIN = Regex("($NUMBER)(?:\\s*[,;]\\s*|\\s+)($NUMBER)")
private val NAMED = Regex("(.+?)\\s*\\((.*)\\)")
private val LINK = Regex("https?://([^/?#]+)([^?#]*)(?:\\?([^#]*))?(?:#(.*))?", RegexOption.IGNORE_CASE)
private val GOOGLE_HOST = Regex("(?:[a-z0-9-]+\\.)*google\\.[a-z]{2,3}(?:\\.[a-z]{2})?")
private val SHORT_HOSTS = setOf("goo.gl", "maps.app.goo.gl")
private const val DECIMAL = "-?\\d+(?:\\.\\d+)?"
private val GOOGLE_PLACE_DATA = Regex("!3d($DECIMAL)!4d($DECIMAL)")
private val GOOGLE_VIEW = Regex("/@($DECIMAL),($DECIMAL)")
private val GOOGLE_PLACE_NAME = Regex("/place/([^/@]+)")
private const val OSM_SHORT_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_~"
private const val OSM_SHORT_CHAR_BITS = 6

// 11 caratteri danno 33 bit per coordinata, uno oltre i 32 del formato: il server di OpenStreetMap li legge ancora.
private const val OSM_SHORT_MAX_CHARS = 11
private val OSM_FRAGMENT = Regex("(?:^|&)map=\\d{1,2}(?:\\.\\d+)?/($DECIMAL)/($DECIMAL)")

// null se non sono due numeri o se escono dall'intervallo di latitudine e longitudine.
private fun coordinates(latitude: Double?, longitude: Double?, name: String? = null): PlaceReading.Coordinates? {
    // NaN e infiniti stanno fuori da ogni intervallo.
    val lat = latitude?.takeIf { it in -MAX_LATITUDE..MAX_LATITUDE }
    val lon = longitude?.takeIf { it in -MAX_LONGITUDE..MAX_LONGITUDE }
    return if (lat != null && lon != null) PlaceReading.Coordinates(lat, lon, name) else null
}

private fun coordinates(latitude: String, longitude: String, name: String? = null) = coordinates(latitude.toDouble(), longitude.toDouble(), name)

private fun plainCoordinates(text: String): PlaceReading.Coordinates? {
    val match = PLAIN.matchEntire(text) ?: return null
    return coordinates(match.groupValues[1].replace(',', '.'), match.groupValues[2].replace(',', '.'))
}

// Il valore di un parametro: "lat,lon", "loc:lat,lon" o "lat,lon(nome)".
private fun coordinateValue(value: String): PlaceReading.Coordinates? {
    val named = NAMED.matchEntire(value.trim())
    val point = plainCoordinates((named?.groupValues?.get(1) ?: value).trim().removePrefix("loc:").trim()) ?: return null
    return named?.groupValues?.get(2)?.let { point.copy(name = cleanName(it)) } ?: point
}

// Il nome si scarta se vuoto o se e' a sua volta una coppia di coordinate (Google la usa per i punti senza nome).
private fun cleanName(raw: String): String? =
    raw.replace(CONTROL, " ").trim().take(MAX_NAME).takeIf { it.isNotEmpty() && plainCoordinates(it) == null }

private fun readGeo(uri: String): PlaceReading {
    val body = uri.substring("geo:".length)
    val query = parseQuery(body.substringAfter('?', ""))
    val point = body.substringBefore('?').substringBefore(';').split(',')
    val latitude = point.getOrNull(0)?.trim()?.toDoubleOrNull()
    val longitude = point.getOrNull(1)?.trim()?.toDoubleOrNull()
    val q = query["q"].orEmpty().trim()
    val fromQuery = coordinateValue(q)
    // geo:0,0 con q e' il modo di chiedere "cerca questo"; senza q non indica niente.
    if (!(latitude == 0.0 && longitude == 0.0)) {
        coordinates(latitude, longitude, if (fromQuery != null) fromQuery.name else cleanName(q))?.let { return it }
    }
    return fromQuery ?: if (q.isEmpty()) PlaceReading.Unreadable else PlaceReading.Search(q.replace(CONTROL, " ").take(MAX_TEXT))
}

private fun readLink(token: String): PlaceReading =
    if (token.startsWith("geo:", ignoreCase = true)) readGeo(token) else LINK.matchEntire(token)?.let(::readHttpLink) ?: PlaceReading.Unreadable

private fun readHttpLink(link: MatchResult): PlaceReading {
    val (authority, path, rawQuery) = link.destructured
    val fragment = link.groupValues.last()
    // Senza utente e porta: "https://maps.google.com@altro.it" ha per host altro.it.
    val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
    val query = parseQuery(rawQuery)
    val isOsm = host == "osm.org" || host == "openstreetmap.org" || host.endsWith(".openstreetmap.org")
    return when {
        host in SHORT_HOSTS -> PlaceReading.ShortLink
        isOsm && path.startsWith("/go/") -> readOsmShortLink(path.removePrefix("/go/"))
        isOsm -> readOpenStreetMap(query, fragment)
        GOOGLE_HOST.matches(host) && (path.startsWith("/maps") || host.startsWith("maps.")) -> readGoogleMaps(path, query)
        else -> PlaceReading.Unreadable
    }
}

private fun readOpenStreetMap(query: Map<String, String>, fragment: String): PlaceReading =
    coordinates(query["mlat"]?.toDoubleOrNull(), query["mlon"]?.toDoubleOrNull())
        ?: OSM_FRAGMENT.find(fragment)?.let { coordinates(it.groupValues[1], it.groupValues[2]) }
        ?: PlaceReading.Unreadable

// Il codice di osm.org/go: ogni carattere da' 3 bit della longitudine e 3 della latitudine, alternati e a partire dalla
// longitudine, su 32 bit per coordinata; "-" e "=" in fondo dicono solo lo zoom. Il punto e' l'angolo in basso a sinistra
// della cella, come lo legge il server di OpenStreetMap.
private fun readOsmShortLink(code: String): PlaceReading {
    val values = code.trimEnd('-', '=').replace('@', '~').map(OSM_SHORT_ALPHABET::indexOf)
    if (values.isEmpty() || values.size > OSM_SHORT_MAX_CHARS || values.any { it < 0 }) return PlaceReading.Unreadable
    val bits = values.flatMap { value -> (OSM_SHORT_CHAR_BITS - 1 downTo 0).map { value shr it and 1 } }
    fun axis(first: Int) = bits.filterIndexed { i, _ -> i % 2 == first }.fold(0L) { acc, bit -> acc shl 1 or bit.toLong() }
    val cells = (1L shl bits.size / 2).toDouble()
    return coordinates(axis(1) / cells * 2 * MAX_LATITUDE - MAX_LATITUDE, axis(0) / cells * 2 * MAX_LONGITUDE - MAX_LONGITUDE)
        ?: PlaceReading.Unreadable
}

private fun readGoogleMaps(path: String, query: Map<String, String>): PlaceReading {
    val name = GOOGLE_PLACE_NAME.find(path)?.groupValues?.get(1)?.let { cleanName(decode(it)) }
    // Il punto del posto (!3d!4d) viene prima del centro della vista (@), che puo' essere spostato.
    val inPath = listOf(GOOGLE_PLACE_DATA, GOOGLE_VIEW).firstNotNullOfOrNull { pattern ->
        pattern.find(path)?.let { coordinates(it.groupValues[1], it.groupValues[2], name) }
    }
    return inPath
        ?: listOf("q", "query", "ll", "destination", "daddr").firstNotNullOfOrNull { key -> query[key]?.let(::coordinateValue) }
            ?.let { if (it.name == null) it.copy(name = name) else it }
        ?: PlaceReading.Unreadable
}

private fun parseQuery(raw: String): Map<String, String> = raw.split('&').mapNotNull { pair ->
    val key = pair.substringBefore('=').lowercase()
    if ('=' in pair && key.isNotEmpty()) key to decode(pair.substringAfter('=')) else null
}.toMap()

// Una percentuale malformata lascia il testo com'e': l'input non e' fidato.
private fun decode(value: String): String = try {
    URLDecoder.decode(value, "UTF-8")
} catch (_: IllegalArgumentException) {
    value
}
