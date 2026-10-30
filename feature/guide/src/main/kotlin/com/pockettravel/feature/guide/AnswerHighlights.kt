package com.pockettravel.feature.guide

import java.text.Normalizer

// Sotto questa lunghezza (dopo la normalizzazione) una frase trova corrispondenze casuali: "Sì.", "Ecco.".
private const val MIN_SENTENCE_LENGTH = 12

private val sentenceBreak = Regex("""(?<=[.!?;:])\s+|\n+""")

/** Testo ridotto a lettere e cifre minuscole senza accenti, con l'indice nel testo originale di ogni carattere tenuto. */
private class Normalized(original: String) {
    val text: String
    private val originalIndex: List<Int>

    init {
        val letters = StringBuilder()
        val indexes = mutableListOf<Int>()
        original.forEachIndexed { index, char ->
            // Spazi, apici, virgolette, trattini, elenchi e grassetti del Markdown non contano: cadono con la punteggiatura.
            Normalizer.normalize(char.toString(), Normalizer.Form.NFD).lowercase().forEach { part ->
                if (part.isLetterOrDigit()) {
                    letters.append(part)
                    indexes += index
                }
            }
        }
        text = letters.toString()
        originalIndex = indexes
    }

    /** Dove [other] compare in questo testo, come intervallo del testo originale; null se non compare. */
    fun find(other: Normalized): IntRange? {
        val start = text.indexOf(other.text).takeIf { it >= 0 } ?: return null
        return originalIndex[start]..originalIndex[start + other.text.length - 1]
    }
}

/**
 * Gli intervalli di [body] (estremi compresi) che la risposta [answer] riprende: prima la risposta intera, poi le sue
 * frasi dalla più lunga, senza badare a maiuscole, accenti, spazi, apici, virgolette e trattini. Vuoto se nessuna frase compare.
 */
internal fun answerHighlights(body: String, answer: String): List<IntRange> {
    val text = Normalized(body)
    val whole = Normalized(answer)
    val wholeMatch = if (whole.text.isEmpty()) null else text.find(whole)
    if (whole.text.isEmpty() || wholeMatch != null) return listOfNotNull(wholeMatch)
    val found = answer.split(sentenceBreak)
        .map(::Normalized)
        .filter { it.text.length >= MIN_SENTENCE_LENGTH }
        .sortedByDescending { it.text.length }
        .mapNotNull(text::find)
        .sortedBy { it.first }
    return found.fold(emptyList<IntRange>()) { merged, range ->
        val last = merged.lastOrNull()
        // listOf: IntRange e' un Iterable<Int>, e "lista + intervallo" aggiungerebbe i singoli indici.
        if (last != null && range.first <= last.last + 1) merged.dropLast(1) + listOf(last.first..maxOf(last.last, range.last)) else merged + listOf(range)
    }
}
