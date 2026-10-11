package com.pockettravel.feature.ai

/**
 * Liste di parole per categoria, per le domande dette con parole che le guide non usano ("pernottare", "souvenir",
 * "Where to stay" sulle guide inglesi, che scrivono "Sleep"). Radici (senza accenti) che nella domanda, in italiano o
 * in inglese, indicano la categoria: una parola le contiene se comincia con la radice; sotto le 4 lettere deve essere
 * uguale, o con una "s" in più ("tip", "tips"). Costruite dagli errori di tools/data-pipeline/scripts/eval_retrieval.py
 * e da sinonimi delle stesse richieste. Accese per tutti; nelle build di debug il task manager le spegne
 * (WordListsOverride). La stessa tabella e' in eval_retrieval.py (--word-lists), che un test confronta con questa.
 */
private val categoryTriggers = mapOf(
    "ALLOGGIO" to "allogg dormi pernot notte alberg hotel ostell campeg stay sleep accomm lodg hostel night camp",
    "CIBO_BEVANDE" to "mangi cibo cucin piatt assag ristor pranz cena cenar colaz specia bere vino birra " +
        "food eat dish cuisin restau lunch dinner breakf tast drink wine beer",
    "COSA_VEDERE" to "veder visit attraz monum muse perder merit see sight attract worth miss",
    "ACQUISTI" to "acqui compr souven negoz mercat manci camer pagar contan bancom soldi valut " +
        "shop buy market tip waiter pay cash card money curren atm",
    "TRASPORTI" to "arriv spost muov treno treni autob macchin auto nolegg guidar raggiun " +
        "bus train car drive rent transp metro reach around",
    "DOGANE" to "passap docum front dogan visto alcol sigar passport border custom visa alcoh cigar",
    "SALUTE" to "vacci ammal malat medic osped salut farmac sanit sick ill doctor hospit health jab pharm",
    "SICUREZZA" to "sicur peric derub furt borseg ladr crimin truff safe danger rob theft thief pickp crime scam",
    "CONNETTIVITA" to "intern telef cellul sim wifi colleg roami online phone mobile connec",
    "STORIA" to "stori origin nata nato fond passat medioev govern antic roman guerr " +
        "histor found establ past ancien rule middl war",
    "CLIMA" to "clima meteo piog piov ombrel tempera primav estat invern autun nevic neve caldo fredd stagion " +
        "climat weather rain umbrel spring summer winter autumn snow hot cold season",
    // Non categorie ma richieste: cosa fare contro un problema e dopo una perdita o un furto. Portano alle sottosezioni
    // pratiche delle pagine del mondo ("Jet lag: Recupero", "Documenti: In caso di emergenza") invece della definizione.
    "CONSIGLI" to "combat evitar preven curar rimed superar affront deal cope avoid prevent cure treat overcom",
    "EMERGENZE" to "perdo perso persa smarr rubat lose lost stolen",
).mapValues { (_, stems) -> stems.split(' ') }

/**
 * Parole dei titoli delle sezioni della categoria nella lingua della guida, nella forma della query FTS: con "*" per
 * prefisso; due parole sono una frase ("get in" e' "Get in", non "get" o "in" ovunque).
 */
private val categoryGuideWords = mapOf(
    "it" to mapOf(
        "ALLOGGIO" to "allogg*",
        "CIBO_BEVANDE" to "mangi*, tavola",
        "COSA_VEDERE" to "veder*",
        "ACQUISTI" to "acqui*, valut*",
        "TRASPORTI" to "arriv*, spost*",
        "DOGANE" to "arriv*, dogan*",
        "SALUTE" to "sanit*, salut*",
        "SICUREZZA" to "sicur*",
        "CONNETTIVITA" to "contat*",
        "STORIA" to "stori*",
        "CLIMA" to "clima",
        "CONSIGLI" to "prepar*, recupe*, preven*, tratta*",
        "EMERGENZE" to "emerg*",
    ),
    "en" to mapOf(
        "ALLOGGIO" to "sleep",
        "CIBO_BEVANDE" to "eat, drink",
        "COSA_VEDERE" to "see",
        "ACQUISTI" to "buy",
        "TRASPORTI" to "get in, get around",
        "DOGANE" to "get in, customs",
        "SALUTE" to "health*",
        "SICUREZZA" to "safe*",
        "CONNETTIVITA" to "connect*",
        "STORIA" to "history",
        "CLIMA" to "climate",
        "CONSIGLI" to "prepar*, cope, recover*, prevent*, treat*",
        "EMERGENZE" to "lose, lost, stolen",
    ),
).mapValues { (_, byCategory) ->
    byCategory.mapValues { (_, words) -> words.split(", ").map { if (' ' in it) "\"$it\"" else it } }
}

private const val SHORT_STEM_CHARS = 4

/** I gruppi (categorie con il nome di GuideCategory, CONSIGLI, EMERGENZE) che le parole di [question] indicano. */
internal fun questionCategories(question: String): Set<String> {
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
    val extra = questionCategories(question).sorted().flatMap { words[it].orEmpty() }
    return (ftsQuery.split(" OR ").filter { it.isNotBlank() } + extra).distinct().joinToString(" OR ")
}
