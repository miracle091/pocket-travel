package com.pockettravel.feature.ai

import com.pockettravel.core.data.CityRepository
import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.DefaultDispatcher
import com.pockettravel.core.data.GuideRepository
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.GuideSourceTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

// Le fonti si salvano nella lingua della risposta: la conversazione ripresa può averne una diversa da quella attuale.
private val CITATION_LANGUAGES = listOf("it", "en")

/** Risale dalla fonte sotto una risposta alla sezione della guida installata che l'ha prodotta. */
class GuideCitationResolver @Inject constructor(
    private val guideRepository: GuideRepository,
    private val cityRepository: CityRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {
    /**
     * Le sezioni di [regionId] citate da [citations] (solo quelle che si risolvono, per fonte). Le sezioni della nazione e
     * i nomi delle città si caricano una volta sola per tutte le fonti, e il confronto non gira sul thread principale.
     */
    suspend fun resolveAll(regionId: String, citations: List<String>, answer: String): Map<String, GuideSourceTarget> =
        withContext(defaultDispatcher) {
            resolveGuideCitations(
                citations = citations,
                answer = answer,
                countrySections = { guideRepository.sectionsFor(regionId) },
                cityNames = { cityRepository.cityNamesFor(regionId) },
                citySections = { city -> cityRepository.sectionsFor(regionId, city) },
            )
        }
}

/**
 * Ricostruisce le fonti delle sezioni con le stesse funzioni che le hanno scritte (GuideSection.citation e
 * CitySection.citation) e cerca quella uguale a [citation]. Delle città carica solo quelle nominate dalla fonte.
 */
internal suspend fun resolveGuideCitation(
    citation: String,
    answer: String,
    countrySections: suspend () -> List<GuideSection>,
    cityNames: suspend () -> List<String>,
    citySections: suspend (city: String) -> List<CitySection>,
): GuideSourceTarget? = resolveGuideCitations(listOf(citation), answer, countrySections, cityNames, citySections)[citation]

/** Come [resolveGuideCitation] per più fonti: ogni elenco (nazione, nomi delle città, sezioni di una città) si carica al massimo una volta. */
internal suspend fun resolveGuideCitations(
    citations: List<String>,
    answer: String,
    countrySections: suspend () -> List<GuideSection>,
    cityNames: suspend () -> List<String>,
    citySections: suspend (city: String) -> List<CitySection>,
): Map<String, GuideSourceTarget> {
    val candidates = citations.distinct().filter { it.startsWith("Fonte: ") || it.startsWith("Source: ") }
    if (candidates.isEmpty()) return emptyMap()
    val country = countrySections()
    var names: List<String>? = null
    val cities = mutableMapOf<String, List<CitySection>>()
    val result = mutableMapOf<String, GuideSourceTarget>()
    for (citation in candidates) {
        val countrySection = country.firstOrNull { section -> CITATION_LANGUAGES.any { section.citation(it) == citation } }
        if (countrySection != null) {
            result[citation] = GuideSourceTarget(city = null, category = countrySection.category, title = countrySection.title, answer = answer)
            continue
        }
        val allNames = names ?: cityNames().also { names = it }
        allNames.filter { citation.contains(" ($it) — ") }.firstNotNullOfOrNull { city ->
            cities.getOrPut(city) { citySections(city) }.firstOrNull { section -> CITATION_LANGUAGES.any { section.citation(it) == citation } }
                ?.let { GuideSourceTarget(city = city, category = it.category, title = it.title, answer = answer) }
        }?.let { result[citation] = it }
    }
    return result
}
