package com.pockettravel.core.data

import com.pockettravel.core.data.db.CityCoordinates
import com.pockettravel.core.data.db.CityDao
import com.pockettravel.core.data.db.CitySectionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class CityRepository @Inject constructor(
    private val cityDao: CityDao,
) {
    /** Nomi delle citta' abbinate alla regione, in ordine alfabetico. */
    fun citiesFor(regionId: String): Flow<List<String>> = cityDao.citiesForRegion(regionId)

    /** Le [limit] citta' con la guida piu' ricca, dalla piu' ricca: le scorciatoie della scheda Citta'. */
    fun mainCitiesFor(regionId: String, limit: Int): Flow<List<MainCity>> =
        cityDao.mainCitiesForRegion(regionId, limit).map { rows -> rows.map { MainCity(it.city, it.population, it.capital) } }

    /** Sezioni della citta': prima quelle di Wikivoyage (per categoria), poi Storia e Clima di Wikipedia. */
    suspend fun sectionsFor(regionId: String, city: String): List<CitySection> =
        cityDao.sectionsFor(regionId, city).map { it.toDomain() }
            .sortedBy { WIKIPEDIA_CATEGORIES.indexOf(it.category) }

    /** Coordinate della citta', null se il cities.db installato non le ha (per la distanza tra due citta' dell'assistente). */
    suspend fun coordinatesFor(regionId: String, city: String): CityCoordinates? = cityDao.coordinatesFor(regionId, city)

    /** I nomi delle citta' della regione, una volta sola (per riconoscerle nelle domande dell'assistente). */
    suspend fun cityNamesFor(regionId: String): List<String> = cityDao.citiesForRegion(regionId).first()

    /**
     * Candidati della ricerca FTS4 col loro matchinfo letto, come GuideRepository.searchCandidates: TravelAssistant
     * li ordina insieme a quelli della guida del paese. Con [city] solo le sezioni di quella citta' (tutte: sono
     * poche); senza, fino a CANDIDATE_CAP in ordine di rowid. [query] e' un'espressione FTS4 MATCH gia' pulita.
     */
    suspend fun searchCandidates(regionId: String, query: String, city: String? = null): List<Pair<CitySection, FtsMatchInfo>> =
        cityDao.searchInRegionRanked(regionId, query, city, if (city != null) CITY_CANDIDATE_CAP else CANDIDATE_CAP)
            .map { it.section.toDomain() to FtsMatchInfo.parse(it.matchinfo) }

    private companion object {
        const val CANDIDATE_CAP = 100
        const val CITY_CANDIDATE_CAP = 100

        // In coda alla guida della citta', in quest'ordine; indexOf = -1 lascia le altre in testa nel loro ordine.
        val WIKIPEDIA_CATEGORIES = listOf(GuideCategory.STORIA, GuideCategory.CLIMA)
    }
}

data class CitySection(
    val regionId: String,
    val city: String,
    val category: GuideCategory,
    val title: String,
    val body: String,
    val sourceUrl: String,
)

private fun CitySectionEntity.toDomain() = CitySection(
    regionId = regionId,
    city = city,
    category = category,
    title = title,
    body = body,
    sourceUrl = sourceUrl,
)

/** Una delle citta' principali di una regione: nome, abitanti (null se ignoti) e se e' la capitale. */
data class MainCity(val name: String, val population: Long?, val capital: Boolean = false)
