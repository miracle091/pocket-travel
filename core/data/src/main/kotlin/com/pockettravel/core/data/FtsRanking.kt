package com.pockettravel.core.data

// Colonne di guide_sections_fts e city_sections_fts, nello stesso ordine dichiarato in GuideSectionFts e
// CitySectionFts: title poi body.
private const val TITLE_COLUMN = 0
private const val TITLE_WEIGHT = 3.0
private const val BODY_WEIGHT = 1.0

/**
 * Punteggio di rilevanza da matchinfo(<tabella fts>, 'pcx'), condiviso da GuideRepository e CityRepository:
 * interi a 32 bit little-endian (nativo su Android). 'p' = numero di frasi della query FTS, 'c' = numero
 * di colonne (title, body), poi per ogni coppia frase/colonna una tripla (occorrenze in questa riga,
 * occorrenze totali, righe con almeno un'occorrenza). Il titolo pesa piu' del corpo, e i termini presenti
 * in quasi tutte le sezioni contano poco (idf-like: 1/righe-con-match) — cosi' un token generico come il
 * nome della regione (es. "marino" per San Marino, presente in ogni sezione) non decide piu' da
 * solo quale sezione vince.
 */
internal fun matchScore(matchinfo: ByteArray): Double {
    val ints = IntArray(matchinfo.size / 4) { i -> readLittleEndianInt(matchinfo, i * 4) }
    val phraseCount = ints[0]
    val columnCount = ints[1]
    var score = 0.0
    for (phrase in 0 until phraseCount) {
        for (column in 0 until columnCount) {
            val base = 2 + (phrase * columnCount + column) * 3
            val hitsInRow = ints[base]
            val docsWithHit = ints[base + 2]
            val columnWeight = if (column == TITLE_COLUMN) TITLE_WEIGHT else BODY_WEIGHT
            score += columnWeight * hitsInRow / docsWithHit.coerceAtLeast(1)
        }
    }
    return score
}

private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
