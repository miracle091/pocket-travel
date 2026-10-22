package com.pockettravel.feature.map

import com.pockettravel.core.data.DepartureRealtime
import com.pockettravel.core.data.TransitDeparture
import com.pockettravel.core.data.delayMinutes

/** Le parole del tempo reale delle partenze successive: [delay] e [early] ricevono i minuti (positivi). */
internal class LaterLabels(
    val delay: (Int) -> String,
    val early: (Int) -> String,
    val cancelled: String,
    val delaySpoken: (Int) -> String,
    val earlySpoken: (Int) -> String,
)

/** Il testo a vista e quello per TalkBack delle partenze successive. */
internal data class LaterTexts(val visible: String, val spoken: String)

/**
 * Le partenze successive all'ora programmata, con accanto il ritardo, l'anticipo o la soppressione; in orario o
 * senza tempo reale nessun segno. Null se non ce ne sono.
 */
internal fun laterTexts(later: List<TransitDeparture>, clockOf: (Int) -> String, labels: LaterLabels): LaterTexts? {
    if (later.isEmpty()) return null
    val parts = later.map { item ->
        val clock = clockOf(item.minuteOfDay)
        val realtime = item.realtime
        val delay = item.realtime.delayMinutes
        when {
            realtime == DepartureRealtime.Cancelled -> "$clock ${labels.cancelled}" to "$clock ${labels.cancelled}"
            delay > 0 -> "$clock ${labels.delay(delay)}" to "$clock ${labels.delaySpoken(delay)}"
            delay < 0 -> "$clock ${labels.early(-delay)}" to "$clock ${labels.earlySpoken(-delay)}"
            else -> clock to clock
        }
    }
    return LaterTexts(parts.joinToString(", ") { it.first }, parts.joinToString("; ") { it.second })
}
