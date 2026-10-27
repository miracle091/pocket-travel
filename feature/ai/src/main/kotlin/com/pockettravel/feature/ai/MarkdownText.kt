package com.pockettravel.feature.ai

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString

private val HEADING = Regex("""^\s{0,3}#{1,6}\s+""")
private val BULLET = Regex("""^(\s*)[*+-]\s+""")

/**
 * Testo di una risposta senza i segni Markdown piu' comuni: grassetto e corsivo diventano stili,
 * titoli e punti elenco perdono il segno (l'elenco diventa "• "), i backtick spariscono.
 * Il testo semplice, per esempio per la lettura ad alta voce, e' [AnnotatedString.text].
 * I segni non chiusi restano come sono.
 */
internal fun markdownToDisplayText(text: String): AnnotatedString = buildAnnotatedString {
    text.split("\n").forEachIndexed { index, rawLine ->
        if (index > 0) append('\n')
        val line = when {
            HEADING.containsMatchIn(rawLine) -> rawLine.replace(HEADING, "")
            BULLET.containsMatchIn(rawLine) -> rawLine.replace(BULLET, "$1• ")
            else -> rawLine
        }
        appendInline(line)
    }
}

private fun AnnotatedString.Builder.appendInline(s: String) {
    var i = 0
    while (i < s.length) {
        val next = appendSpanAt(s, i)
        if (next > i) {
            i = next
        } else {
            append(s[i])
            i++
        }
    }
}

// Aggiunge il tratto (codice, grassetto o corsivo) che inizia in [i] e restituisce dove riprendere, o [i] se non c'e'.
private fun AnnotatedString.Builder.appendSpanAt(s: String, i: Int): Int = when (val c = s[i]) {
    '`' -> {
        val close = s.indexOf('`', i + 1)
        if (close > i + 1) append(s.substring(i + 1, close))
        if (close > i + 1) close + 1 else i
    }
    '*', '_' -> appendEmphasisAt(s, i, c)
    else -> i
}

private fun AnnotatedString.Builder.appendEmphasisAt(s: String, i: Int, c: Char): Int {
    // Prima il segno doppio (grassetto), poi quello singolo (corsivo).
    val marker = if (s.startsWith("$c$c", i) && closingIndex(s, i, "$c$c") > 0) "$c$c" else c.toString()
    val end = closingIndex(s, i, marker)
    if (end >= 0) {
        pushStyle(if (marker.length == 2) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(fontStyle = FontStyle.Italic))
        appendInline(s.substring(i + marker.length, end))
        pop()
    }
    return if (end >= 0) end + marker.length else i
}

private fun canOpen(s: String, open: Int, marker: String): Boolean {
    val contentStart = open + marker.length
    if (contentStart >= s.length || s[contentStart].isWhitespace()) return false
    // Il trattino basso dentro una parola (snake_case) non apre nulla.
    return marker[0] != '_' || open == 0 || !s[open - 1].isLetterOrDigit()
}

// Posizione del segno di chiusura di quello che si apre in [open], o -1 se non e' un'apertura valida.
private fun closingIndex(s: String, open: Int, marker: String): Int {
    val c = marker[0]
    val first = s.indexOf(marker, open + marker.length + 1)
    return if (!canOpen(s, open, marker)) {
        -1
    } else {
        generateSequence(first.takeIf { it > 0 }) { j -> s.indexOf(marker, j + 1).takeIf { it > 0 } }
            .firstOrNull { j ->
                val after = j + marker.length
                !s[j - 1].isWhitespace() &&
                    (marker.length == 2 || s.getOrNull(after) != c) &&
                    (c != '_' || s.getOrNull(after)?.isLetterOrDigit() != true)
            } ?: -1
    }
}
