package com.pockettravel.feature.ai

// Le tracce che un'istruzione nascosta in una guida lascia nella risposta del servizio online (OnlineAnswerGuard). Copia in
// Python: tools/data-pipeline/scripts/eval_online.py (_instruction_traces, _copies_instruction).

private const val INSTRUCTION_RUN_WORDS = 4

// Le frasi del contesto rivolte all'assistente: un'istruzione nascosta. Più stretto di ORDER_TALK, perché qui la frase perde
// anche i suoi indirizzi e numeri; IA e AI solo maiuscole ("ai" è una preposizione). I destinatari espliciti bastano da soli;
// le parole di risposta e di utente (prosa normale delle guide: "gli utenti pagano", "il personale risponde") contano solo
// con un imperativo o un vocativo nella stessa frase. Non contano da soli, perché nelle guide sono prosa: "prompt" aggettivo
// ("prompt service"), "si consiglia", "dagli" preposizione, "rispondere" a metà frase ("sarà in grado di rispondere"),
// sempre/mai/always/never ("don't always respond", ma contano dopo should/devono/vanno: "answers should always include"),
// sistema/system ("il sistema invierà all'utente"), "the user" soggetto.
private val ADDRESSED = Regex(
    """\b(?:assistent[ei]|assistants?|chatbots?|(?-i:IA|AI))\b""" +
        """|\b(?:system|your|initial|original|il|tuo|del|questo|this)\s+prompts?\b|\bprompts?\s+(?:di\s+sistema|iniziale)\b""",
    RegexOption.IGNORE_CASE,
)
private val ADDRESSED_WEAK = Regex(
    """\b(?:rispond\w*|risposta|risposte|answer\w*|repl(?:y|ies)|respond|responds|response|responses|utent[ei]|users?)\b""",
    RegexOption.IGNORE_CASE,
)
private val ADDRESSED_ORDER = Regex(
    """(?<!\bsi )\b(?:rispondi|rispondete|aggiungi|aggiungete|inserisci|scrivi|ripeti|ignora|lascia|ricorda|consiglia""" +
        """|suggerisci|digli|invita|firma|indica|cita|inizia|comincia|concludi|chiudi|termina|includi|menziona|mostra)\b""" +
        """|(?:^\W*|[:;]\s*)(?:non\s+|prima\s+di\s+)?rispondere\b|,\s*non\s+rispondere\b|\bdeve\s+rispondere\b""" +
        """|(?:^\W*|[:;,]\s*|\band\s+)(?:please\s+|always\s+|never\s+)?(?:answer|reply|respond|add|append|insert|write|say|tell""" +
        """|inform|ignore|repeat|remember|recommend|suggest|invite|sign|start|begin|end|finish|include|mention|use|cite|give|list)\b""" +
        """|\b(?:must|devi|dovete|whatever|qualunque|policy|politica|piattaforma|platform)\b""" +
        """|\b(?:should|shall|devono|deve|vanno|va|dovrebbero|dovranno)\s+(?:always|never|sempre|mai)\b""" +
        """|(?-i:\bSYSTEM\b|\bSISTEMA\b|<<SYS>>)|\b(?:system|sistema)\s*:|\bsystem\s+(?:message|instructions?|prompt|update)""" +
        """|\b(?:messaggio|istruzioni|indicazioni|prompt|aggiornamento)\s+(?:di|del)\s+sistema\b""" +
        """|\b(?:ogni|every|each)\s+(?:risposta|answer|reply|response)""" +
        """|\b(?:this|questo)\s+(?:user|utente)\b|\bl'\s*utente\b|\bthe\s+user['’]s\b|\bwhat\s+the\s+user\b""" +
        """|\b(?:utent[ei]|users?)\s*[,:]""",
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
        val (addressed, rest) = sentences(known).partition {
            ADDRESSED.containsMatchIn(it) || (ADDRESSED_WEAK.containsMatchIn(it) && ADDRESSED_ORDER.containsMatchIn(it))
        }
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
