package com.pockettravel.feature.ai

import com.pockettravel.core.data.CityRepository
import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.GuideRepository
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.GuideSourceTarget
import javax.inject.Inject

// Le fonti si salvano nella lingua della risposta: la conversazione ripresa può averne una diversa da quella attuale.
private val CITATION_LANGUAGES = listOf("it", "en")

/** Risale dalla fonte sotto una risposta alla sezione della guida installata che l'ha prodotta. */
class GuideCitationResolver @Inject constructor(
    private val guideRepository: GuideRepository,
    private val cityRepository: CityRepository,
) {
    /** La sezione di [regionId] citata da [citation], o null se la fonte non è di una sezione installata. */
    suspend fun resolve(regionId: String, citation: String, answer: String): GuideSourceTarget? =
        resolveGuideCitation(
            citation = citation,
            answer = answer,
            countrySections = { guideRepository.sectionsFor(regionId) },
            cityNames = { cityRepository.cityNamesFor(regionId) },
            citySections = { city -> cityRepository.sectionsFor(regionId, city) },
        )
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
): GuideSourceTarget? {
    if (!citation.startsWith("Fonte: ") && !citation.startsWith("Source: ")) return null
    val country = countrySections().firstOrNull { section -> CITATION_LANGUAGES.any { section.citation(it) == citation } }
    return if (country != null) {
        GuideSourceTarget(city = null, category = country.category, title = country.title, answer = answer)
    } else {
        cityNames().filter { citation.contains(" ($it) — ") }.firstNotNullOfOrNull { city ->
            citySections(city).firstOrNull { section -> CITATION_LANGUAGES.any { section.citation(it) == citation } }
                ?.let { GuideSourceTarget(city = city, category = it.category, title = it.title, answer = answer) }
        }
    }
}
