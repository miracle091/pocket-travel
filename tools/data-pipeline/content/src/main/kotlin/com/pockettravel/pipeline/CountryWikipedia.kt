package com.pockettravel.pipeline

import org.json.JSONObject

// Sezioni della voce di Wikipedia della regione (country_wikipedia.py le estrae come "== Titolo ==" con le stesse chiavi
// di SECTIONS li') -> categoria della guida. Si aggiungono sempre, anche quando Wikivoyage ha la categoria.
private val wikipediaHeadingToCategory = mapOf(
    "gastronomia" to "CIBO_BEVANDE",
    "cucina" to "CIBO_BEVANDE",
    "tradizioni" to "USI_COSTUMI",
    "usi e costumi" to "USI_COSTUMI",
    "tradizioni e folclore" to "USI_COSTUMI",
    "folclore e tradizioni" to "USI_COSTUMI",
    "festività" to "USI_COSTUMI",
    "festività nazionali" to "USI_COSTUMI",
    "mezzi di comunicazione" to "VITA_QUOTIDIANA",
    "mezzi di comunicazione di massa" to "VITA_QUOTIDIANA",
    "media" to "VITA_QUOTIDIANA",
    "telecomunicazioni" to "CONNETTIVITA",
    "comunicazioni" to "CONNETTIVITA",
    "internet" to "CONNETTIVITA",
)
private val wikipediaHeadingToCategoryEn = mapOf(
    "cuisine" to "CIBO_BEVANDE",
    "customs" to "USI_COSTUMI",
    "traditions" to "USI_COSTUMI",
    "etiquette" to "USI_COSTUMI",
    "festivals" to "USI_COSTUMI",
    "holidays" to "USI_COSTUMI",
    "public holidays" to "USI_COSTUMI",
    "holidays and festivals" to "USI_COSTUMI",
    "media" to "VITA_QUOTIDIANA",
    "mass media" to "VITA_QUOTIDIANA",
    "telecommunications" to "CONNETTIVITA",
    "telecommunication" to "CONNETTIVITA",
    "communications" to "CONNETTIVITA",
    "internet" to "CONNETTIVITA",
)

// Sezioni piu' corte, una volta pulite, sono di solito solo il rimando alla voce a parte ("{{vedi anche|Cucina
// keniota}}" e una frase): non rispondono a nessuna domanda. Le lunghe si accorciano come la Storia delle citta'.
internal const val WIKIPEDIA_MIN_CHARS = 200
private const val WIKIPEDIA_MAX_CHARS = 4000

internal fun isWikipediaUrl(url: String?): Boolean =
    url.orEmpty().substringAfter("://").substringBefore('/').endsWith("wikipedia.org")

/**
 * [body] senza i sottotitoli "▸" rimasti senza testo: dropDanglingIntros toglie la frase che introduceva una tabella
 * ("Le principali festivita' sono:"), che per cleanBody era il testo del sottotitolo.
 */
internal fun dropOrphanSubheadings(body: String): String {
    val lines = body.lines()
    return lines.filterIndexed { i, line ->
        !line.startsWith("▸ ") || lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.startsWith("▸ ") == false
    }.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
}

/** Sezioni della guida dalle sezioni [text] (wikitext) della voce [title] di Wikipedia, ognuna con la voce come fonte. */
fun wikipediaGuideSections(title: String, text: String, english: Boolean): List<GuideSectionRow> {
    val lang = if (english) "en" else "it"
    val url = "https://$lang.wikipedia.org/wiki/" + title.replace(' ', '_')
    return parseWikivoyageDump(text, if (english) wikipediaHeadingToCategoryEn else wikipediaHeadingToCategory).mapNotNull { section ->
        val body = truncateSection(dropOrphanSubheadings(dropDanglingIntros(section.body)), WIKIPEDIA_MAX_CHARS)
        if (body.length < WIKIPEDIA_MIN_CHARS) null else section.copy(body = body, sourceUrl = url)
    }
}

/** {regionId: sezioni} dal file di country_wikipedia.py; una regione presente senza sezioni ha la lista vuota. */
fun parseCountryWikipedia(jsonl: String, english: Boolean): Map<String, List<GuideSectionRow>> =
    jsonl.lineSequence().filter { it.isNotBlank() }.associate { line ->
        val obj = JSONObject(line)
        obj.getString("regionId") to wikipediaGuideSections(obj.getString("title"), obj.getString("text"), english)
    }

/**
 * Le guide con le sezioni di Wikipedia di [fresh] in fondo, al posto di quelle che avevano. Le regioni assenti da [fresh]
 * (voce non letta in questa run) tengono le sezioni di Wikipedia gia' pubblicate, [published] della regione.
 */
fun List<RegionGuide>.withWikipedia(
    fresh: Map<String, List<GuideSectionRow>>,
    published: (String) -> List<GuideSectionRow>,
): List<RegionGuide> = map { guide ->
    val wikipedia = fresh[guide.regionId] ?: published(guide.regionId).filter { isWikipediaUrl(it.sourceUrl) }
    guide.copy(sections = guide.sections.filterNot { isWikipediaUrl(it.sourceUrl) } + wikipedia)
}
