package com.pockettravel.feature.ai

import com.pockettravel.core.data.FtsCorpusStats
import com.pockettravel.core.data.FtsMatchInfo
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.bm25Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralGuideComparisonTest {

    private fun section(title: String, body: String) = GuideSection(
        regionId = "italia",
        category = GuideCategory.TRASPORTI,
        title = title,
        body = body,
        sourceUrl = "https://it.wikivoyage.org/wiki/Italia",
    )

    /** Due frasi (la parola della domanda, poi quella della categoria del modello) e due colonne: occorrenze nel corpo. */
    private fun info(questionHits: Int, categoryHits: Int) = FtsMatchInfo(
        phraseCount = 2,
        columnCount = 2,
        hits = intArrayOf(0, questionHits, 0, categoryHits),
        docsWithHit = intArrayOf(2, 3, 2, 3),
        rowCount = 20,
        averageLength = intArrayOf(3, 50),
        length = intArrayOf(3, 50),
    )

    @Test
    fun `bm25 con le sole prime frasi della query`() {
        val row = info(questionHits = 1, categoryHits = 4)
        val stats = FtsCorpusStats.of(listOf(row))

        assertTrue(bm25Score(row, stats, phrases = 1) < bm25Score(row, stats))
        assertEquals(bm25Score(info(questionHits = 1, categoryHits = 0), stats), bm25Score(row, stats, phrases = 1), 1e-9)
    }

    @Test
    fun `le parole della categoria del modello non tolgono la testa alla guida generale`() {
        // La regione vince solo grazie alla parola della categoria; sulla parola della domanda vince la guida generale.
        val region = listOf(section("Trasporti", "regione") to info(questionHits = 1, categoryHits = 5))
        val general = listOf(section("Borseggiatori: Proteggersi", "generale") to info(questionHits = 2, categoryHits = 0))

        assertEquals(listOf("regione"), rankSections(region, emptyList(), limit = 3, countryFirst = true, language = "it", generalGuideMatches = general).map { it.body })
        assertEquals(
            listOf("generale", "regione"),
            rankSections(region, emptyList(), limit = 3, countryFirst = true, language = "it", generalGuideMatches = general, comparePhrases = 1).map { it.body },
        )
    }
}
