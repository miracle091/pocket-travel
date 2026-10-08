package com.pockettravel.feature.ai

/** Mezzo chiesto nella frase ("a piedi", "by car"); null se la frase non lo dice. */
enum class NavigationRequestProfile { WALK, BIKE, CAR }

/**
 * "Portami al Colosseo a piedi": la destinazione da cercare nel Navigatore e il mezzo, se detto. [destinationWithTime] e' la destinazione
 * col momento del giorno che le regole le hanno tolto ("bar stasera" per "bar"), null se non c'era: puo' essere il nome
 * del luogo, e il Navigatore la cerca per prima.
 */
data class NavigationRequest(
    val destination: String,
    val profile: NavigationRequestProfile?,
    val destinationWithTime: String? = null,
)

/**
 * Riconosce una richiesta di navigazione con regole fisse, senza modello e senza rete: la frase deve
 * cominciare con un comando ("portami", "indicazioni per", "take me to"...). "Come arrivare a..." e
 * "come si arriva" restano domande per la guida (sezione "Come arrivare" di Wikivoyage), non comandi.
 */
fun parseNavigationRequest(text: String): NavigationRequest? {
    val rest = TRIGGER.find(text.trim())?.groupValues?.get(1) ?: return null
    val profileMatch = MODES.firstNotNullOfOrNull { (regex, profile) -> regex.find(rest)?.let { it to profile } }
    val withTime = (profileMatch?.let { rest.removeRange(it.first.range) } ?: rest)
        .replace(POLITENESS, " ").trim().trimEnd('?', '!', '.', ',', ';', ' ')
    val withoutTime = withTime.replace(TRAILING_TIME, "")
    val destination = withoutLeadingWords(withoutTime)
    return destination.takeIf { it.isNotEmpty() }
        ?.let { NavigationRequest(it, profileMatch?.second, withoutLeadingWords(withTime).takeIf { withoutTime != withTime }) }
}

private fun withoutLeadingWords(text: String): String {
    var destination = text
    while (true) {
        val stripped = destination.replaceFirst(LEADING_WORD, "")
        if (stripped == destination) break
        destination = stripped
    }
    return destination.trim()
}

private val TRIGGER = Regex(
    "^(?:(?:puoi|potresti|per favore|please|can you|could you)\\s+)?" +
        "(?:portami|accompagnami|come (?:arrivo|raggiungo|vado)|naviga(?:re)?|indicazioni|percorso|voglio andare|andiamo" +
        "|take me|directions|navigate|how (?:do|can) i get to|route|guide me|get me|i want to go)\\b(.*)$",
    RegexOption.IGNORE_CASE,
)

// Il mezzo con il verbo davanti, se c'e' ("vado a piedi", "I'm walking"): si toglie tutto dalla meta.
private const val MODE_VERB = "(?:,?\\s*\\b(?:vado|andiamo|vengo|i'm|i am|we're|we are)\\s+)?"

private val MODES = listOf(
    Regex("$MODE_VERB\\b(?:a piedi|camminando|on foot|walking)\\b", RegexOption.IGNORE_CASE) to NavigationRequestProfile.WALK,
    Regex("$MODE_VERB\\b(?:(?:in|con la) bici(?:cletta)?|by (?:bike|bicycle)|cycling)\\b", RegexOption.IGNORE_CASE) to NavigationRequestProfile.BIKE,
    Regex("$MODE_VERB\\b(?:in (?:auto|macchina|automobile)|con (?:la macchina|l'auto|l’auto)|by car|driving)\\b", RegexOption.IGNORE_CASE) to NavigationRequestProfile.CAR,
)

private val POLITENESS = Regex(",?\\s*\\b(?:per favore|please)\\b", RegexOption.IGNORE_CASE)

// Quando, in fondo alla destinazione: "il Colosseo domani" -> "il Colosseo",
// "the beach tomorrow morning" -> "the beach".
// Solo in minuscolo: con la maiuscola e' parte del nome ("Bar Stasera", "Café Tomorrow").
private val TRAILING_TIME = Regex(
    ",?\\s+(?:(?:dopo)?domani(?:\\s+(?:mattina|pomeriggio|sera))?|oggi(?:\\s+pomeriggio)?|domattina|stamattina|stasera|stanotte|adesso" +
        "|(?:tomorrow|today|this)(?:\\s+(?:morning|afternoon|evening|night))?|tonight|(?:right\\s+)?now)$",
)

// Preposizioni, articoli e verbi di visita davanti alla meta, tolti uno alla volta: "fino alla stazione" -> "stazione",
// "a vedere il Colosseo" -> "Colosseo". I verbi solo in minuscolo: con la maiuscola sono parte del nome ("See Hotel").
private val LEADING_WORD = Regex(
    "^\\s*(?:(?:subito|stradali|verso|fino|per|a|ad|al|allo|alla|ai|agli|alle|in|il|lo|la|i|gli|le|(?-i:vedere|visitare|see|visit)|to|towards?|for|the)(?:\\s+|$)|(?:all|dall|l)['’])",
    RegexOption.IGNORE_CASE,
)
