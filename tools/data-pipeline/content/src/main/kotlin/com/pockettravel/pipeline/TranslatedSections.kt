package com.pockettravel.pipeline

import org.json.JSONObject

/**
 * Sezioni di una regione o citta' ([owner]) e [category] tradotte da translate_guides.py dalla guida nell'altra lingua
 * (italiano <-> inglese): sostituiscono tutte le sezioni della stessa categoria, tranne i consigli di viaggio che si
 * aggiungono (vedi withTranslations). Ogni riga ha [GuideSectionRow.translated]
 * e l'url della pagina d'origine.
 */
data class TranslatedCategory(val owner: String, val category: String, val sections: List<GuideSectionRow>)

/** Righe del file scritto da translate_guides.py: {"owner", "category", "sections": [{"title", "body", "sourceUrl"}]}. */
fun parseTranslatedSections(jsonl: String): List<TranslatedCategory> =
    jsonl.lineSequence().filter { it.isNotBlank() }.map { line ->
        val obj = JSONObject(line)
        val category = obj.getString("category")
        val sections = obj.getJSONArray("sections")
        TranslatedCategory(
            owner = obj.getString("owner"),
            category = category,
            sections = (0 until sections.length()).map { i ->
                val section = sections.getJSONObject(i)
                GuideSectionRow(category, section.getString("title"), section.getString("body"), section.getString("sourceUrl"), translated = true)
            },
        )
    }.toList()

/**
 * [rows] con le categorie di [replacements] ({categoria: righe}) al posto di quelle originali: il blocco tradotto
 * prende il posto della prima riga della categoria, quelle che non c'erano vanno in fondo.
 */
internal fun <T> replaceCategories(rows: List<T>, categoryOf: (T) -> String, replacements: Map<String, List<T>>): List<T> {
    val placed = mutableSetOf<String>()
    val kept = rows.flatMap { row ->
        val replacement = replacements[categoryOf(row)]
        when {
            replacement == null -> listOf(row)
            placed.add(categoryOf(row)) -> replacement
            else -> emptyList()
        }
    }
    return kept + replacements.filterKeys { it !in placed }.values.flatten()
}

/**
 * Le guide con le sezioni tradotte: quelle di Wikivoyage al posto della categoria, quelle dei consigli di viaggio
 * (isTravelAdvice, che la guida italiana non ha di suo) in fondo. I consigli tradotti di una categoria assente da
 * [translated] (traduzione non riuscita, rimandata per il tempo o spenta) restano quelli gia' pubblicati,
 * [publishedAdvice] della regione, come fa readTravelAdvice per la guida inglese.
 */
fun List<RegionGuide>.withTranslations(
    translated: List<TranslatedCategory>,
    publishedAdvice: (String) -> List<GuideSectionRow> = { emptyList() },
): List<RegionGuide> {
    val byRegion = translated.groupBy { it.owner }
    return map { guide ->
        val categories = byRegion[guide.regionId].orEmpty()
        val replacements = categories.associate { it.category to it.sections.filterNot(::isTravelAdvice) }.filterValues { it.isNotEmpty() }
        val advice = categories.associate { it.category to it.sections.filter(::isTravelAdvice) }.filterValues { it.isNotEmpty() }
        val previous = publishedAdvice(guide.regionId).filter { it.translated && isTravelAdvice(it) }
        if (replacements.isEmpty() && advice.isEmpty() && previous.isEmpty()) return@map guide
        val own = guide.sections.filterNot { it.translated && isTravelAdvice(it) }
        guide.copy(sections = replaceCategories(own, { it.category }, replacements) + replaceCategories(previous, { it.category }, advice))
    }
}

/** Come per le guide, per citta'; popolazione, capitale e coordinate restano quelle della citta' (le sezioni tradotte non le hanno). */
fun List<CitySectionRow>.withCityTranslations(translated: List<TranslatedCategory>): List<CitySectionRow> {
    val byCity = translated.groupBy { it.owner }
    return groupBy { it.city }.flatMap { (city, rows) ->
        val replacements = byCity[city]?.associate { it.category to it.sections } ?: return@flatMap rows
        val first = rows.first()
        val asCityRows = replacements.mapValues { (_, sections) ->
            sections.map {
                CitySectionRow(
                    city, it.category, it.title, it.body, it.sourceUrl.orEmpty(), first.population, first.capital,
                    latitude = first.latitude, longitude = first.longitude, translated = true,
                )
            }
        }
        replaceCategories(rows, { it.category }, asCityRows)
    }
}
