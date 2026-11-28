// Un lettore per forma (gradi, geo:, Plus Code, link di mappe) e i loro aiuti: divisi in piu' file si perderebbe
// l'ordine in cui si provano.
@file:Suppress("TooManyFunctions")

package com.pockettravel.feature.map

import java.net.URLDecoder
import java.util.Locale

/**
 * Legge il posto di [text], che viene dal campo di ricerca, da un URI `geo:` o da un testo condiviso da un'altra app: e'
 * un input non fidato, quindi si leggono solo le forme note e i valori fuori intervallo si scartano. Forme: gradi decimali
 * ("43,6 1,44", "43.6, 1.44"), gradi con primi e secondi (`41°53'24.8"N 12°29'32.3"E`) o con primi decimali
 * (`N 41° 53.413' E 12° 29.538'`), `geo:lat,lon` e `geo:0,0?q=lat,lon(nome)`, Plus Code completi (`8FHJVHXV+5Q`, anche in
 * `plus.codes/`), link di Google Maps (`@lat,lon`, `!3d..!4d..`, `q`, `query`, `ll`, `destination`, `daddr`, ultimo punto di
 * `/dir/`), di OpenStreetMap (`mlat`/`mlon`, `#map=zoom/lat/lon`, `/go/codice`), di Apple Maps, Waze (anche `/ul/h<geohash>`),
 * Organic Maps (`ge0`), OsmAnd, HERE, Mapy e Magic Earth. Un percorso con piu' punti da' l'ultimo, la destinazione. Un testo
 * con piu' parole cerca il link tra le parole.
 */
