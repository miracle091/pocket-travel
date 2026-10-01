package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class ArrivalAndReminderTest {
    private val now = 10_000_000L

    @Test
    fun `senza arrivo registrato non e' un arrivo recente`() {
        assertFalse(isRecentArrival(null, now))
    }

    @Test
    fun `un arrivo di pochi secondi fa e' recente`() {
        assertTrue(isRecentArrival(now - 30_000, now))
        assertTrue(isRecentArrival(now, now))
    }

    @Test
    fun `fino a due minuti e' recente, oltre no`() {
        assertTrue(isRecentArrival(now - RECENT_ARRIVAL_MILLIS, now))
        assertFalse(isRecentArrival(now - RECENT_ARRIVAL_MILLIS - 1, now))
    }

    @Test
    fun `un arrivo nel futuro (orologio cambiato) non e' recente`() {
        assertFalse(isRecentArrival(now + 1, now))
    }

    @Test
    fun `l'avviso suona all'ora locale di partenza`() {
        val departure = LocalDateTime.of(2026, 10, 1, 14, 38)
        assertEquals(Instant.parse("2026-10-01T14:38:00Z").toEpochMilli(), reminderTriggerMillis(departure, ZoneOffset.UTC))
        // Due ore avanti dell'UTC: la stessa ora locale e' due ore prima nell'epoca.
        assertEquals(Instant.parse("2026-10-01T12:38:00Z").toEpochMilli(), reminderTriggerMillis(departure, ZoneOffset.ofHours(2)))
    }

    @Test
    fun `all accensione l avviso futuro si riprogramma, quello appena perso si mostra, il vecchio no`() {
        val now = 1_000_000_000L
        assertEquals(RestoreAction.SCHEDULE, restoreAction(now + 60_000, now))
        assertEquals(RestoreAction.NOTIFY_NOW, restoreAction(now - 10 * 60_000, now))
        assertEquals(RestoreAction.DROP, restoreAction(now - 31 * 60_000, now))
    }
}
