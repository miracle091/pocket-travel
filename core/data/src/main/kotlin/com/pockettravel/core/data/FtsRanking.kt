package com.pockettravel.core.data

import kotlin.math.ln

// Colonne di guide_sections_fts e city_sections_fts, nello stesso ordine dichiarato in GuideSectionFts e
// CitySectionFts: title poi body.
private const val TITLE_COLUMN = 0
private const val TITLE_WEIGHT = 3.0
private const val BODY_WEIGHT = 1.0

// Parametri standard di BM25: saturazione della frequenza e peso della lunghezza della sezione.
private const val BM25_K1 = 1.2
private const val BM25_B = 0.75
// Correzione di BM25 nell'idf: una parola presente in quasi tutte le righe vale poco ma non zero.
private const val BM25_IDF_SMOOTHING = 0.5

// Disposizione del blob: 'p' e 'c' in testa, poi per ogni coppia frase/colonna la tripla di 'x' (occorrenze nella
// riga, occorrenze totali, righe con la frase).
private const val HEADER_INTS = 2
private const val X_INTS_PER_PAIR = 3
private const val X_ROWS_WITH_HIT = 2

/**
 * matchinfo(<tabella fts>, 'pcxnal') letto: interi a 32 bit little-endian (nativo su Android). 'p' = numero di frasi
 * della query FTS, 'c' = numero di colonne (title, body), 'x' = per ogni coppia frase/colonna (occorrenze in questa
 * riga, occorrenze totali, righe con almeno un'occorrenza), 'n' = righe della tabella, 'a' = lunghezza media in token
 * per colonna, 'l' = lunghezza in token di questa riga per colonna. 'x', 'n' e 'a' valgono per tutta la tabella,
 * non solo per le righe della regione.
 */
class FtsMatchInfo(
    val phraseCount: Int,
    val columnCount: Int,
    private val hits: IntArray,
    private val docsWithHit: IntArray,
    val rowCount: Int,
    val averageLength: IntArray,
    val length: IntArray,
) {
    fun hitsInRow(phrase: Int, column: Int): Int = hits[phrase * columnCount + column]
    fun rowsWithHit(phrase: Int, column: Int): Int = docsWithHit[phrase * columnCount + column]

    companion object {
        fun parse(blob: ByteArray): FtsMatchInfo {
            val ints = IntArray(blob.size / 4) { i -> readLittleEndianInt(blob, i * 4) }
            val phrases = ints.getOrElse(0) { 0 }
            val columns = ints.getOrElse(1) { 0 }
            val pairs = phrases * columns
            val tail = HEADER_INTS + pairs * X_INTS_PER_PAIR
            return FtsMatchInfo(
                phraseCount = phrases,
                columnCount = columns,
                hits = IntArray(pairs) { ints[HEADER_INTS + it * X_INTS_PER_PAIR] },
                docsWithHit = IntArray(pairs) { ints[HEADER_INTS + it * X_INTS_PER_PAIR + X_ROWS_WITH_HIT] },
                rowCount = ints.getOrElse(tail) { 0 },
                averageLength = IntArray(columns) { ints.getOrElse(tail + 1 + it) { 0 } },
                length = IntArray(columns) { ints.getOrElse(tail + 1 + columns + it) { 0 } },
            )
        }
    }
}

/**
 * Statistiche di piu' tabelle FTS come se fossero una sola (guide del paese + guide delle citta'): righe, lunghezza
 * media per colonna e righe con ogni frase per colonna, sommate. Cosi' i punteggi delle due tabelle si possono
 * confrontare: con le statistiche di ognuna, una parola qualunque pesa molto di piu' nelle poche sezioni del paese
 * che nelle centinaia delle citta'. [tables]: un matchinfo qualunque per tabella (le statistiche sono di tabella).
 */
class FtsCorpusStats private constructor(
    private val rowCount: Int,
    private val averageLength: DoubleArray,
    private val docsWithHit: Array<IntArray>,
) {
    internal fun idf(phrase: Int, column: Int): Double {
        val docs = docsWithHit[phrase][column].coerceAtLeast(1)
        return ln(1 + (rowCount - docs + BM25_IDF_SMOOTHING) / (docs + BM25_IDF_SMOOTHING))
    }

    internal fun averageLength(column: Int): Double = averageLength[column]

    companion object {
        fun of(tables: List<FtsMatchInfo>): FtsCorpusStats {
            val first = tables.first()
            val rows = tables.sumOf { it.rowCount }
            return FtsCorpusStats(
                rowCount = rows,
                averageLength = DoubleArray(first.columnCount) { col ->
                    tables.sumOf { it.rowCount.toDouble() * it.averageLength[col] } / rows.coerceAtLeast(1)
                },
                docsWithHit = Array(first.phraseCount) { ph ->
                    IntArray(first.columnCount) { col -> tables.sumOf { it.rowsWithHit(ph, col) } }
                },
            )
        }
    }
}

/**
 * Punteggio BM25 di una riga con le statistiche [stats] (FtsCorpusStats.of): frequenza saturata e normalizzata per
 * lunghezza (una Storia di 4.000 caratteri non vince solo perche' ripete piu' volte le parole), idf sulle righe che
 * contengono la parola, titolo che pesa piu' del corpo. FTS4 non ha bm25() come FTS5. Replicato in
 * tools/data-pipeline/scripts/eval_retrieval.py, che misura la ricerca su dati pubblicati: va cambiato insieme.
 */
fun bm25Score(info: FtsMatchInfo, stats: FtsCorpusStats): Double {
    var score = 0.0
    for (phrase in 0 until info.phraseCount) {
        for (column in 0 until info.columnCount) {
            val tf = info.hitsInRow(phrase, column)
            if (tf == 0) continue
            val norm = 1 - BM25_B + BM25_B * info.length[column] / stats.averageLength(column).coerceAtLeast(1.0)
            val columnWeight = if (column == TITLE_COLUMN) TITLE_WEIGHT else BODY_WEIGHT
            score += columnWeight * stats.idf(phrase, column) * tf * (BM25_K1 + 1) / (tf + BM25_K1 * norm)
        }
    }
    return score
}

private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
