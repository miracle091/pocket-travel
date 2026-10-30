package com.pockettravel.feature.ai

/**
 * Liste di parole, per le domande dette con parole che le guide non usano ("pernottare", "souvenir",
 * "Where to stay" sulle guide inglesi, che scrivono "Sleep"). Radici (senza accenti) che nella domanda, in italiano o
 * in inglese, indicano la categoria: una parola le contiene se comincia con la radice; sotto le 4 lettere deve essere
 * uguale, o con una "s" in più ("tip", "tips"), come con "=" in fondo ("perso=" non trova "persone", "card=" non trova
 * "Cardiff"); con "+" e' un'espressione, che conta all'inizio di una parola ("best+time", "periodo+migliore"). Costruite dagli errori di tools/data-pipeline/scripts/eval_retrieval.py
 * e da sinonimi delle stesse richieste. Accese per tutti; nelle build di debug il task manager le spegne
 * (WordListsOverride). La stessa tabella e' in eval_retrieval.py (--word-lists), che un test confronta con questa.
 */
private val categoryTriggers = mapOf(
    "ALLOGGIO" to "allogg dormi pernot notte alberg hotel ostell campeg stay sleep accomm lodg hostel night= camp= camping campsite camera prenot room rooms",
    "CIBO_BEVANDE" to "mangi cibo cucin piatt assag ristor pranz cena cenar colaz specia bere vino birra " +
        "food eat dish cuisin restaurant lunch dinner breakf tast drink wine beer beve bevi bevan " +
        "dolci= dolce= dessert sweets= pastic pastry trattor osteri tavern",
    "COSA_VEDERE" to "veder visit attraz monum muse perder merit see sight attract worth miss to+do things+to+do " +
        "castell castle palazz palace chies church cattedr cathedr duomo",
    "ACQUISTI" to "acqui compr souven negoz mercat manci camerier pagar contan bancom soldi valut " +
        "shop buy market tip waiter pay cash card= money curren atm paga euro= dollar " +
        "ricord regal gift cost= costs= costo= costi= expens costa+la+vita costo+della+vita cost+of+living",
    "TRASPORTI" to "arriv spost muov treno treni autob macchin auto nolegg guidar raggiun " +
        "bus train car drive rent transp metro reach around stazion station aeroport airport " +
        "taxi tassi= tram= traghett ferry",
    "DOGANE" to "passap docum front dogan visto alcol sigar passport border custom visa alcoh cigar identit id+card",
    "SALUTE" to "vacci ammal malat medic osped salute farmac sanit sick ill doctor hospit health jab pharm pronto+soccorso first+aid " +
        "rubinett tap+water",
    "SICUREZZA" to "sicur peric derub furt borseg ladr crimin truffa truffe safe danger rob theft thief pickp crime scam",
    "CONNETTIVITA" to "internet telef cellul sim wifi collegar roami online phone mobile connec",
    "STORIA" to "stori origin nata= nato= fondat fondo= passat medioev govern antico antica antich roman guerr " +
        "histor found establ past= ancien rule= ruled ruler middl war middle+ages costru mura= built wall= ottocent novecent settecent seicent cinquecent quattrocent trecent secol centur " +
        "popol abitat inhabit settl peoples= how+old",
    "CLIMA" to "clima meteo piog piov ombrel tempera primav estat invern autun nevic neve caldo fredd stagion " +
        "climat weather rain umbrel spring summer winter= autumn snow hot cold season " +
        "periodo+migliore quando+andare conviene+andare best+time when+to+go when+to+visit gennaio febbraio marzo aprile maggio giugno luglio agosto settembre ottobre novembre dicembre mesi january february march april june july august september october november december months",
    // Non categorie ma momenti dell'anno: "Che tempo trovo a Roma a Natale?" chiede il clima di quel periodo.
    "PERIODI" to "natale= natalizi capodann pasqua ferragost christmas xmas easter new+year che+tempo",
    // Non categorie ma richieste: cosa fare contro un problema e dopo una perdita o un furto. Portano alle sottosezioni
    // pratiche delle pagine della guida generale ("Jet lag: Recupero", "Documenti: In caso di emergenza") invece della definizione.
    "CONSIGLI" to "combat evitar preven curar rimed superar affront deal cope avoid prevent cure treat overcom",
    "EMERGENZE" to "perdo perso= persa smarr rubat lose lost stolen",
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
        "PERIODI" to "clima",
        // Previsioni: finche' non arrivano nel contesto, la sezione del clima.
        "METEO" to "clima",
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
        "PERIODI" to "climate",
        "METEO" to "climate",
    ),
).mapValues { (_, byCategory) ->
    byCategory.mapValues { (_, words) -> words.split(", ").map { if (' ' in it) "\"$it\"" else it } }
}

private const val SHORT_STEM_CHARS = 4

// Previsioni del tempo ("Che tempo fa domani?", "Pioverà sabato?"): una parola di previsione, oppure una del clima
// (CLIMA, o "che tempo" di PERIODI) insieme a un momento vicino. Da sole le parole del momento non bastano ("Cosa
// vedere oggi?"). Il gruppo METEO prende il posto di CLIMA e PERIODI.
private val forecastStems = "previs forecast".split(' ')
private val nearTimeStems = ("oggi domani dopodomani stasera stanotte stamattina weekend= fine+settimana lunedi martedi " +
    "mercoledi giovedi venerdi sabato domenica prossimi+giorni settimana+prossima questa+settimana today tomorrow " +
    "tonight this+week next+week next+days monday tuesday wednesday thursday friday saturday sunday").split(' ')

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
