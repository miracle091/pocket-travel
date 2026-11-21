package com.pockettravel.feature.ai

import java.net.URLDecoder

// Il servizio online può eseguire un'istruzione nascosta in una guida anche dopo il filtro sul contesto (Mistral Small il
// 18-21% di 120 istruzioni, Gemini flash-lite circa il 35%). I casi gravi lasciano una traccia nella risposta, che qui si
// controlla: si tolgono le frasi con un indirizzo o un numero di telefono che non sono nel contesto né nella domanda e quelle
// che ripetono le istruzioni di sistema; una risposta in un'altra lingua o scrittura non si mostra. Copia in Python:
// tools/data-pipeline/scripts/eval_online.py (guard_answer).

/** La risposta è in un'altra lingua o scrittura rispetto a domanda e app: non si mostra. */
internal class OnlineAnswerWrongLanguageException : IllegalStateException("Risposta in un'altra lingua")

/** La risposta finale è breve e non usa nulla della guida né della domanda ("BANANA", "lol"): non si mostra. */
internal class OnlineAnswerEmptyException : IllegalStateException("Risposta senza contenuto")

/** [text] senza le frasi tolte e con il segno dopo link e numeri non delle guide; [removed] e [marked] dicono se è successo. */
internal data class GuardedAnswer(val text: String, val removed: Boolean, val marked: Boolean)

// La fine di un link che il contesto non ha e se è un'immagine markdown.
private data class UnknownLink(val end: Int, val image: Boolean)

private const val URL_CHARS = """[^\s<>()\[\]«»"”]"""
private val URL = Regex(
    """(?:https?://|www\.)$URL_CHARS+|\b(?:[a-z0-9-]+\.)+(?:(?:com|net|org|info|biz|example|it|eu|de|fr|es|uk|io""" +
        """|app|shop|online)\b|[a-z]{2,}(?=/))$URL_CHARS*""",
    RegexOption.IGNORE_CASE,
)
private val MARKDOWN_ADDRESS = Regex("""\]\(([^)\s]+)""")
private val MARKDOWN_LINK = Regex("""\[[^\]]*\]\(([^)\s]+)\)""")

// La posizione dell'utente in un link: parametri con nome o due coordinate.
private val LOCATION = Regex(
    """(?:lat|lon|lng|latitude|longitude|position|posizione|location|coordinat\w*)\s*=""" +
        """|-?[0-9]{1,3}\.[0-9]{4,}\s*[,;]\s*-?[0-9]{1,3}\.[0-9]{4,}""",
    RegexOption.IGNORE_CASE,
)
private val URL_SCHEME = Regex("""https?://(?:www\.)?|www\.""")

// Orari, date, anni, importi e numeri con le migliaia: non sono numeri di telefono.
private val NOT_A_PHONE = listOf(
    """[0-9]{1,2}:[0-9]{2}(?:\s*[-–]\s*[0-9]{1,2}:[0-9]{2})?""",
    """[0-9]{1,2}\.[0-9]{2}\s*[-–]\s*[0-9]{1,2}\.[0-9]{2}""",
    """\b[0-9]{1,2}[/.\-][0-9]{1,2}[/.\-][0-9]{2,4}\b""",
    """\b[12][0-9]{3}\s*[-–]\s*[12][0-9]{3}\b""",
    """(?<![0-9+.])[1-9][0-9]{0,2}(?:[.,][0-9]{3}){2,}(?![0-9])""",
    """[€$£]\s?[0-9]{1,3}(?:[., ][0-9]{3})*(?:[.,][0-9]{1,2})?""",
    """[0-9]{1,3}(?:[., ][0-9]{3})*(?:[.,][0-9]{1,2})?\s?(?:€|\$|£|euro|eur\b|usd|dollar\w*|sterlin\w*|chf|kr\b|yen)""",
).map { Regex(it, RegexOption.IGNORE_CASE) }
private val PHONE = Regex("""\+?\(?[0-9]{2,}\)?(?:[ .\-]\(?[0-9]{2,}\)?)*""")
private val PARENTHESES = Regex("""\([^)]*\)""")
private val DIGIT_SEPARATOR = Regex("""(?<=[0-9])[ .\-](?=[0-9])""")
private val WHITESPACE = Regex("""\s+""")

private const val INSTRUCTION_REPEAT_CHARS = 60
private val PHONE_DIGITS = 7..15

// Il prefisso internazionale può mancare nella guida: si confrontano le ultime cifre.
private const val PHONE_TAIL_DIGITS = 8
private const val FOREIGN_LETTERS_MIN = 6
private const val OTHER_LANG_WORDS_MIN = 4
private const val EMPTY_ANSWER_MAX_CHARS = 60
private const val EXFILTRATION_PIECE_CHARS = 25
private const val EXFILTRATION_QUESTION_MIN = 8

