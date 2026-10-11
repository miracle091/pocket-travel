package com.pockettravel.feature.ai

import com.pockettravel.core.data.GuideCategory

/**
 * Liste di parole per categoria, per le domande dette con parole che le guide non usano ("pernottare", "souvenir",
 * "Where to stay" sulle guide inglesi, che scrivono "Sleep"). Radici (senza accenti) che nella domanda, in italiano o
 * in inglese, indicano la categoria: una parola le contiene se comincia con la radice; sotto le 4 lettere deve essere
 * uguale, o con una "s" in più ("tip", "tips"). Costruite dagli errori di tools/data-pipeline/scripts/eval_retrieval.py
 * e da sinonimi delle stesse richieste. Accese per tutti; nelle build di debug il task manager le spegne
 * (WordListsOverride). La stessa tabella e' in eval_retrieval.py (--word-lists), che un test confronta con questa.
 */
private val categoryTriggers = mapOf(
    GuideCategory.ALLOGGIO to "allogg dormi pernot notte alberg hotel ostell campeg stay sleep accomm lodg hostel night camp",
    GuideCategory.CIBO_BEVANDE to "mangi cibo cucin piatt assag ristor pranz cena cenar colaz specia bere vino birra " +
        "food eat dish cuisin restau lunch dinner breakf tast drink wine beer",
    GuideCategory.COSA_VEDERE to "veder visit attraz monum muse perder merit see sight attract worth miss",
    GuideCategory.ACQUISTI to "acqui compr souven negoz mercat manci camer pagar contan bancom soldi valut " +
        "shop buy market tip waiter pay cash card money curren atm",
    GuideCategory.TRASPORTI to "arriv spost muov treno treni autob macchin auto nolegg guidar raggiun " +
        "bus train car drive rent transp metro reach around",
    GuideCategory.DOGANE to "passap docum front dogan visto alcol sigar passport border custom visa alcoh cigar",
    GuideCategory.SALUTE to "vacci ammal malat medic osped salut farmac sanit sick ill doctor hospit health jab pharm",
    GuideCategory.SICUREZZA to "sicur peric derub furt borseg ladr crimin truff safe danger rob theft thief pickp crime scam",
    GuideCategory.CONNETTIVITA to "intern telef cellul sim wifi colleg roami online phone mobile connec",
    GuideCategory.STORIA to "stori origin nata nato fond passat medioev govern antic roman guerr " +
        "histor found establ past ancien rule middl war",
    GuideCategory.CLIMA to "clima meteo piog piov ombrel tempera primav estat invern autun nevic neve caldo fredd stagion " +
        "climat weather rain umbrel spring summer winter autumn snow hot cold season",
).mapValues { (_, stems) -> stems.split(' ') }

/**
 * Parole dei titoli delle sezioni della categoria nella lingua della guida, nella forma della query FTS: con "*" per
 * prefisso; due parole sono una frase ("get in" e' "Get in", non "get" o "in" ovunque).
 */
private val categoryGuideWords = mapOf(
    "it" to mapOf(
        GuideCategory.ALLOGGIO to "allogg*",
        GuideCategory.CIBO_BEVANDE to "mangi*, tavola",
        GuideCategory.COSA_VEDERE to "veder*",
        GuideCategory.ACQUISTI to "acqui*, valut*",
        GuideCategory.TRASPORTI to "arriv*, spost*",
        GuideCategory.DOGANE to "arriv*, dogan*",
        GuideCategory.SALUTE to "sanit*, salut*",
        GuideCategory.SICUREZZA to "sicur*",
        GuideCategory.CONNETTIVITA to "contat*",
        GuideCategory.STORIA to "stori*",
        GuideCategory.CLIMA to "clima",
    ),
    "en" to mapOf(
        GuideCategory.ALLOGGIO to "sleep",
        GuideCategory.CIBO_BEVANDE to "eat, drink",
        GuideCategory.COSA_VEDERE to "see",
        GuideCategory.ACQUISTI to "buy",
        GuideCategory.TRASPORTI to "get in, get around",
        GuideCategory.DOGANE to "get in, customs",
        GuideCategory.SALUTE to "health*",
        GuideCategory.SICUREZZA to "safe*",
        GuideCategory.CONNETTIVITA to "connect*",
        GuideCategory.STORIA to "history",
        GuideCategory.CLIMA to "climate",
    ),
).mapValues { (_, byCategory) ->
    byCategory.mapValues { (_, words) -> words.split(", ").map { if (' ' in it) "\"$it\"" else it } }
}

private const val SHORT_STEM_CHARS = 4

/** Le categorie che le parole di [question] indicano (categoryTriggers). */
internal fun questionCategories(question: String): Set<GuideCategory> {
    val tokens = question.split(nonWord).filter { it.isNotEmpty() }.map(::folded)
    fun hit(stem: String) = tokens.any { if (stem.length >= SHORT_STEM_CHARS) it.startsWith(stem) else it == stem || it == "${stem}s" }
    return categoryTriggers.filterValues { stems -> stems.any(::hit) }.keys
}

/**
 * [ftsQuery] con in OR le parole dei titoli delle guide in [language] delle categorie indicate da [question]
 * (categoryGuideWords), senza doppioni; [ftsQuery] com'e' con [enabled] false. Vuota resta vuota solo se la domanda non
 * indica nessuna categoria. Le radici dei paragrafi (focusStems) vengono dalla query senza queste parole.
 */
internal fun withCategoryWords(ftsQuery: String, question: String, language: String, enabled: Boolean = true): String {
    if (!enabled) return ftsQuery
    val words = categoryGuideWords[language] ?: categoryGuideWords.getValue("en")
    val extra = questionCategories(question).sortedBy { it.name }.flatMap { words[it].orEmpty() }
    return (ftsQuery.split(" OR ").filter { it.isNotBlank() } + extra).distinct().joinToString(" OR ")
}
