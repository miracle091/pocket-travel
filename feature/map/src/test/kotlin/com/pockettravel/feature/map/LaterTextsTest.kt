package com.pockettravel.feature.map

import com.pockettravel.core.data.DepartureRealtime
import com.pockettravel.core.data.TransitDeparture
import com.pockettravel.core.data.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaterTextsTest {
    private val labels = LaterLabels(
        delay = { "+$it" },
        early = { "-$it" },
        cancelled = "soppressa",
        delaySpoken = { "$it minuti di ritardo" },
        earlySpoken = { "$it minuti in anticipo" },
    )

    private fun later(minute: Int, realtime: DepartureRealtime?) = TransitDeparture(
        line = "1", mode = TransitMode.BUS, color = null, textColor = null, headsign = null,
        minuteOfDay = minute, inMinutes = 0, realtime = realtime,
    )

    private fun clockOf(minute: Int) = "%d:%02d".format(minute / 60, minute % 60)

    @Test
    fun `senza partenze successive non c'e' testo`() {
        assertNull(laterTexts(emptyList(), ::clockOf, labels))
    }

    @Test
    fun `le successive restano all'ora programmata con il segno accanto`() {
        val texts = laterTexts(
            listOf(
                later(10 * 60 + 50, DepartureRealtime.Delay(3)),
                later(11 * 60 + 20, DepartureRealtime.Delay(-2)),
                later(11 * 60 + 50, DepartureRealtime.Cancelled),
                later(12 * 60 + 20, DepartureRealtime.Delay(0)),
                later(12 * 60 + 50, null),
            ),
            ::clockOf,
            labels,
        )
        assertEquals("10:50 +3, 11:20 -2, 11:50 soppressa, 12:20, 12:50", texts?.visible)
        assertEquals(
            "10:50 3 minuti di ritardo; 11:20 2 minuti in anticipo; 11:50 soppressa; 12:20; 12:50",
            texts?.spoken,
        )
    }
}