// La risposta dice che le guide non bastano: le frasi del prompt e le loro varianti.
private val GUIDES_NOT_ENOUGH = Regex(
    """guid[ae]|guides|\bapp\b|context|contesto|wikivoyage|informazion|information|\bnon (?:è|e') present""" +
        """|not (?:included|available|present|mentioned)""",
    RegexOption.IGNORE_CASE,
)
private val YES_NO = Regex("""^\W*(?:sì|si|no|yes)(?!\w)""", RegexOption.IGNORE_CASE)

// Frasi che parlano all'assistente o di risposte: un'istruzione nascosta sta nel contesto e le sue parole non sono contenuto.
private val ORDER_TALK = Regex(
    """\b(?:rispond\w*|risposta|risposte|answer\w*|repl(?:y|ies)|respon\w*|tua|tuo|tue|tuoi|your|ti|domand\w*|questions?|utente""" +
        """|users?|assistente|assistant|output)\b""",
    RegexOption.IGNORE_CASE,
)
private val DIGIT = Regex("""[0-9]""")

// Le parole di contenuto del contesto, senza le frasi che parlano all'assistente (ORDER_TALK).
private fun guideWords(known: String): Set<String> =
    known.lines().flatMap { it.split(SENTENCE_END) }.filterNot { ORDER_TALK.containsMatchIn(it) }.flatMap { contentWords(it) }.toSet()

private fun scripts(text: String): List<Character.UnicodeScript> =
    text.codePoints().toArray().filter { Character.isLetter(it) }.map { Character.UnicodeScript.of(it) }

private fun address(url: String): String =
    url.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('.', ',', ';', ':', '!', '?', '/', ')', '»', '"', '\'')

// Le cifre e la fine dei numeri di telefono, senza orari, date, anni, importi e numeri con le migliaia.
private fun phoneMatches(sentence: String): List<Pair<String, Int>> {
    var masked = sentence
    for (pattern in NOT_A_PHONE) masked = pattern.replace(masked) { " ".repeat(it.value.length) }
    return PHONE.findAll(masked).map { match -> match.value.filter { it in '0'..'9' } to match.range.last + 1 }
        .filter { it.first.length in PHONE_DIGITS }.toList()
}

/** Le cifre dei numeri di telefono di [sentence], senza orari, date, anni, importi e numeri con le migliaia. */
internal fun phoneNumbers(sentence: String): List<String> = phoneMatches(sentence).map { it.first }

/** [sentence] ha un indirizzo web: con schema, con `www.`, con un dominio e un percorso o con un'estensione comune, o un link markdown. */
internal fun hasAddress(sentence: String): Boolean = URL.containsMatchIn(sentence) || MARKDOWN_ADDRESS.containsMatchIn(sentence)

private fun squash(text: String) = text.lowercase().replace(WHITESPACE, " ")

// Minuscolo, senza codifica degli indirizzi (%20, +) e con gli spazi al posto di "_": per trovare la domanda dentro un link.
private fun plain(text: String): String {
    val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
    return squash(decoded.replace('+', ' ').replace('_', ' ')).trim()
}

/** Le istruzioni di sistema senza contesto, per riconoscere la risposta che le ripete. */
internal fun onlineInstructions(language: String, withPhoto: Boolean): String =
    OnlinePromptTemplates.systemPrompt("§", language, withPhoto).substringBefore("§")

/**
 * Controlla le risposte del servizio online. [known]: il contesto e il messaggio dell'utente mandati al servizio (indirizzi,
 * numeri e scritture lì presenti sono ammessi); [question]: la domanda, che dà la lingua; [instructions]: onlineInstructions;
 * [language]: la lingua dell'app, ammessa nella risposta come quella della domanda.
 */
