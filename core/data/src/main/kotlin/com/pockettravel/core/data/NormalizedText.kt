package com.pockettravel.core.data

import java.text.Normalizer
import java.util.Locale

/**
 * Testo normalizzato per la ricerca: senza diacritici e in minuscolo, un carattere alla volta, e per ogni carattere la
 * posizione nel testo originale (per evidenziare le parole trovate). Un segno combinante isolato si toglie.
 */
internal class NormalizedText(val text: String, private val origins: IntArray) {
    /** Dove comincia, nell'originale, il carattere [index] del testo normalizzato. */
    fun originStart(index: Int): Int = origins[index]

    /** Dove finisce (escluso), nell'originale, il carattere [index] del testo normalizzato. */
    fun originEnd(index: Int): Int = origins[index] + 1
}

internal fun normalizeForSearch(original: String): NormalizedText {
    val out = StringBuilder(original.length)
    val origins = IntArray(original.length)
    original.forEachIndexed { index, char ->
        normalizedChar(char)?.let {
            origins[out.length] = index
            out.append(it)
        }
    }
    return NormalizedText(out.toString(), origins.copyOf(out.length))
}

// null per un segno combinante isolato. Se togliere i diacritici e abbassare la maiuscola non dà un carattere solo
// (alfabeti con scomposizioni lunghe), resta il carattere minuscolo originale: gli scostamenti restano allineati.
private fun normalizedChar(char: Char): Char? {
    val base = if (char.code < ASCII_LIMIT) char.toString() else Normalizer.normalize(char.toString(), Normalizer.Form.NFD).filterNot { it.category == CharCategory.NON_SPACING_MARK }
    val lower = base.lowercase(Locale.ROOT)
    return when {
        base.isEmpty() -> null
        lower.length == 1 -> lower[0]
        else -> Character.toLowerCase(char)
    }
}

/** Le parole di [normalized]: sequenze di lettere o cifre, l'apostrofo e ogni altro segno le separano. */
internal fun searchWords(normalized: String): List<IntRange> {
    val words = mutableListOf<IntRange>()
    var start = -1
    for (index in 0..normalized.length) {
        val inWord = index < normalized.length && normalized[index].isLetterOrDigit()
        if (inWord && start < 0) start = index
        if (!inWord && start >= 0) {
            words += start until index
            start = -1
        }
    }
    return words
}

/** Le parole della domanda cercata, normalizzate e senza ripetizioni; vuota se non ce ne sono. */
internal fun queryWords(query: String): List<String> {
    val text = normalizeForSearch(query).text
    return searchWords(text).map { text.substring(it.first, it.last + 1) }.distinct()
}

/**
 * Le parti di [normalized] che iniziano una parola e coincidono con una parola cercata (la più lunga se più d'una); null se
 * una parola cercata non c'è.
 */
internal fun prefixMatches(normalized: String, query: List<String>, words: List<IntRange> = searchWords(normalized)): List<IntRange>? {
    if (query.isEmpty()) return null
    val found = mutableListOf<IntRange>()
    val covered = HashSet<String>()
    for (word in words) {
        val matching = query.filter { word.last + 1 - word.first >= it.length && normalized.startsWith(it, word.first) }
        val longest = matching.maxByOrNull { it.length } ?: continue
        covered += matching
        found += word.first until word.first + longest.length
    }
    return found.takeIf { covered.size == query.size }
}

private const val ASCII_LIMIT = 0x80
