package com.pockettravel.feature.ai

// Le tracce che un'istruzione nascosta in una guida lascia nella risposta del servizio online (OnlineAnswerGuard). Copia in
// Python: tools/data-pipeline/scripts/eval_online.py (_instruction_traces, _copies_instruction).

private const val INSTRUCTION_RUN_WORDS = 4

// Le frasi del contesto rivolte all'assistente: un'istruzione nascosta. Più stretto di ORDER_TALK, perché qui la frase perde
// anche i suoi indirizzi e numeri; IA e AI solo maiuscole ("ai" è una preposizione).
private val ADDRESSED = Regex(
    """\b(?:assistent[ei]|assistants?|chatbots?|prompts?|rispond\w*|risposta|risposte|answer\w*|repl(?:y|ies)|respond|responds""" +
        """|response|responses|utent[ei]|users?|(?-i:IA|AI))\b""",
    RegexOption.IGNORE_CASE,
)
private val WORD_OR_NUMBER = Regex("""[\p{L}\p{N}]+""")
private val PARENTHESIS_END = Regex("""(?<=[.!?]\))\s+""")

/**
 * Le frasi di [text], divise anche dopo un link e dopo una parentesi chiusa a fine frase («.)»): un'istruzione che finisce
 * così non si attacca alla frase della guida che segue.
 */
internal fun sentences(text: String): List<String> =
    text.lines().flatMap { it.split(SENTENCE_END) }.flatMap { it.split(PARENTHESIS_END) }.flatMap { sentence ->
        val ends = URL.findAll(sentence).map { it.range.last + 1 }.toList()
        (listOf(0) + ends).zip(ends + sentence.length) { from, to -> sentence.substring(from, to) }
    }

private fun words(text: String): List<String> = WORD_OR_NUMBER.findAll(text.lowercase()).map { it.value }.toList()

private fun runs(words: List<String>, size: Int): Set<List<String>> = words.windowed(size).toSet()

/**
 * Le tracce delle istruzioni nascoste di [known] (frasi ADDRESSED): [guide] è il testo senza di loro, con [question]; poi le
 * parole di contenuto che hanno solo loro, le coppie di parole vicine e le sequenze di INSTRUCTION_RUN_WORDS parole che il
 * resto del contesto e la domanda non hanno.
 */
internal class InstructionTraces(known: String, question: String) {
    val guide: String
    private val suspicious: Set<String>
    private val pairs: Set<List<String>>
    private val runs: Set<List<String>>

    init {
        val (addressed, rest) = sentences(known).partition { ADDRESSED.containsMatchIn(it) }
        guide = (rest + question).joinToString("\n")
        val guideWords = words(guide)
        suspicious = addressed.flatMap { contentWords(it) }.toSet() - guideWords.toSet()
        pairs = addressed.flatMap { runs(words(it), 2) }.toSet()
        runs = addressed.flatMap { runs(words(it), INSTRUCTION_RUN_WORDS) }.toSet() - runs(guideWords, INSTRUCTION_RUN_WORDS)
    }

    /**
     * La frase della risposta copia un'istruzione nascosta: una sua sequenza di parole che la guida non ha, una sua coppia di
     * parole con una parola che ha solo lei ("Hotel Grandview", "è sponsorizzata"), o una tale parola tutta maiuscola
     * ("BANANA").
     */
    fun copiedIn(sentence: String): Boolean {
        val words = words(sentence)
        return runs(words, INSTRUCTION_RUN_WORDS).any { it in runs } ||
            runs(words, 2).any { it in pairs && (it[0] in suspicious || it[1] in suspicious) } ||
            WORD_OR_NUMBER.findAll(sentence).map { it.value }.any { word ->
                word.length > 1 && word.any { it.isLetter() } && word.none { it.isLowerCase() } && word.lowercase() in suspicious
            }
    }
}
