package com.pockettravel.feature.map

// Orari OSM (tag opening_hours). parseOpeningHours capisce le forme piu' comuni ("Mo-Fr 09:00-18:00;
// Sa 09:00-13:00; Su off", "24/7", festivi con PH) e ne fa una tabella per giorni; il resto (mesi,
// settimane, eccezioni, "sunrise"...) torna null e il riquadro mostra formatOpeningHours.

/** Una riga della tabella: giorni consecutivi con lo stesso orario ("lun–ven", "08:00–20:00"). */
internal data class OpeningHoursRow(val days: String, val hours: String, val includesToday: Boolean)

/** Testi nella lingua dell'interfaccia (vedi openingHoursLabels in MapScreen.kt); [ITALIAN] per i test. */
internal data class OpeningHoursLabels(val days: List<String>, val closed: String, val alwaysOpen: String, val holidays: String) {
    companion object {
        val ITALIAN = OpeningHoursLabels(listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom"), "chiuso", "sempre aperto", "festivi")
    }
}

private val DAY_KEYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
private const val DAY = "(?:Mo|Tu|We|Th|Fr|Sa|Su|PH)"
private val DAYS = Regex("""$DAY(?:-$DAY)?(?:,$DAY(?:-$DAY)?)*""")
private val TIME = Regex("""(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})""")
private val TIMES = Regex("""${TIME.pattern}(?:,\s*${TIME.pattern})*""")
private val RULE = Regex("""^(?:(${DAYS.pattern})\s+)?(${TIMES.pattern}|off|closed)$""")

/**
 * Righe da lunedi' a domenica (giorni consecutivi con lo stesso orario insieme), piu' i festivi se
 * indicati; null se la stringa usa sintassi che qui non si interpreta. [today]: 0 = lunedi'.
 */
internal fun parseOpeningHours(raw: String, today: Int, labels: OpeningHoursLabels = OpeningHoursLabels.ITALIAN): List<OpeningHoursRow>? {
    val closed = labels.closed
    val names = labels.days
    val text = raw.trim()
    val week = arrayOfNulls<String>(7)
    var holidays: String? = null
    if (text == "24/7") {
        return listOf(OpeningHoursRow("${names[0]}–${names[6]}", labels.alwaysOpen, true))
    }
    for (rule in text.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
        val match = RULE.matchEntire(rule) ?: return null
        val hours = match.groupValues[2].let { if (it == "off" || it == "closed") closed else formatTimes(it) }
        val days = match.groupValues[1]
        // Regola senza giorni: vale per tutta la settimana (le successive la correggono).
        val targets = if (days.isEmpty()) DAY_KEYS else expandDays(days)
        for (day in targets) {
            if (day == "PH") holidays = hours else week[DAY_KEYS.indexOf(day)] = hours
        }
    }
    // In OSM i giorni non citati sono chiusi.
    val filled = week.map { it ?: closed }
    val rows = mutableListOf<OpeningHoursRow>()
    var start = 0
    for (i in 1..7) {
        if (i == 7 || filled[i] != filled[start]) {
            val label = if (i - 1 == start) names[start] else "${names[start]}–${names[i - 1]}"
            rows += OpeningHoursRow(label, filled[start], today in start until i)
            start = i
        }
    }
    holidays?.let { rows += OpeningHoursRow(labels.holidays, it, false) }
    return rows
}

private fun expandDays(spec: String): List<String> = spec.split(',').flatMap { part ->
    val range = part.split('-')
    if (range.size == 1 || range[0] == "PH" || range[1] == "PH") {
        range
    } else {
        val from = DAY_KEYS.indexOf(range[0])
        val to = DAY_KEYS.indexOf(range[1])
        // Intervalli che attraversano la domenica ("Fr-Mo").
        val indices = if (from <= to) (from..to).toList() else (from..6).toList() + (0..to).toList()
        indices.map { DAY_KEYS[it] }
    }
}

// "9:00-13:00,15:00-19:00" -> "09:00–13:00 | 15:00–19:00"
private fun formatTimes(times: String): String = TIME.findAll(times).joinToString(" | ") { m ->
    val (h1, m1, h2, m2) = m.destructured
    "${h1.padStart(2, '0')}:$m1–${h2.padStart(2, '0')}:$m2"
}

private val WORD = Regex("""\b(Mo|Tu|We|Th|Fr|Sa|Su|PH|off|closed)\b""")

/** Ripiego per le sintassi che parseOpeningHours non interpreta: giorni tradotti, una regola per riga. */
internal fun formatOpeningHours(raw: String, labels: OpeningHoursLabels = OpeningHoursLabels.ITALIAN): String {
    if (raw.trim() == "24/7") return labels.alwaysOpen
    val words = DAY_KEYS.zip(labels.days).toMap() + mapOf("PH" to labels.holidays, "off" to labels.closed, "closed" to labels.closed)
    return raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") { rule ->
        WORD.replace(rule) { words.getValue(it.value) }.replace(Regex(""",\s*"""), ", ")
    }
}
