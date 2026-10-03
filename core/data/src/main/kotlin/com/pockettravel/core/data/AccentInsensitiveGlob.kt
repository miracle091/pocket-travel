package com.pockettravel.core.data

import java.text.Normalizer

/**
 * Pattern GLOB di SQLite che trova [query] dentro un testo senza badare a maiuscole e accenti: ogni
 * lettera diventa il gruppo delle sue varianti ("riga" -> "*[rRŕŘ...][iIīí...][gGģ...][aAā...]*"), cosi'
 * "riga" trova "Rīgas" e "Rīga" trova "Riga". LIKE di SQLite ignora le maiuscole solo nell'ASCII e non
 * conosce gli accenti; un indice o una colonna senza accenti richiederebbero una migrazione della tabella poi.
 */
internal fun accentInsensitiveGlob(query: String): String = buildString {
    append('*')
    for (original in query) {
        // Un carattere alla volta: senza segni diacritici ne resta uno solo, o nessuno se era un segno combinante.
        val base = baseLetters(original.toString())
        if (base.length != 1) {
            if (base.isNotEmpty()) append(original)
            continue
        }
        val char = base[0]
        val variants = LETTER_VARIANTS[char]
        // Lettere senza varianti (cirillico, greco...): maiuscola, minuscola, base senza accento e lettera
        // originale, cosi' il testo digitato esatto trova sempre se stesso, anche con l'accento (ή).
        val forms = linkedSetOf(original, original.lowercaseChar(), original.uppercaseChar(), char, char.uppercaseChar())
        when {
            variants != null -> append('[').append(variants).append(']')
            forms.size > 1 -> append('[').append(forms.joinToString("")).append(']')
            // Caratteri speciali di GLOB come gruppo di un solo carattere: valgono per se stessi.
            char == '*' || char == '?' || char == '[' -> append('[').append(char).append(']')
            else -> append(char)
        }
    }
    append('*')
}

// Minuscole senza segni diacritici (NFD e via i segni combinanti): la base delle varianti.
private fun baseLetters(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

// Lettera ASCII minuscola -> tutte le sue forme (maiuscola, minuscola, accentate) dei blocchi latini.
private val LETTER_VARIANTS: Map<Char, String> by lazy {
    val variants = ('a'..'z').associateWithTo(mutableMapOf()) { StringBuilder().append(it).append(it.uppercaseChar()) }
    val latin = (0x00C0..0x024F) + (0x1E00..0x1EFF)
    for (code in latin) {
        val char = code.toChar()
        val base = Normalizer.normalize(char.toString(), Normalizer.Form.NFD).first().lowercaseChar()
        variants[base]?.takeIf { char !in it }?.append(char)
    }
    // Lettere senza scomposizione Unicode ma lette come quella base.
    mapOf('l' to "łŁ", 'o' to "øØ", 'd' to "đĐð", 'i' to "ıİ", 'h' to "ħĦ", 't' to "ŧŦ").forEach { (base, extra) ->
        extra.filterNot { it in variants.getValue(base) }.forEach { variants.getValue(base).append(it) }
    }
    variants.mapValues { it.value.toString() }
}
