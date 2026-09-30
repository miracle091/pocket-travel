package com.pockettravel.core.data

import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import javax.inject.Inject

class GuideRepository @Inject constructor(
    private val guideDao: GuideDao,
) {
    /**
     * Solo per i test (GuideRepositoryTest la usa per popolare il database prima di provare
     * [sectionsFor] e [searchInRegion]); il codice dell'app non la chiama. Aggiunge le sezioni senza
     * togliere quelle gia' presenti, quindi non va usata per aggiornare una guida: l'import reale del
     * pacchetto guide passa da GuidesImporter (core/sync), che svuota e reimporta tutto in una
     * transazione.
     */
    suspend fun importSections(sections: List<GuideSection>) {
        guideDao.insertAll(sections.map { it.toEntity() })
    }

    suspend fun sectionsFor(regionId: String): List<GuideSection> =
        guideDao.sectionsForRegion(regionId).map { it.toDomain() }

    /**
     * FTS4 non ha bm25() (arrivato solo con FTS5): si prendono fino a CANDIDATE_CAP candidati col
     * loro matchinfo e si riordinano per rilevanza in Kotlin (vedi matchScore) prima di tagliare a
     * [limit], invece di affidarsi al semplice ordine per rowid della MATCH.
     */
    suspend fun searchInRegion(regionId: String, query: String, limit: Int): List<GuideSection> =
        guideDao.searchInRegionRanked(regionId, query, maxOf(limit, CANDIDATE_CAP))
            .sortedByDescending { matchScore(it.matchinfo) }
            .take(limit)
            .map { it.section.toDomain() }

    /**
     * Come searchInRegion, ma con il punteggio esposto: usata da TravelAssistant per unire i
     * candidati con quelli di CityRepository.searchInRegionScored, che usa lo stesso schema di
     * ranking (vedi matchScore).
     */
    suspend fun searchInRegionScored(regionId: String, query: String, limit: Int): List<Pair<GuideSection, Double>> =
        guideDao.searchInRegionRanked(regionId, query, maxOf(limit, CANDIDATE_CAP))
            .map { it.section.toDomain() to matchScore(it.matchinfo) }
            .sortedByDescending { it.second }
            .take(limit)

    private companion object {
        const val CANDIDATE_CAP = 30
    }
}

// Colonne di guide_sections_fts nell'ordine dichiarato in GuideSectionFts: title poi body.
private const val TITLE_COLUMN = 0
private const val TITLE_WEIGHT = 3.0
private const val BODY_WEIGHT = 1.0

/**
 * Punteggio di rilevanza da matchinfo(guide_sections_fts, 'pcx'): interi a 32 bit little-endian
 * (nativo su Android). 'p' = numero di frasi della query FTS, 'c' = numero di colonne (title, body),
 * poi per ogni coppia frase/colonna una tripla (occorrenze in questa riga, occorrenze totali,
 * righe con almeno un'occorrenza). Il titolo pesa piu' del corpo, e i termini presenti in quasi
 * tutte le sezioni contano poco (idf-like: 1/righe-con-match) — cosi' un token generico come il
 * nome della regione (es. "marino" per San Marino, presente in ogni sezione) non decide piu' da
 * solo quale sezione vince.
 */
private fun matchScore(matchinfo: ByteArray): Double {
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

private fun GuideSection.toEntity() = GuideSectionEntity(
    regionId = regionId,
    category = category,
    title = title,
    body = body,
    sourceUrl = sourceUrl,
)

private fun GuideSectionEntity.toDomain() = GuideSection(
    regionId = regionId,
    category = category,
    title = title,
    body = body,
    sourceUrl = sourceUrl,
)
