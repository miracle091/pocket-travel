package com.pockettravel.feature.ai

/**
 * Le liste di parole dell'assistente, da resources/pockettravel/word_lists.txt: lo stesso file che leggono gli script
 * Python (tools/data-pipeline/scripts/word_lists.py), cosi' app e misura non hanno copie da tenere uguali. Righe "#":
 * commenti; ogni sezione comincia con [nome] e le sue righe si uniscono con uno spazio, salvo [history-climate].
 */
internal object WordLists {
    private val sections: Map<String, List<String>> = parseWordLists(
        checkNotNull(WordLists::class.java.getResourceAsStream(PATH)) { "$PATH mancante" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() },
    )

    private fun words(name: String): List<String> =
        checkNotNull(sections[name]) { "sezione [$name] mancante in $PATH" }.joinToString(" ").split(' ')

    /** Parole della domanda (senza accenti) che compaiono in quasi ogni sezione e non dicono di cosa si parla. */
    val questionStopwords: Set<String> = words("stopwords").toSet()

    /** Radici che indicano la categoria, per gruppo (questionGroups). */
    val categoryTriggers: Map<String, List<String>> = sections.keys
        .filter { it.startsWith("triggers ") }
        .associate { it.removePrefix("triggers ") to words(it) }

    /** Parole dei titoli delle guide per lingua e gruppo, come nel file ("get in", "allogg*"). */
    val categoryGuideWords: Map<String, Map<String, List<String>>> = sections
        .filterKeys { it.startsWith("guide ") }
        .entries
        .groupBy({ it.key.split(' ')[1] }) { (name, lines) -> name.split(' ')[2] to lines.joinToString(" ").split(", ") }
        .mapValues { (_, pairs) -> pairs.toMap() }

    val forecastStems: List<String> = words("forecast")
    val nearTimeStems: List<String> = words("near-time")

    /** Parole di storia o di clima, a inizio parola ("rain" non deve trovare "train"). */
    val historyClimateWords: Regex = Regex(
        checkNotNull(sections["history-climate"]) { "sezione [history-climate] mancante in $PATH" }.joinToString(""),
        RegexOption.IGNORE_CASE,
    )

    private const val PATH = "/pockettravel/word_lists.txt"
}

/** {nome della sezione: righe}, nell'ordine del file. */
internal fun parseWordLists(text: String): Map<String, List<String>> {
    val result = linkedMapOf<String, MutableList<String>>()
    var current: MutableList<String>? = null
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.forEach { line ->
        if (line.startsWith('[') && line.endsWith(']')) {
            current = mutableListOf<String>().also { result[line.substring(1, line.length - 1)] = it }
        } else {
            requireNotNull(current) { "riga fuori da una sezione: $line" } += line
        }
    }
    return result
}
