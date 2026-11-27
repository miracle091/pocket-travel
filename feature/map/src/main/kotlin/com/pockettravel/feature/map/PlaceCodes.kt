package com.pockettravel.feature.map

// Decodificatori dei codici che portano un punto senza rete: Plus Code, geohash e ge0. Ognuno da' (latitudine, longitudine)
// o null se il codice e' malformato; l'intervallo lo controlla chi li chiama.

private const val PLUS_ALPHABET = "23456789CFGHJMPQRVWX"
private const val PLUS_PREFIX_DIGITS = 8
private const val PLUS_MAX_LENGTH = 16
private const val PLUS_PAIRS = 5
private const val PLUS_PAIR_BASE = 20.0
private const val PLUS_FIRST_SIZE = 20.0
private const val PLUS_GRID_ROWS = 5
private const val PLUS_GRID_COLUMNS = 4
private const val PLUS_MAX_FIRST_LATITUDE_DIGIT = 8
private const val PLUS_MAX_FIRST_LONGITUDE_DIGIT = 17

/**
 * Open Location Code (Plus Code) completo, come "8FHJVHXV+5Q": 8 cifre, "+" e da 2 a 7 cifre. Il punto e' il centro della
 * cella. Algoritmo della specifica pubblica (Apache-2.0). I codici corti (senza le prime 4 cifre) e quelli con lo zero di
 * riempimento non si leggono.
 */
internal fun decodePlusCode(code: String): Pair<Double, Double>? {
    val upper = code.uppercase()
    if (upper.length > PLUS_MAX_LENGTH || upper.indexOf('+') != PLUS_PREFIX_DIGITS) return null
    val values = (upper.substring(0, PLUS_PREFIX_DIGITS) + upper.substring(PLUS_PREFIX_DIGITS + 1)).map(PLUS_ALPHABET::indexOf)
    // 8 + almeno 2 cifre; la prima cifra della latitudine arriva a 90 gradi, quella della longitudine a 180.
    val inRange = values.size >= PLUS_PAIRS * 2 && values.none { it < 0 }
    return if (inRange && values[0] <= PLUS_MAX_FIRST_LATITUDE_DIGIT && values[1] <= PLUS_MAX_FIRST_LONGITUDE_DIGIT) {
        plusCodeCenter(values)
    } else {
        null
    }
}

private fun plusCodeCenter(values: List<Int>): Pair<Double, Double> {
    var latitude = -MAX_LATITUDE
    var longitude = -MAX_LONGITUDE
    var size = PLUS_FIRST_SIZE
    for (pair in 0 until PLUS_PAIRS) {
        latitude += values[2 * pair] * size
        longitude += values[2 * pair + 1] * size
        if (pair < PLUS_PAIRS - 1) size /= PLUS_PAIR_BASE
    }
    var latitudeSize = size
    var longitudeSize = size
    // Dopo le coppie, ogni cifra sceglie una cella di una griglia 5 righe x 4 colonne.
    for (digit in values.drop(PLUS_PAIRS * 2)) {
        latitudeSize /= PLUS_GRID_ROWS
        longitudeSize /= PLUS_GRID_COLUMNS
        latitude += digit / PLUS_GRID_COLUMNS * latitudeSize
        longitude += digit % PLUS_GRID_COLUMNS * longitudeSize
    }
    return (latitude + latitudeSize / 2) to (longitude + longitudeSize / 2)
}

private const val GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"
private const val GEOHASH_CHAR_BITS = 5
private const val GEOHASH_MAX_CHARS = 12

/** Geohash standard (dominio pubblico): bit alternati di longitudine e latitudine, a partire dalla longitudine; il punto e' il centro della cella. */
internal fun decodeGeohash(hash: String): Pair<Double, Double>? {
    val values = hash.lowercase().map(GEOHASH_ALPHABET::indexOf)
    if (values.isEmpty() || values.size > GEOHASH_MAX_CHARS || values.any { it < 0 }) return null
    var latitudeMin = -MAX_LATITUDE
    var latitudeMax = MAX_LATITUDE
    var longitudeMin = -MAX_LONGITUDE
    var longitudeMax = MAX_LONGITUDE
    var isLongitude = true
    for (value in values) {
        for (bit in GEOHASH_CHAR_BITS - 1 downTo 0) {
            val upper = ((value shr bit) and 1) == 1
            if (isLongitude) {
                val middle = (longitudeMin + longitudeMax) / 2
                if (upper) longitudeMin = middle else longitudeMax = middle
            } else {
                val middle = (latitudeMin + latitudeMax) / 2
                if (upper) latitudeMin = middle else latitudeMax = middle
            }
            isLongitude = !isLongitude
        }
    }
    return (latitudeMin + latitudeMax) / 2 to (longitudeMin + longitudeMax) / 2
}

private const val GE0_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
private const val GE0_COORD_BITS = 30
private const val GE0_CHAR_BITS = 6
private const val GE0_BITS_PER_CHAR = 3
private const val GE0_MAX_POINT_CHARS = 9

/**
 * Codice ge0 di Organic Maps, CoMaps e MAPS.ME (`omaps.app/<codice>`, `ge0://<codice>`): un carattere di zoom, che qui non
 * serve, e 9 caratteri del punto. Ogni carattere (base64 con "-" e "_") porta 3 bit di latitudine e 3 di longitudine,
 * alternati a partire dalla latitudine, su 30 bit per coordinata; il punto e' il centro della cella. Il formato e' descritto
 * nel repository di Organic Maps (organicmaps/organicmaps, cartella libs/ge0).
 */
internal fun decodeGe0(code: String): Pair<Double, Double>? {
    val values = code.map(GE0_ALPHABET::indexOf)
    // Il parser di Organic Maps vuole sempre lo zoom e tutti i 9 caratteri del punto.
    if (code.length != GE0_MAX_POINT_CHARS + 1 || values.any { it < 0 }) return null
    var latitude = 0L
    var longitude = 0L
    values.drop(1).forEachIndexed { index, value ->
        val shift = GE0_COORD_BITS - GE0_BITS_PER_CHAR * (index + 1)
        // Dei 6 bit, il piu' alto e' di latitudine, il successivo di longitudine, e cosi' via.
        var latitudeBits = 0
        var longitudeBits = 0
        for (bit in 0 until GE0_BITS_PER_CHAR) {
            latitudeBits = latitudeBits shl 1 or (value shr (GE0_CHAR_BITS - 1 - 2 * bit) and 1)
            longitudeBits = longitudeBits shl 1 or (value shr (GE0_CHAR_BITS - 2 - 2 * bit) and 1)
        }
        latitude = latitude or (latitudeBits.toLong() shl shift)
        longitude = longitude or (longitudeBits.toLong() shl shift)
    }
    val middle = 1L shl (GE0_BITS_PER_CHAR * (GE0_MAX_POINT_CHARS + 1 - (code.length - 1)) - 1)
    val cells = 1L shl GE0_COORD_BITS
    return (latitude + middle).toDouble() / (cells - 1) * 2 * MAX_LATITUDE - MAX_LATITUDE to
        (longitude + middle).toDouble() / cells * 2 * MAX_LONGITUDE - MAX_LONGITUDE
}
