package com.pockettravel.feature.map

// Orari OSM (tag opening_hours) resi leggibili: giorni in italiano, "off" come "chiuso", una regola
// per riga. Non interpreta la sintassi: quello che non riconosce resta com'e' in OSM.
private val WORDS = mapOf(
    "Mo" to "lun", "Tu" to "mar", "We" to "mer", "Th" to "gio", "Fr" to "ven", "Sa" to "sab", "Su" to "dom",
    "PH" to "festivi", "off" to "chiuso", "closed" to "chiuso",
)
private val WORD = Regex("""\b(Mo|Tu|We|Th|Fr|Sa|Su|PH|off|closed)\b""")

internal fun formatOpeningHours(raw: String): String {
    if (raw.trim() == "24/7") return "sempre aperto"
    return raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") { rule ->
        WORD.replace(rule) { WORDS.getValue(it.value) }.replace(Regex(""",\s*"""), ", ")
    }
}
