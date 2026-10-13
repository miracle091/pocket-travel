package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Casi limite del parser di matchinfo e del punteggio BM25: blob anomali, statistiche del corpus, pesi delle colonne. */
class FtsRankingEdgeCasesTest {

    private fun score(blob: ByteArray): Double {
        val info = FtsMatchInfo.parse(blob)
        return bm25Score(info, FtsCorpusStats.of(listOf(info)))
    }

    @Test
    fun `un blob vuoto si legge come nessuna frase e nessuna colonna`() {
        val info = FtsMatchInfo.parse(ByteArray(0))

        assertEquals(0, info.phraseCount)
        assertEquals(0, info.columnCount)
        assertEquals(0, info.rowCount)
        assertEquals(0, info.averageLength.size)
        assertEquals(0, info.length.size)
    }

    @Test
    fun `i byte in coda che non fanno un intero si ignorano`() {
        val blob = matchInfoBlob(phrases = 1, hits = listOf(Hits(2, 5, 3), Hits(1, 9, 4)), rowCount = 12, averageLength = listOf(3, 70), length = listOf(4, 90))

        val info = FtsMatchInfo.parse(blob + byteArrayOf(1, 2, 3))

        assertEquals(2, info.hitsInRow(0, 0))
        assertEquals(4, info.rowsWithHit(0, 1))
        assertEquals(12, info.rowCount)
        assertEquals(90, info.length[1])
    }

    @Test
    fun `senza la parte finale del blob righe e lunghezze valgono zero`() {
        // Solo l'intestazione (p, c) e i sei interi x: niente n, a, l.
        val blob = matchInfoBlob(phrases = 1, hits = listOf(Hits(1, 2, 3), Hits(4, 5, 6))).copyOf(4 * (2 + 6))

        val info = FtsMatchInfo.parse(blob)

        assertEquals(4, info.hitsInRow(0, 1))
        assertEquals(6, info.rowsWithHit(0, 1))
        assertEquals(0, info.rowCount)
        assertEquals(listOf(0, 0), info.averageLength.toList())
        assertEquals(listOf(0, 0), info.length.toList())
    }

    @Test
    fun `i valori oltre un byte si leggono come interi a 32 bit`() {
        val blob = matchInfoBlob(phrases = 1, hits = listOf(Hits(0, 0, 0), Hits(70_000, 16_777_216, 1)), rowCount = 300)

        val info = FtsMatchInfo.parse(blob)

        assertEquals(70_000, info.hitsInRow(0, 1))
        assertEquals(300, info.rowCount)
    }

    @Test
    fun `il titolo pesa tre volte il corpo a parita' di statistiche`() {
        val inTitle = matchInfoBlob(phrases = 1, hits = listOf(Hits(2, 5, 5), Hits(0, 0, 0)))
        val inBody = matchInfoBlob(phrases = 1, hits = listOf(Hits(0, 0, 0), Hits(2, 5, 5)))

        assertEquals(3 * score(inBody), score(inTitle), 1e-9)
    }

    @Test
    fun `il punteggio di piu' frasi e' la somma di quello di ognuna`() {
        val both = matchInfoBlob(phrases = 2, hits = listOf(Hits(1, 3, 3), Hits(0, 0, 0), Hits(2, 4, 4), Hits(0, 0, 0)))
        val first = matchInfoBlob(phrases = 1, hits = listOf(Hits(1, 3, 3), Hits(0, 0, 0)))
        val second = matchInfoBlob(phrases = 1, hits = listOf(Hits(2, 4, 4), Hits(0, 0, 0)))

        assertEquals(score(first) + score(second), score(both), 1e-9)
    }

    @Test
    fun `una parola in ogni riga vale poco ma non zero e meno di una rara`() {
        val everywhere = matchInfoBlob(phrases = 1, hits = listOf(Hits(1, 10, 10), Hits(0, 0, 0)), rowCount = 10)
        val rare = matchInfoBlob(phrases = 1, hits = listOf(Hits(1, 1, 1), Hits(0, 0, 0)), rowCount = 10)

        assertTrue(score(everywhere) > 0.0)
        assertTrue(score(rare) > score(everywhere))
    }

    @Test
    fun `con lunghezze medie e della riga a zero il punteggio resta finito`() {
        val blob = matchInfoBlob(
            phrases = 1,
            hits = listOf(Hits(1, 1, 1), Hits(1, 1, 1)),
            rowCount = 1,
            averageLength = listOf(0, 0),
            length = listOf(0, 0),
        )

        val value = score(blob)

        assertTrue(value.isFinite())
        assertTrue(value > 0.0)
    }

    @Test
    fun `una tabella senza righe non fa dividere per zero la lunghezza media`() {
        val empty = FtsMatchInfo.parse(matchInfoBlob(phrases = 1, hits = listOf(Hits(0, 0, 0), Hits(0, 0, 0)), rowCount = 0, averageLength = listOf(0, 0)))

        val stats = FtsCorpusStats.of(listOf(empty))

        assertEquals(0.0, stats.averageLength(0), 0.0)
        assertEquals(0.0, stats.averageLength(1), 0.0)
    }

    @Test
    fun `le statistiche di piu' tabelle sono medie pesate sulle righe e somme delle righe con la frase`() {
        val guide = FtsMatchInfo.parse(
            matchInfoBlob(phrases = 1, hits = listOf(Hits(0, 0, 0), Hits(1, 3, 3)), rowCount = 10, averageLength = listOf(2, 100)),
        )
        val cities = FtsMatchInfo.parse(
            matchInfoBlob(phrases = 1, hits = listOf(Hits(0, 0, 0), Hits(1, 7, 7)), rowCount = 30, averageLength = listOf(4, 300)),
        )

        val stats = FtsCorpusStats.of(listOf(guide, cities))

        assertEquals((10 * 2 + 30 * 4) / 40.0, stats.averageLength(0), 1e-9)
        assertEquals((10 * 100 + 30 * 300) / 40.0, stats.averageLength(1), 1e-9)
        // 40 righe, 3 + 7 con la frase nel corpo.
        assertEquals(Math.log(1 + (40 - 10 + 0.5) / (10 + 0.5)), stats.idf(0, 1), 1e-12)
    }

    @Test
    fun `senza righe con la frase nelle statistiche l'idf conta una riga sola`() {
        // rowsWithHit 0 su una colonna in cui la riga corrisponde: le statistiche lo portano a una riga, senza divisione per zero.
        val info = FtsMatchInfo.parse(matchInfoBlob(phrases = 1, hits = listOf(Hits(1, 1, 0), Hits(0, 0, 0)), rowCount = 10))

        val stats = FtsCorpusStats.of(listOf(info))

        assertEquals(Math.log(1 + (10 - 1 + 0.5) / (1 + 0.5)), stats.idf(0, 0), 1e-12)
    }
}
