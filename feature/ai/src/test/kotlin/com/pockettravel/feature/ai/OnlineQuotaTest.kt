package com.pockettravel.feature.ai

import com.pockettravel.core.sync.OnlineModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

// Quota giornaliera dei modelli online: quando torna, quali modelli sono esauriti, l'ordine dei ripieghi in Automatico.
class OnlineQuotaTest {

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `the quota comes back at the next midnight in Pacific time`() {
        // Ora solare negli Stati Uniti: mezzanotte del Pacifico = 08:00 UTC = 09:00 in Italia.
        assertEquals(millis("2026-11-03T08:00:00Z"), dailyQuotaResetMillis(millis("2026-11-02T10:00:00Z")))
        // Il 1 novembre gli Stati Uniti hanno ancora l'ora legale fino alle 2: la mezzanotte appena passata era alle 07:00 UTC.
        assertEquals(millis("2026-11-02T08:00:00Z"), dailyQuotaResetMillis(millis("2026-11-01T07:30:00Z")))
        // D'estate: mezzanotte del Pacifico = 07:00 UTC.
        assertEquals(millis("2026-07-16T07:00:00Z"), dailyQuotaResetMillis(millis("2026-07-15T12:00:00Z")))
    }

    @Test
    fun `an exhausted model stays exhausted until its reset, for its service only`() {
        val tracker = OnlineQuotaTracker()
        val now = millis("2026-11-02T10:00:00Z")
        val resetAt = tracker.markExhausted(OnlineProvider.GEMINI, "gemini-3.6-flash", now)
        assertEquals(resetAt, tracker.resetAtMillis(OnlineProvider.GEMINI, "gemini-3.6-flash", now + 1_000))
        assertNull(tracker.resetAtMillis(OnlineProvider.GEMINI, "gemini-3.6-flash", resetAt))
        assertNull(tracker.resetAtMillis(OnlineProvider.GEMINI, "gemini-3.5-flash-lite", now))
        assertNull(tracker.resetAtMillis(OnlineProvider.MISTRAL, "gemini-3.6-flash", now))
    }

    @Test
    fun `automatic tries its own model first, then the others from the cheapest`() {
        val models = listOf(
            OnlineModelEntry("gemini-3.6-flash", "auto", false, 0.75, 3.75),
            OnlineModelEntry("gemini-4-argon", "capable", false, 2.0, 10.0),
            OnlineModelEntry("gemini-3.5-flash-lite", "capable", true, 0.30, 2.50),
        )
        assertEquals(
            listOf("gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-4-argon"),
            automaticCandidates("gemini-3.6-flash", models),
        )
    }
}