fun readPlace(text: String): PlaceReading {
    val trimmed = text.trim().take(MAX_TEXT)
    val plain = plainCoordinates(trimmed) ?: degreesCoordinates(trimmed)
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
internal const val MAX_LATITUDE = 90.0
internal const val MAX_LONGITUDE = 180.0
private val WHITESPACE = Regex("\\s+")
private val LINK_EDGE = charArrayOf('(', ')', '<', '>', '"', '\'', '.', ',', ';')
private val CONTROL = Regex("\\p{Cntrl}")

private val UNICODE_SPACE = Regex("\\p{Zs}")
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
private const val GOOGLE_DIRECTIONS = "/maps/dir/"

// Gradi, primi e (facoltativi) secondi, con la lettera dell'emisfero prima o dopo i numeri: "41°53'24.8"N" o "N 41° 53.413'".
private const val DEGREES_VALUE = "(\\d{1,3})\\s*[°º]\\s*(\\d{1,2}(?:\\.\\d+)?)\\s*['′’]\\s*(?:(\\d{1,2}(?:\\.\\d+)?)\\s*(?:\"|″|”|''|′′|’’))?"
private const val HEMISPHERE = "([NSEWnsew])"
private const val PAIR_SEPARATOR = "(?:\\s*[,;]\\s*|\\s+)"
private val DEGREES_AFTER = Regex("$DEGREES_VALUE\\s*$HEMISPHERE$PAIR_SEPARATOR$DEGREES_VALUE\\s*$HEMISPHERE")
private val DEGREES_BEFORE = Regex("$HEMISPHERE\\s*$DEGREES_VALUE$PAIR_SEPARATOR$HEMISPHERE\\s*$DEGREES_VALUE")
private const val MINUTES_PER_DEGREE = 60.0
private const val SECONDS_PER_DEGREE = 3_600.0
private const val MAX_MINUTES = 60.0
private const val GROUPS_PER_DEGREES = 4

// Plus Code completo; senza ancore, perche' serve sia da solo sia all'inizio di un pezzo di percorso.
private val PLUS_CODE = Regex("[2-9CFGHJMPQRVWX]{8}\\+[2-9CFGHJMPQRVWX]{2,7}", RegexOption.IGNORE_CASE)
private val GE0_HOSTS = setOf("omaps.app", "ge0.me", "comaps.at")
private val MAPY_HOST = Regex("(?:[a-z0-9-]+\\.)*mapy\\.(?:cz|com)")
private val WAZE_GEOHASH = Regex("/ul/h([0-9a-z]+)/?", RegexOption.IGNORE_CASE)
private val HERE_PLACE = Regex("/l/($DECIMAL),($DECIMAL)(?:[,/].*)?")
private val HERE_MAP = Regex("^($DECIMAL),($DECIMAL)")
private val OSMAND_FRAGMENT = Regex("^\\d{1,2}(?:\\.\\d+)?/($DECIMAL)/($DECIMAL)")
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

private fun readLink(token: String): PlaceReading = when {
    token.startsWith("geo:", ignoreCase = true) -> readGeo(token)
    token.startsWith("ge0://", ignoreCase = true) -> readGe0(token.substring("ge0://".length))
    PLUS_CODE.matches(token) -> readPlusCode(token)
    else -> LINK.matchEntire(token)?.let(::readHttpLink) ?: PlaceReading.Unreadable
}

// "41°53'24.8"N 12°29'32.3"E" o "N 41° 53.413' E 12° 29.538'": la latitudine puo' stare prima o dopo la longitudine.
private fun degreesCoordinates(text: String): PlaceReading.Coordinates? {
    val spaced = text.replace(UNICODE_SPACE, " ")
    val after = DEGREES_AFTER.matchEntire(spaced)?.groupValues?.drop(1)?.chunked(GROUPS_PER_DEGREES)
    val before = DEGREES_BEFORE.matchEntire(spaced)?.groupValues?.drop(1)?.chunked(GROUPS_PER_DEGREES)
    // Quattro gruppi per coordinata: gradi, primi, secondi ed emisfero, che in DEGREES_BEFORE sta in testa.
    val (first, second) = when {
        after != null -> after.map { degreesValue(it.dropLast(1), it.last()) }
        before != null -> before.map { degreesValue(it.drop(1), it.first()) }
        else -> return null
    }
    return when {
        first == null || second == null -> null
        first.second in "NS" && second.second in "EW" -> coordinates(first.first, second.first)
        first.second in "EW" && second.second in "NS" -> coordinates(second.first, first.first)
        else -> null
    }
}

// Gradi, primi e secondi (vuoti se mancano) in [numbers], lettera in [hemisphere]: (valore con segno, lettera maiuscola).
private fun degreesValue(numbers: List<String>, hemisphere: String): Pair<Double, Char>? {
    val (degrees, minutes, seconds) = numbers.map { it.ifEmpty { "0" }.toDouble() }
    val letter = hemisphere.uppercase().single()
    val value = degrees + minutes / MINUTES_PER_DEGREE + seconds / SECONDS_PER_DEGREE
    val isSigned = letter == 'S' || letter == 'W'
    return if (minutes >= MAX_MINUTES || seconds >= MAX_MINUTES) null else (if (isSigned) -value else value) to letter
}

private fun readHttpLink(link: MatchResult): PlaceReading {
    val (authority, path, rawQuery) = link.destructured
    val fragment = link.groupValues.last()
    // Senza utente e porta: "https://maps.google.com@altro.it" ha per host altro.it.
    val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
    val query = parseQuery(rawQuery)
    val isOsm = host == "osm.org" || host == "openstreetmap.org" || host.endsWith(".openstreetmap.org")
    val bareHost = host.removePrefix("www.")
    return when {
        host in SHORT_HOSTS -> PlaceReading.ShortLink
        isOsm && path.startsWith("/go/") -> readOsmShortLink(path.removePrefix("/go/"))
        isOsm -> readOpenStreetMap(query, fragment)
        GOOGLE_HOST.matches(host) && (path.startsWith("/maps") || host.startsWith("maps.")) -> readGoogleMaps(path, query)
        else -> readOtherApp(bareHost, path, query, fragment)
    }
}

// I servizi che non sono Google ne' OpenStreetMap; senza rete si legge solo il punto che il link porta.
private fun readOtherApp(host: String, path: String, query: Map<String, String>, fragment: String): PlaceReading = when {
    host == "plus.codes" -> readPlusCode(path.removePrefix("/"))
    host == "maps.apple.com" || host == "maps.apple" -> readApple(path, query)
    host == "waze.com" || host.endsWith(".waze.com") -> readWaze(path, query)
    host in GE0_HOSTS -> readGe0(path.removePrefix("/"))
    host == "osmand.net" -> if ("/navigate" in path) PlaceReading.Unreadable else readOsmAnd(query, fragment)
    host == "share.here.com" || host == "wego.here.com" -> readHere(path, query)
    MAPY_HOST.matches(host) -> readMapy(path, query)
    host == "magicearth.com" -> readMagicEarth(query)
    else -> PlaceReading.Unreadable
}

private fun latitudeLongitude(query: Map<String, String>, latitudeKey: String = "lat", longitudeKey: String = "lon") =
    coordinates(query[latitudeKey]?.toDoubleOrNull(), query[longitudeKey]?.toDoubleOrNull())

// Un Plus Code all'inizio di [raw], anche con "+" scritto %2B ("8FHJVHXV%2B5Q+Roma"); i codici corti non si leggono.
private fun readPlusCode(raw: String): PlaceReading {
    val code = PLUS_CODE.find(raw.replace("%2B", "+", ignoreCase = true))?.takeIf { it.range.first == 0 }?.value
    val point = code?.let(::decodePlusCode)
    return point?.let { coordinates(it.first, it.second) } ?: PlaceReading.Unreadable
}

private fun readApple(path: String, query: Map<String, String>): PlaceReading {
    if (path.startsWith("/p/")) return PlaceReading.ShortLink
    // Con una destinazione il posto e' quella: ll e sll sono la vista o la partenza.
    val destination = query["daddr"] ?: query["destination"]
    return if (destination != null) {
        coordinateValue(destination) ?: PlaceReading.Unreadable
    } else {
        // sll e' dove cercare il testo di q, non il posto: vale solo senza q.
        val keys = if ("q" in query) listOf("ll", "coordinate", "q") else listOf("ll", "coordinate", "sll")
        keys.firstNotNullOfOrNull { key -> query[key]?.let(::coordinateValue) } ?: PlaceReading.Unreadable
    }
}

private fun readWaze(path: String, query: Map<String, String>): PlaceReading {
    val geohash = WAZE_GEOHASH.matchEntire(path)?.groupValues?.get(1)?.let(::decodeGeohash)
    // Con q il testo e' la destinazione e ll e' la vista: vale solo senza q.
    return geohash?.let { coordinates(it.first, it.second) }
        ?: query["q"]?.let(::searchByName)
        ?: query["ll"]?.let(::coordinateValue)
        ?: PlaceReading.Unreadable
}

// Il testo di una ricerca del link: coordinate se lo sono, altrimenti la ricerca per nome; null se vuoto.
private fun searchByName(text: String): PlaceReading? =
    coordinateValue(text) ?: text.replace(CONTROL, " ").trim().take(MAX_TEXT).takeIf { it.isNotEmpty() }?.let(PlaceReading::Search)

// Il nome sta dopo il codice: "_" e' uno spazio e uno spazio codificato e' un trattino basso.
private fun readGe0(rest: String): PlaceReading {
    val point = decodeGe0(rest.substringBefore('/').substringBefore('?').substringBefore('#'))
    val name = decode(rest.substringAfter('/', "").substringBefore('?').substringBefore('#'))
        .map {
            when (it) {
                '_' -> ' '
                ' ' -> '_'
                else -> it
            }
        }.joinToString("")
    return point?.let { coordinates(it.first, it.second, cleanName(name)) } ?: PlaceReading.Unreadable
}

private fun readOsmAnd(query: Map<String, String>, fragment: String): PlaceReading =
    latitudeLongitude(query)
        ?: query["pin"]?.let(::coordinateValue)
        ?: OSMAND_FRAGMENT.find(fragment)?.let { coordinates(it.groupValues[1], it.groupValues[2]) }
        ?: PlaceReading.Unreadable

private fun readHere(path: String, query: Map<String, String>): PlaceReading = when {
    path.startsWith("/p/") -> PlaceReading.ShortLink
    // Un percorso: map= e' la vista, non l'arrivo.
    path.startsWith("/directions") -> PlaceReading.Unreadable
    // Una ricerca (/search/nome): map= e' la vista, la destinazione e' il nome.
    path.startsWith("/search/") || path.startsWith("/discover/") ->
        searchByName(decode(path.substringAfter('/', "").substringAfter('/').substringBefore('/'))) ?: PlaceReading.Unreadable
    else -> (HERE_PLACE.matchEntire(path) ?: query["map"]?.let { HERE_MAP.find(it) })
        ?.let { coordinates(it.groupValues[1], it.groupValues[2]) } ?: PlaceReading.Unreadable
}

// x e y sono la vista, `source=coor&id=lon,lat` e' il punto scelto; l'hash `rc` dei percorsi non si legge, e di un
// percorso la vista non e' l'arrivo.
private fun readMapy(path: String, query: Map<String, String>): PlaceReading = when {
    path.startsWith("/s/") -> PlaceReading.ShortLink
    "rc" in query -> PlaceReading.Unreadable
    else -> query["id"]?.split(',')?.takeIf { query["source"] == "coor" && it.size == 2 }
        ?.let { coordinates(it[1].toDoubleOrNull(), it[0].toDoubleOrNull()) }
        ?: query["q"]?.let(::searchByName)
        ?: latitudeLongitude(query, "y", "x")
        ?: PlaceReading.Unreadable
}

private fun readMagicEarth(query: Map<String, String>): PlaceReading =
    latitudeLongitude(query)?.copy(name = query["name"]?.let(::cleanName)) ?: PlaceReading.Unreadable

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
    // Un percorso (/dir/A/B/C/@vista/...) vale per la sua destinazione, l'ultimo punto prima della vista. Se l'arrivo
    // non ha coordinate il link non si legge: il centro della vista sta di solito a meta' strada.
    val stops = path.takeIf { it.startsWith(GOOGLE_DIRECTIONS) }?.removePrefix(GOOGLE_DIRECTIONS)?.split('/')
        ?.takeWhile { !it.startsWith("@") && !it.startsWith("data=") }?.filter { it.isNotEmpty() }.orEmpty()
    if (stops.isNotEmpty()) return coordinateValue(decode(stops.last())) ?: PlaceReading.Unreadable
    val placeName = GOOGLE_PLACE_NAME.find(path)?.groupValues?.get(1)
    val name = placeName?.let { cleanName(decode(it)) }
    // Il punto del posto (!3d!4d) e il Plus Code vengono prima del centro della vista (@), che puo' essere spostato.
    val inPath = GOOGLE_PLACE_DATA.find(path)?.let { coordinates(it.groupValues[1], it.groupValues[2], name) }
        ?: placeName?.let(::readPlusCode) as? PlaceReading.Coordinates
        ?: GOOGLE_VIEW.find(path)?.let { coordinates(it.groupValues[1], it.groupValues[2], name) }
    // destination e daddr prima di ll: nei link vecchi ll e' il centro della mappa.
    return inPath
        ?: listOf("q", "query", "destination", "daddr", "ll").firstNotNullOfOrNull { key -> query[key]?.let(::coordinateValue) }
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