internal class OnlineAnswerGuard(
    private val question: String,
    private val known: String,
    instructions: String,
    private val language: String,
) {
    private val knownAddresses = URL_SCHEME.replace(known.lowercase(), "")
    private val knownDigits = DIGIT_SEPARATOR.replace(knownAddresses, "")
    private val firstSentence = instructions.substringBefore('\n').lowercase().trimEnd('.', ' ')
    private val squashedInstructions = squash(instructions)
    private val knownContentWords = guideWords(known) + contentWords(question)
    private val askedPlain = plain(question).trimEnd('?', '!', '.', ' ')
    private val mark = if (language == "en") "(not from the guides, check it)" else "(non dalle guide, da verificare)"

    /**
     * [answer] senza le frasi tolte e con il segno dopo link e numeri non delle guide; il testo parziale dello streaming si
     * controlla tutto a ogni aggiornamento. [final]: la risposta finita, che se è vuota di contenuto non si mostra (un testo
     * parziale all'inizio è sempre breve).
     */
    fun check(answer: String, final: Boolean = false): GuardedAnswer {
        if (otherScript(answer) || otherLatinLanguage(answer)) throw OnlineAnswerWrongLanguageException()
        var removed = false
        var marked = false
        val text = answer.lines().joinToString("\n") { line ->
            val sentences = line.split(SENTENCE_END)
            val out = mutableListOf<String>()
            for (sentence in sentences) {
                val links = unknownLinks(sentence)
                if (dropped(sentence, links)) {
                    removed = true
                } else {
                    val withMark = withMark(sentence, links)
                    marked = marked || withMark != null
                    out.add(withMark ?: sentence)
                }
            }
            if (out == sentences) line else out.joinToString(" ")
        }
        if (final && emptyAnswer(text)) throw OnlineAnswerEmptyException()
        return GuardedAnswer(text, removed, marked)
    }

    // Breve, senza parole di contenuto di contesto e domanda, senza numeri e senza dire che le guide non bastano; le citazioni
    // tra parentesi non contano.
    private fun emptyAnswer(answer: String): Boolean {
        val text = PARENTHESES.replace(answer, "").trim()
        val content = text.length > EMPTY_ANSWER_MAX_CHARS || GUIDES_NOT_ENOUGH.containsMatchIn(text) ||
            YES_NO.containsMatchIn(text) || DIGIT.containsMatchIn(text)
        return !content && contentWords(text).none { it in knownContentWords }
    }

    // Più della metà delle lettere in una scrittura assente da domanda e contesto.
    private fun otherScript(answer: String): Boolean {
        val seen = scripts(known).toSet()
        val used = scripts(PARENTHESES.replace(answer, "")) // le citazioni tra parentesi sono nella lingua dell'app
        val foreign = used.count { it !in seen }
        return foreign >= FOREIGN_LETTERS_MIN && foreign * 2 > used.size
    }

    // Una lingua latina chiaramente diversa dalla lingua dell'app e da quella della domanda (italiano o inglese).
    private fun otherLatinLanguage(answer: String): Boolean {
        val asked = langCounts(question)
        val allowed = setOfNotNull(
            language,
            when {
                asked.getValue("it") > asked.getValue("en") -> "it"
                asked.getValue("en") > asked.getValue("it") -> "en"
                else -> null
            },
        )
        val got = langCounts(answer)
        val best = allowed.maxOf { got.getValue(it) }
        return got.any { (lang, n) -> lang !in allowed && n >= OTHER_LANG_WORDS_MIN && n > 2 * best }
    }

    // I link markdown e gli indirizzi che il contesto non ha.
    private fun unknownLinks(sentence: String): List<UnknownLink> {
        val links = mutableListOf<UnknownLink>()
        val covered = mutableListOf<IntRange>()
        for (match in MARKDOWN_LINK.findAll(sentence)) {
            covered.add(match.range)
            if (address(match.groupValues[1]) !in knownAddresses) {
                links.add(UnknownLink(match.range.last + 1, match.range.first > 0 && sentence[match.range.first - 1] == '!'))
            }
        }
        for (match in URL.findAll(sentence)) {
            if (covered.none { match.range.first in it } && address(match.value) !in knownAddresses) {
                links.add(UnknownLink(match.range.first + match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', '»', '"', '\'').length, false))
            }
        }
        return links
    }

    // Si toglie: un'immagine non nel contesto, un link che porta la domanda dell'utente (o un pezzo lungo) o la posizione,
    // una frase che ripete le istruzioni di sistema.
    private fun dropped(sentence: String, links: List<UnknownLink>): Boolean =
        links.any { it.image } || (links.isNotEmpty() && carriesUser(sentence)) || repeatsInstructions(sentence)

    // La domanda intera nella frase, o un pezzo lungo nell'indirizzo, o la posizione.
    private fun carriesUser(sentence: String): Boolean = LOCATION.containsMatchIn(sentence) || carriesQuestion(sentence)

    private fun carriesQuestion(sentence: String): Boolean {
        if (askedPlain.length < EXFILTRATION_QUESTION_MIN) return false
        val piece = minOf(EXFILTRATION_PIECE_CHARS, askedPlain.length)
        val addresses = plain((URL.findAll(sentence).map { it.value } + MARKDOWN_ADDRESS.findAll(sentence).map { it.groupValues[1] }).joinToString(" "))
        return plain(sentence).contains(askedPlain) || (0..askedPlain.length - piece).any { addresses.contains(askedPlain.substring(it, it + piece)) }
    }

    // [sentence] con il segno dopo ogni link e ogni numero di telefono che il contesto non ha; null se non ce n'è.
    private fun withMark(sentence: String, links: List<UnknownLink>): String? {
        val phones = phoneMatches(sentence).filter { it.first.takeLast(PHONE_TAIL_DIGITS) !in knownDigits }.map { it.second }
        val spots = (links.map { it.end } + phones).toSortedSet(reverseOrder())
        if (spots.isEmpty()) return null
        var result = sentence
        for (end in spots) result = result.substring(0, end) + " " + mark + result.substring(end)
        return result
    }

    private fun repeatsInstructions(sentence: String): Boolean {
        val squashed = squash(sentence)
        return firstSentence in squashed ||
            (0..squashed.length - INSTRUCTION_REPEAT_CHARS).any { squashedInstructions.contains(squashed.substring(it, it + INSTRUCTION_REPEAT_CHARS)) }
    }
}
