package com.pockettravel.feature.guide

import com.pockettravel.core.data.GuideTerm
import java.text.Normalizer

private val combiningMarks = Regex("""\p{Mn}+""")

private fun foldedChar(c: Char): String = Normalizer.normalize(c.lowercase(), Normalizer.Form.NFD).replace(combiningMarks, "")

/** [text] in minuscolo e senza accenti, con per ogni carattere del risultato la sua posizione in [text]. */
private class FoldedText(text: String) {
    val folded: String
    val origin: IntArray

    init {
        val builder = StringBuilder()
        val positions = ArrayList<Int>(text.length)
        text.forEachIndexed { index, c ->
            val f = foldedChar(c)
            builder.append(f)
            repeat(f.length) { positions += index }
        }
        folded = builder.toString()
        origin = positions.toIntArray()
    }
}

/**
 * Le occorrenze dei termini di [terms] nei paragrafi [paragraphs] di una sezione, per nome o alias, come parole intere e
 * senza badare ad accenti e maiuscole: per ogni paragrafo, intervallo nel suo testo e termine. Ogni termine solo alla prima
 * occorrenza nella sezione, per non riempire il testo di collegamenti; tra nomi sovrapposti vince il più lungo.
 */
internal fun termLinks(paragraphs: List<String>, terms: List<GuideTerm>): List<List<Pair<IntRange, GuideTerm>>> {
    val names = terms.flatMap { term -> (listOf(term.term) + term.aliases).map { term to it } }
        .map { (term, name) -> term to name.map(::foldedChar).joinToString("") }
        .filter { (_, name) -> name.isNotBlank() }
        .sortedByDescending { (_, name) -> name.length }
    val linked = mutableSetOf<GuideTerm>()
    return paragraphs.map { paragraph ->
        val text = FoldedText(paragraph)
        val found = mutableListOf<Pair<IntRange, GuideTerm>>()
        for ((term, name) in names) {
            if (term in linked) continue
            fun fits(i: Int): Boolean {
                val end = i + name.length
                return text.folded.getOrNull(i - 1)?.isLetterOrDigit() != true && text.folded.getOrNull(end)?.isLetterOrDigit() != true &&
                    found.none { (range, _) -> text.origin[i] <= range.last && text.origin[end - 1] >= range.first }
            }
            var start = text.folded.indexOf(name)
            while (start >= 0 && !fits(start)) start = text.folded.indexOf(name, start + 1)
            if (start >= 0) {
                found += text.origin[start]..text.origin[start + name.length - 1] to term
                linked += term
            }
        }
        found.sortedBy { it.first.first }
    }
}
