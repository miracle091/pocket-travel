package com.pockettravel.feature.ai

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.core.sync.OnlineModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

// Quota giornaliera dei modelli online: quando torna, quali modelli sono esauriti, l'ordine dei ripieghi in Automatico.
class OnlineQuotaTest {

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    private val files = InMemoryPreferencesFactory()
    private var apiKey: String? = "chiave-uno"

    // Un nuovo tracker sugli stessi file: come dopo un riavvio.
    private fun tracker() = OnlineQuotaTracker(files) { apiKeyFingerprint(apiKey) }

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
        val tracker = tracker()
        val now = millis("2026-11-02T10:00:00Z")
        val resetAt = tracker.markExhausted(OnlineProvider.GEMINI, "gemini-3.6-flash", now)
        assertEquals(resetAt, tracker.resetAtMillis(OnlineProvider.GEMINI, "gemini-3.6-flash", now + 1_000))
        assertNull(tracker.resetAtMillis(OnlineProvider.GEMINI, "gemini-3.6-flash", resetAt))
        assertNull(tracker.resetAtMillis(OnlineProvider.GEMINI, "gemini-3.5-flash-lite", now))
        assertNull(tracker.resetAtMillis(OnlineProvider.MISTRAL, "gemini-3.6-flash", now))
    }

    @Test
    fun `an exhausted model survives a restart`() {
        val now = millis("2026-11-02T10:00:00Z")
        val resetAt = tracker().markExhausted(OnlineProvider.GEMINI, "gemini-3.6-flash", now)
        assertEquals(resetAt, tracker().resetAtMillis(OnlineProvider.GEMINI, "gemini-3.6-flash", now + 1_000))
    }

    @Test
    fun `the reset told by the service replaces the midnight`() {
        val now = millis("2026-11-02T10:00:00Z")
        val resetAt = tracker().markExhausted(OnlineProvider.CHATGPT, "gpt-5-mini", now, now + 60_000)
        assertEquals(now + 60_000, resetAt)
        assertEquals(resetAt, tracker().resetAtMillis(OnlineProvider.CHATGPT, "gpt-5-mini", now + 1_000))
    }

    @Test
    fun `an exhausted model is forgotten when the key changes`() {
        val now = millis("2026-11-02T10:00:00Z")
        tracker().markExhausted(OnlineProvider.GEMINI, "gemini-3.6-flash", now)
        apiKey = "chiave-due"
        assertNull(tracker().resetAtMillis(OnlineProvider.GEMINI, "gemini-3.6-flash", now + 1_000))
        // Anche tornando alla chiave di prima: il salvataggio e' stato scartato.
        apiKey = "chiave-uno"
        assertNull(tracker().resetAtMillis(OnlineProvider.GEMINI, "gemini-3.6-flash", now + 1_000))
    }

    @Test
    fun `an exhausted model is forgotten after its reset, also after a restart`() {
        val now = millis("2026-11-02T10:00:00Z")
        val resetAt = tracker().markExhausted(OnlineProvider.GEMINI, "gemini-3.6-flash", now)
        assertNull(tracker().resetAtMillis(OnlineProvider.GEMINI, "gemini-3.6-flash", resetAt + 1))
        assertNull(tracker().resetAtMillis(OnlineProvider.GEMINI, "gemini-3.6-flash", now))
    }

    @Test
    fun `the kind of limit survives a restart`() {
        val now = millis("2026-11-02T10:00:00Z")
        tracker().markExhausted(OnlineProvider.CHATGPT, "gpt-5-mini", now, now + 60_000, daily = false)
        tracker().markExhausted(OnlineProvider.GEMINI, "gemini-3.6-flash", now)
        assertEquals(false, tracker().exhaustion(OnlineProvider.CHATGPT, "gpt-5-mini", now + 1_000)?.daily)
        assertEquals(true, tracker().exhaustion(OnlineProvider.GEMINI, "gemini-3.6-flash", now + 1_000)?.daily)
    }

    @Test
    fun `an entry saved before the kind existed reads as daily`() {
        val now = millis("2026-11-02T10:00:00Z")
        val resetAt = tracker().markExhausted(OnlineProvider.GEMINI, "gemini-3.6-flash", now)
        val prefs = files.open("online_quota")
        val name = "GEMINI/gemini-3.6-flash"
        prefs.edit().putString(name, requireNotNull(prefs.getString(name, null)).substringBeforeLast("|")).commit()
        val old = tracker().exhaustion(OnlineProvider.GEMINI, "gemini-3.6-flash", now + 1_000)
        assertEquals(resetAt, old?.resetAtMillis)
        assertEquals(true, old?.daily)
    }

    @Test
    fun `the text follows the kind of limit`() {
        assertEquals(R.string.ai_error_daily_quota, ChatNotice.QuotaExhausted("m", 0L, emptyList()).textRes())
        assertEquals(R.string.ai_error_request_limit, ChatNotice.QuotaExhausted("m", 0L, emptyList(), daily = false).textRes())
        assertEquals(R.string.ai_notice_model_switched, ChatNotice.ModelSwitched("a", "b", 0L).textRes())
        assertEquals(R.string.ai_notice_model_switched_request_limit, ChatNotice.ModelSwitched("a", "b", 0L, daily = false).textRes())
    }

    @Test
    fun `the key is never saved in the clear`() {
        tracker().markExhausted(OnlineProvider.GEMINI, "gemini-3.6-flash", millis("2026-11-02T10:00:00Z"))
        val saved = files.open("online_quota").all.values.joinToString()
        assertFalse(saved, "chiave-uno" in saved)
    }

    @Test
    fun `the key fingerprint is fixed per key and missing without a key`() {
        assertEquals(apiKeyFingerprint("chiave-uno"), apiKeyFingerprint("chiave-uno"))
        assertEquals(16, apiKeyFingerprint("chiave-uno")?.length)
        assertFalse(apiKeyFingerprint("chiave-uno") == apiKeyFingerprint("chiave-due"))
        assertNull(apiKeyFingerprint(null))
        assertNull(apiKeyFingerprint(" "))
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
