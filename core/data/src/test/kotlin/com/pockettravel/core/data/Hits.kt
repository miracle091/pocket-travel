package com.pockettravel.core.data

/**
 * Hit di una frase in una colonna per [matchInfoBlob]: occorrenze in questa riga, occorrenze in tutta la tabella,
 * righe della tabella con almeno un'occorrenza.
 */
internal data class Hits(val inRow: Int, val total: Int = inRow, val rowsWithHit: Int = 1)

/**
 * Blob matchinfo(..., 'pcxnal') finto, come lo restituisce SQLite: interi a 32 bit little-endian, [phrases] x
 * [columns] [Hits] (frase per frase, colonne title e body), poi righe della tabella, lunghezza media e lunghezza
 * della riga per colonna — lo stesso formato letto da FtsMatchInfo.parse (FtsRanking.kt).
 */
internal fun matchInfoBlob(
    phrases: Int,
    columns: Int = 2,
    hits: List<Hits> = emptyList(),
    rowCount: Int = 10,
    averageLength: List<Int> = List(columns) { 10 },
    length: List<Int> = averageLength,
): ByteArray {
    val ints = mutableListOf(phrases, columns)
    hits.forEach { ints += listOf(it.inRow, it.total, it.rowsWithHit) }
    ints += rowCount
    ints += averageLength
    ints += length
    val bytes = ByteArray(ints.size * 4)
    ints.forEachIndexed { i, value ->
        bytes[i * 4] = (value and 0xFF).toByte()
        bytes[i * 4 + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = ((value shr 24) and 0xFF).toByte()
    }
    return bytes
}

internal val NO_MATCH_INFO: ByteArray = matchInfoBlob(phrases = 0)
