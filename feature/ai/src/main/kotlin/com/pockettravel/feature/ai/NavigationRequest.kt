package com.pockettravel.feature.ai

/** Modo chiesto nella frase ("a piedi", "by car"); null se la frase non lo dice. */
enum class NavigationRequestProfile { WALK, BIKE, CAR }

/**
 * "Portami al Colosseo a piedi": la destinazione da cercare nel Navigatore e il modo, se detto. [destinationWithTime] e' la destinazione
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
    val trigger = TRIGGER.find(text.trim()) ?: return null
    val rest = trigger.groupValues[2]
    val profileMatch = MODES.firstNotNullOfOrNull { (regex, profile) -> regex.find(rest)?.let { it to profile } }
    val withTime = (profileMatch?.let { rest.removeRange(it.first.range) } ?: rest)
        .replace(POLITENESS, " ").trim().trimEnd('?', '!', '.', ',', ';', ' ')
    val withoutTime = withTime.replace(TRAILING_TIME, "")
    // "Percorso consigliato di 3 giorni a Roma?", "Route 66 è bella?": questi trigger sono anche parole comuni, e
    // valgono solo con una preposizione di destinazione dopo ("percorso per", "route to"), anche dopo il modo.
    // "Indicazioni per ottenere il visto", "percorso per 3 giorni": dopo la preposizione non c'e' un luogo.
    val destination = withoutLeadingWords(withoutTime).takeUnless { trigger.groupValues[1].isNotEmpty() && !isDestination(withTime) }
    return destination?.takeIf { it.isNotEmpty() }
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
        "(?:portami|accompagnami|come (?:arrivo|raggiungo|vado)|voglio andare|andiamo" +
        "|take me|how (?:do|can) i get to|guide me|get me|i want to go" +
        // Trigger che sono anche parole comuni: gruppo 1, controllati con DESTINATION_START.
        "|(naviga(?:re)?|indicazioni|percorso|directions|navigate|route))\\b(.*)$",
    RegexOption.IGNORE_CASE,
)

// Inizio della destinazione dopo un trigger ambiguo: "per", "verso", "alla", "to", "towards"; "stradali" puo' precedere.
private val DESTINATION_START = Regex(
    "^(?:stradali\\s+)?(?:(?:verso|fino|per|a|ad|al|allo|alla|ai|agli|alle|to|towards?|for)\\b|(?:all|dall)['’])",
    RegexOption.IGNORE_CASE,
)

private fun isDestination(text: String) = DESTINATION_START.containsMatchIn(text) && !NOT_A_PLACE.containsMatchIn(text)

// Dopo "per"/"for"/"to": un verbo che non porta a un luogo ("per ottenere", "to get a visa") o una durata ("per 3 giorni",
// "for 3 days"). Elenco chiuso, non la desinenza dell'infinito: "per Torre del Greco", "per Sestriere" sono luoghi, e i verbi
// di movimento ("per arrivare a Roma") li toglie LEADING_WORD.
private val NOT_A_PLACE = Regex(
    "^(?:stradali\\s+)?(?:per\\s+(?:(?:ottenere|fare|richiedere|rinnovare|prenotare|comprare|acquistare|pagare|trovare|cercare" +
        "|chiedere|preparare|cucinare|compilare|scaricare|usare|utilizzare|aprire|attivare|cambiare|convertire|iscriversi" +
        "|registrarsi|evitare|organizzare|pianificare)\\b|\\d+\\s+(?:giorn|ore|ora|settiman|nott|mes)\\p{L}*)" +
        "|for\\s+\\d+\\s+(?:day|hour|week|night|month)s?\\b" +
        "|to\\s+(?:get(?!\\s+to\\b)|obtain|apply|renew|find|buy|book|make|do|pay|use|cook|plan|avoid|register|change|exchange)\\b)",
    RegexOption.IGNORE_CASE,
)

// Il modo con il verbo davanti, se c'e' ("vado a piedi", "I'm walking"): si toglie tutto dalla destinazione.
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

// Preposizioni, articoli e verbi di visita davanti alla destinazione, tolti uno alla volta: "fino alla stazione" -> "stazione",
// "a vedere il Colosseo" -> "Colosseo", "per arrivare a Roma" -> "Roma". I verbi solo in minuscolo: con la maiuscola sono parte
// del nome ("See Hotel").
private val LEADING_WORD = Regex(
    "^\\s*(?:(?:subito|stradali|verso|fino|per|a|ad|al|allo|alla|ai|agli|alle|in|il|lo|la|i|gli|le|(?-i:vedere|visitare|arrivare|andare|raggiungere|tornare|recarsi|see|visit|reach|get(?=\\s+to\\b))|to|towards?|for|the)(?:\\s+|$)|(?:all|dall|l)['’])",
    RegexOption.IGNORE_CASE,
)
