package com.pockettravel.feature.ai

// Link markdown a meta' ("[Mus", "[Museo]", "[Museo](https://x.it/a"): dalla "[" alla fine della riga.
private val OPEN_LINK = Regex("""\[[^\[\]]*(?:\](?:\([^)]*)?)?\z""")

// Indirizzo nudo non ancora seguito da uno spazio; non dentro "(...)" di un link, che chiude con la parentesi.
private val OPEN_URL = Regex("""(?<!\()https?://\S*\z""")

/**
 * Il testo parziale di una risposta senza il markup scritto a meta': un link non chiuso, un indirizzo che sta
 * ancora arrivando, tolti dall'apertura alla fine; di un grassetto o corsivo aperto ("**Roma") si toglie solo il segno.
 * In caso di dubbio non si taglia. Solo per il testo che si mostra mentre arriva: la risposta finale e il testo grezzo
 * restano interi.
 */
internal fun withoutPartialMarkup(text: String): String {
    val lineStart = text.lastIndexOf('\n') + 1
    var line = text.substring(lineStart)
    line = OPEN_LINK.find(line)?.let { line.substring(0, it.range.first) } ?: line
    line = OPEN_URL.find(line)?.let { line.substring(0, it.range.first) } ?: line
    line = emphasisOpener(line)?.let { line.removeRange(it) } ?: line
    return text.substring(0, lineStart) + line
}

// Il segno che apre un grassetto o corsivo senza chiusura nella riga, il primo se ce n'e' piu' d'uno.
private fun emphasisOpener(line: String): IntRange? {
    val openers = listOf("**", "__").mapNotNull { marker ->
        line.lastIndexOf(marker).takeIf { line.split(marker).size % 2 == 0 && it >= 0 && isOpener(line, it, marker.length) }
            ?.let { it until it + marker.length }
    }
    // Un asterisco solo: non quelli dei doppi.
    val singles = line.indices.filter { line[it] == '*' && line.getOrNull(it - 1) != '*' && line.getOrNull(it + 1) != '*' }
    val single = singles.lastOrNull()?.takeIf { singles.size % 2 == 1 && isOpener(line, it, 1) }?.let { it..it }
    return (openers + listOfNotNull(single)).minByOrNull { it.first }
}

// Apre un'enfasi se segue una parola e non e' attaccato a una prima ("a*b", "5 * 3" e "* elenco" no).
private fun isOpener(line: String, index: Int, length: Int): Boolean {
    val next = line.getOrNull(index + length)
    val before = line.getOrNull(index - 1)
    return next != null && !next.isWhitespace() && (before == null || !before.isLetterOrDigit())
}
