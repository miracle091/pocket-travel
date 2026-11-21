package com.pockettravel.feature.ai

// Le parole frequenti per lingua del controllo sulla risposta (OnlineAnswerGuard).

private val WORD = Regex("""\p{L}+""")
private val CONTENT_WORD = Regex("""\p{L}{4,}|[0-9]+""")

// Parole frequenti per lingua, come IT_WORDS, EN_WORDS e OTHER_LANG_WORDS di eval_common.py.
private fun words(text: String): Set<String> = text.split(' ').toSet()

internal val LANG_WORDS: Map<String, Set<String>> = mapOf(
    "it" to words(
        "il lo la gli le l di del dello della dei degli delle dell da dal dalla dall nel nella nei nelle nell " +
            "al alla ai alle che e è non per un una uno sono si ci c con su sul sulla tra fra cosa dove dov quando " +
            "quale quali qual quanto quanti quanta perché posso serve servono bisogna ho ha hanno essere ma o anche " +
            "più mi ti cui questo questa devo qualcosa conviene meglio",
    ),
    "en" to words(
        "the of and is are was be to for with what where when which who how can could should do does did " +
            "there it its you your my me we this that these those at on from by any about have has need an or " +
            "not if than",
    ),
    "de" to words(
        "der die das und ist nicht ein eine mit von zu den dem des auf für sie wir ich sich auch im bei nach wie " +
            "wenn oder aber wird sind haben kann können gibt",
    ),
    "fr" to words("les des du sont pas une dans pour avec que vous nous je elle cette ces aux sur mais où et"),
    "es" to words("el es los las son por para que como pero más está hay muy esto esta puede donde cuando también tiene sus"),
    "pt" to words("os dos das em não uma para que mais está tem são muito também pode onde"),
    "sw" to words("na ya wa kwa ni za katika kuwa hii huu hilo yake lakini kama hiyo vya ili hapa kuna watu"),
    "fi" to words("ja ei että oli ovat mutta kuin tai myös hän joka kun niin ole sekä jos vain kaikki tämä heidän mitä jotka kanssa yksi kaksi kaupunki"),
    "hu" to words("az és hogy nem egy meg vagy ami már csak ezt kell lehet ez nagyon város mert még minden pedig között után"),
    "nl" to words("het een ik dat niet voor zijn maar ook als aan bij naar uit nog wel wordt deze kan"),
    "pl" to words("nie się że jest jak czy tak od przez dla który tylko może być oraz miasto"),
    "ro" to words("și în cu să sunt pentru dar sau fost foarte acest această oraș este ale dintre către iar"),
    "sv" to words("och att det som är på av för med inte har om ett jag från eller också staden"),
    "tr" to words("ve bir bu için ile çok daha gibi olarak olan şehir ise değil"),
    "cs" to words("je že jako který byl byla jsou nebo také velmi město"),
    "id" to words("yang dan ini itu dengan untuk tidak dari dalam akan pada adalah juga atau ada sangat"),
    "ca" to words("els les amb és són dels als més també aquest aquesta això molt ciutat"),
)

internal fun langCounts(text: String): Map<String, Int> {
    val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
    return LANG_WORDS.mapValues { (_, known) -> words.count { it in known } }
}

internal val FREQUENT_WORDS: Set<String> = LANG_WORDS.values.flatten().toSet()

// Le parole di 4 lettere o più che non sono frequenti in nessuna lingua, e i numeri.
internal fun contentWords(text: String): Set<String> =
    CONTENT_WORD.findAll(text.lowercase()).map { it.value }.filter { it !in FREQUENT_WORDS }.toSet()

// Le lingue che la domanda può chiedere per la risposta ("in tedesco", "in German", "auf Deutsch"), con il codice di
// LANG_WORDS; OTHER_SCRIPT per quelle con un'altra scrittura.
internal const val OTHER_SCRIPT = "*"
private val LANGUAGE_NAMES: Map<String, String> = buildMap {
    fun names(code: String, names: String) = names.split(' ').forEach { put(it, code) }
    names("it", "italiano italian")
    names("en", "inglese english")
    names("de", "tedesco german deutsch")
    names("fr", "francese french français")
    names("es", "spagnolo spanish español")
    names("pt", "portoghese portuguese português")
    names("sw", "swahili")
    names("fi", "finlandese finnish")
    names("hu", "ungherese hungarian")
    names("nl", "olandese dutch")
    names("pl", "polacco polish")
    names("ro", "rumeno romeno romanian")
    names("sv", "svedese swedish")
    names("tr", "turco turkish")
    names("cs", "ceco czech")
    names("id", "indonesiano indonesian")
    names("ca", "catalano catalan")
    names(
        OTHER_SCRIPT,
        "giapponese japanese cinese chinese coreano korean russo russian ucraino ukrainian greco greek arabo arabic " +
            "ebraico hebrew hindi thai tailandese",
    )
}
private val LANGUAGE_REQUEST = Regex("""\b(?:in|into|auf|en)\s+(\p{L}+)""")

internal fun requestedLanguages(question: String): Set<String> =
    LANGUAGE_REQUEST.findAll(question.lowercase()).mapNotNull { LANGUAGE_NAMES[it.groupValues[1]] }.toSet()
