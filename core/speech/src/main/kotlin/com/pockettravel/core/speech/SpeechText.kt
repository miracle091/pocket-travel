package com.pockettravel.core.speech

private val HEADING = Regex("""^#+\s*""")
private val BULLET = Regex("""^(?:[-*•]|\d+[.)])\s+""")
private val EMPHASIS = Regex("""\*{1,2}|`""")
private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val SENTENCE_END = Regex("""(?<=[.!?…])\s+""")
private const val PAUSE_PUNCTUATION = ".!?…:;,"

/**
 * Il testo come lo legge la voce, in pezzi di al massimo [maxLength] caratteri (il limite del motore) divisi tra una
 * frase e l'altra. Toglie i segni di formattazione che la voce pronuncerebbe (asterischi, cancelletti dei titoli,
 * trattini degli elenchi, link Markdown) e mette un punto in fondo alle righe che non ne hanno uno, perche' la voce faccia
 * una pausa tra le voci di un elenco invece di leggerle di seguito.
 */
fun speechChunks(text: String, maxLength: Int): List<String> {
    val clean = text.lines()
        .map { line -> line.trim().replace(HEADING, "").replace(BULLET, "").replace(LINK, "$1").replace(EMPHASIS, "").trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ") { if (it.last() in PAUSE_PUNCTUATION) it else "$it." }
    val chunks = mutableListOf<String>()
    var current = StringBuilder()
    for (sentence in clean.split(SENTENCE_END).flatMap { it.pieces(maxLength) }) {
        if (current.isNotEmpty() && current.length + 1 + sentence.length > maxLength) {
            chunks += current.toString()
            current = StringBuilder()
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(sentence)
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks
}

// Una frase piu' lunga del limite si divide all'ultimo spazio prima del limite (o al limite, senza spazi).
private fun String.pieces(maxLength: Int): List<String> {
    val pieces = mutableListOf<String>()
    var rest = this
    while (rest.length > maxLength) {
        val cut = rest.lastIndexOf(' ', maxLength).takeIf { it > 0 } ?: maxLength
        pieces += rest.substring(0, cut)
        rest = rest.substring(cut).trimStart()
    }
    if (rest.isNotEmpty()) pieces += rest
    return pieces
}
