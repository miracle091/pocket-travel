package com.pockettravel.feature.ai

/** Mezzo chiesto nella frase ("a piedi", "by car"); null se la frase non lo dice. */
enum class NavigationRequestMode { WALK, BIKE, CAR }

/** "Portami al Colosseo a piedi": la meta da cercare nel Navigatore e il mezzo, se detto. */
data class NavigationRequest(val destination: String, val mode: NavigationRequestMode?)

/**
 * Riconosce una richiesta di navigazione con regole fisse, senza modello e senza rete: la frase deve
 * cominciare con un comando ("portami", "indicazioni per", "take me to"...). "Come arrivare a..." e
 * "come si arriva" restano domande per la guida (sezione "Come arrivare" di Wikivoyage), non comandi.
 */
fun parseNavigationRequest(text: String): NavigationRequest? {
    val rest = TRIGGER.find(text.trim())?.groupValues?.get(1) ?: return null
    val modeMatch = MODES.firstNotNullOfOrNull { (regex, mode) -> regex.find(rest)?.let { it to mode } }
    var destination = modeMatch?.let { rest.removeRange(it.first.range) } ?: rest
    destination = destination.replace(POLITENESS, " ").trim().trimEnd('?', '!', '.', ',', ';', ' ').trim()
    while (true) {
        val stripped = destination.replaceFirst(LEADING_WORD, "")
        if (stripped == destination) break
        destination = stripped
    }
    destination = destination.trim()
    return if (destination.isEmpty()) null else NavigationRequest(destination, modeMatch?.second)
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
    Regex("$MODE_VERB\\b(?:a piedi|camminando|on foot|walking)\\b", RegexOption.IGNORE_CASE) to NavigationRequestMode.WALK,
    Regex("$MODE_VERB\\b(?:(?:in|con la) bici(?:cletta)?|by (?:bike|bicycle)|cycling)\\b", RegexOption.IGNORE_CASE) to NavigationRequestMode.BIKE,
    Regex("$MODE_VERB\\b(?:in (?:auto|macchina|automobile)|con (?:la macchina|l'auto|l’auto)|by car|driving)\\b", RegexOption.IGNORE_CASE) to NavigationRequestMode.CAR,
)

private val POLITENESS = Regex(",?\\s*\\b(?:per favore|please)\\b", RegexOption.IGNORE_CASE)

// Preposizioni e articoli davanti alla meta, tolti uno alla volta: "fino alla stazione" -> "stazione".
private val LEADING_WORD = Regex(
    "^\\s*(?:(?:subito|stradali|verso|fino|per|a|ad|al|allo|alla|ai|agli|alle|in|il|lo|la|i|gli|le|to|towards?|for|the)(?:\\s+|$)|(?:all|dall|l)['’])",
    RegexOption.IGNORE_CASE,
)
