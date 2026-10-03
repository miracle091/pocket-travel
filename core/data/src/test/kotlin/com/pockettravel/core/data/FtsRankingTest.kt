package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FtsRankingTest {

    private fun score(blob: ByteArray, vararg tables: ByteArray): Double {
        val info = FtsMatchInfo.parse(blob)
        return bm25Score(info, FtsCorpusStats.of(tables.map { FtsMatchInfo.parse(it) }.ifEmpty { listOf(info) }))
    }

    @Test
    fun `parse legge frasi, colonne, hit, righe e lunghezze`() {
        val info = FtsMatchInfo.parse(
            matchInfoBlob(phrases = 1, hits = listOf(Hits(1, 3, 2), Hits(4, 9, 5)), rowCount = 40, averageLength = listOf(3, 200), length = listOf(2, 350)),
        )

        assertEquals(1, info.phraseCount)
        assertEquals(2, info.columnCount)
        assertEquals(4, info.hitsInRow(0, 1))
        assertEquals(5, info.rowsWithHit(0, 1))
        assertEquals(40, info.rowCount)
        assertEquals(200, info.averageLength[1])
        assertEquals(350, info.length[1])
    }

    @Test
    fun `la parola rara nel titolo vince sul nome ripetuto nel corpo`() {
        // Caso reale: per "Quale valuta si usa a San Marino?" deve vincere "Valuta e acquisti", non "Come arrivare"
        // (che nomina San Marino piu' volte nel corpo). Frase 0 = "valuta", frase 1 = "marino"; colonne title, body.
        val comeArrivare = matchInfoBlob(
            phrases = 2,
            hits = listOf(Hits(0, 3, 1), Hits(0, 3, 1), Hits(0, 0, 2), Hits(5, 7, 2)),
        )
        val valutaAcquisti = matchInfoBlob(
            phrases = 2,
            hits = listOf(Hits(1, 3, 1), Hits(2, 3, 1), Hits(0, 0, 2), Hits(1, 7, 2)),
        )

        assertTrue(score(valutaAcquisti) > score(comeArrivare))
    }

    @Test
    fun `a parita' di occorrenze vince la sezione piu' corta`() {
        val corta = matchInfoBlob(phrases = 1, hits = listOf(Hits(0, 0, 3), Hits(2, 6, 3)), averageLength = listOf(3, 300), length = listOf(3, 100))
        val lunga = matchInfoBlob(phrases = 1, hits = listOf(Hits(0, 0, 3), Hits(2, 6, 3)), averageLength = listOf(3, 300), length = listOf(3, 1200))

        assertTrue(score(corta) > score(lunga))
    }

    @Test
    fun `le occorrenze ripetute contano sempre meno`() {
        fun body(hits: Int) = matchInfoBlob(phrases = 1, hits = listOf(Hits(0, 0, 3), Hits(hits, 40, 3)))

        val uno = score(body(1))
        val dieci = score(body(10))
        val venti = score(body(20))

        assertTrue(dieci > uno)
        assertTrue(venti - dieci < dieci - uno)
    }

    @Test
    fun `con le statistiche sommate la parola comune nelle citta' non vince nella piccola guida del paese`() {
        // "vedere": 1 riga su 10 nella guida del paese, 400 su 500 nelle citta'. Con le statistiche della sola guida
        // varrebbe molto; sommate (401 righe su 510) e' una parola comune e pesa poco.
        val guida = matchInfoBlob(phrases = 1, hits = listOf(Hits(0, 0, 0), Hits(1, 1, 1)), rowCount = 10)
        val citta = matchInfoBlob(phrases = 1, hits = listOf(Hits(0, 0, 0), Hits(1, 400, 400)), rowCount = 500)

        assertTrue(score(guida, guida) > 1.0)
        assertTrue(score(guida, guida, citta) < 0.5)
    }

    @Test
    fun `nessuna occorrenza, punteggio zero`() {
        assertEquals(0.0, score(matchInfoBlob(phrases = 1, hits = listOf(Hits(0, 2, 2), Hits(0, 5, 3)))), 0.0)
    }
}
