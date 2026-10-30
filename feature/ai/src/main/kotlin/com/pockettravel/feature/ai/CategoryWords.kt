package com.pockettravel.feature.ai

/**
 * Liste di parole, per le domande dette con parole che le guide non usano ("pernottare", "souvenir",
 * "Where to stay" sulle guide inglesi, che scrivono "Sleep"): radici che nella domanda indicano la categoria e parole dei
 * titoli delle guide da aggiungere alla query, in word_lists.txt (WordLists). Una parola contiene una radice se comincia
 * con la radice; sotto le 4 lettere deve essere uguale, o con una "s" in piu' ("tip", "tips"), come con "=" in fondo;
 * con "+" e' un'espressione, che conta all'inizio di una parola ("best+time"). Costruite dagli errori di
 * tools/data-pipeline/scripts/eval_retrieval.py e da sinonimi delle stesse richieste. Accese per tutti; nelle build di
 * debug il task manager le spegne (WordListsOverride).
 */
private val categoryTriggers = WordLists.categoryTriggers

// Nella forma della query FTS: due parole sono una frase ("get in" e' "Get in", non "get" o "in" ovunque).
private val categoryGuideWords = WordLists.categoryGuideWords.mapValues { (_, byCategory) ->
    byCategory.mapValues { (_, words) -> words.map { if (' ' in it) "\"$it\"" else it } }
}

private const val SHORT_STEM_CHARS = 4

// Previsioni del tempo ("Che tempo fa domani?", "Pioverà sabato?"): una parola di previsione, oppure una del clima
// (CLIMA, o "che tempo" di PERIODI) insieme a un momento vicino. Da sole le parole del momento non bastano ("Cosa
// vedere oggi?"). Il gruppo METEO prende il posto di CLIMA e PERIODI.
private val forecastStems = WordLists.forecastStems
private val nearTimeStems = WordLists.nearTimeStems

/**
 * I gruppi (categorie con il nome di GuideCategory, CONSIGLI, EMERGENZE, PERIODI, METEO) che le parole di [question]
 * indicano.
 */
internal fun questionGroups(question: String): Set<String> {
    val tokens = question.split(nonWord).filter { it.isNotEmpty() }.map(::folded)
    val text = tokens.joinToString(" ", prefix = " ", postfix = " ")
    fun hit(stem: String) = when {
        '+' in stem -> " ${stem.replace('+', ' ')}" in text
        stem.endsWith('=') -> stem.dropLast(1).let { word -> tokens.any { it == word || it == "${word}s" } }
        stem.length >= SHORT_STEM_CHARS -> tokens.any { it.startsWith(stem) }
        else -> tokens.any { it == stem || it == "${stem}s" }
    }
    val groups = categoryTriggers.filterValues { stems -> stems.any(::hit) }.keys
    val weather = "CLIMA" in groups || hit("che+tempo")
    val forecast = forecastStems.any(::hit) || weather && nearTimeStems.any(::hit)
    return if (forecast) groups - "CLIMA" - "PERIODI" + "METEO" else groups
}

/**
 * [ftsQuery] con in OR le parole dei titoli delle guide in [language] (categoryGuideWords) delle categorie indicate da
 * [question] e di [extraGroups] (la categoria scelta dal modello, modelQuestionCategory), in fondo e senza doppioni;
 * [ftsQuery] com'e' con [enabled] false. Vuota resta vuota solo se ne' la domanda ne' [extraGroups] indicano una
 * categoria. Le radici dei paragrafi (focusStems) vengono dalla query senza queste parole.
 */
internal fun withCategoryWords(ftsQuery: String, question: String, language: String, enabled: Boolean = true, extraGroups: Set<String> = emptySet()): String {
    if (!enabled) return ftsQuery
    val words = categoryGuideWords[language] ?: categoryGuideWords.getValue("en")
    val extra = (questionGroups(question) + extraGroups).sorted().flatMap { words[it].orEmpty() }
    return (ftsQuery.split(" OR ").filter { it.isNotBlank() } + extra).distinct().joinToString(" OR ")
}
