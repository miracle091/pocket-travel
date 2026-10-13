package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Casi limite dei conti sull'ora di arrivo e di partenza: mezzanotte, cambio dell'ora legale, soglie dell'avviso. */
class DepartureTimingTest {
    private val rome = ZoneId.of("Europe/Rome")

    @Test
    fun `un orario passato da esattamente un'ora e' ancora di oggi, un secondo di piu' e' di domani`() {
        val now = LocalDateTime.of(2026, 10, 1, 14, 0, 30)

        assertEquals(LocalDateTime.of(2026, 10, 1, 13, 0, 30), arrivalFor(LocalTime.of(13, 0, 30), now))
        assertEquals(LocalDateTime.of(2026, 10, 2, 13, 0), arrivalFor(LocalTime.of(13, 0), now))
    }

    @Test
    fun `a cavallo di mezzanotte un orario piccolo scelto di sera vale per domani`() {
        val now = LocalDateTime.of(2026, 10, 1, 23, 30)

        assertEquals(LocalDateTime.of(2026, 10, 2, 0, 15), arrivalFor(LocalTime.of(0, 15), now))
    }

    @Test
    fun `un orario di sera scelto appena dopo mezzanotte resta oggi`() {
        val now = LocalDateTime.of(2026, 10, 2, 0, 10)

        assertEquals(LocalDateTime.of(2026, 10, 2, 23, 50), arrivalFor(LocalTime.of(23, 50), now))
    }

    @Test
    fun `l'ora scelta e' esatta anche a fine anno`() {
        val now = LocalDateTime.of(2026, 12, 31, 23, 50)

        assertEquals(LocalDateTime.of(2027, 1, 1, 0, 30), arrivalFor(LocalTime.of(0, 30), now))
    }

    @Test
    fun `la partenza per un arrivo dopo mezzanotte e' la sera prima`() {
        val arrival = LocalDateTime.of(2026, 10, 2, 0, 10)

        assertEquals(LocalDateTime.of(2026, 10, 1, 23, 40), departureFor(arrival, 30 * 60.0, ZoneOffset.UTC))
    }

    @Test
    fun `la partenza perde i secondi dell'arrivo`() {
        val arrival = LocalDateTime.of(2026, 10, 1, 15, 0, 30)

        // Un minuto di strada: 14:59:30, portato al minuto pieno.
        assertEquals(LocalDateTime.of(2026, 10, 1, 14, 59), departureFor(arrival, 60.0, ZoneOffset.UTC))
    }

    @Test
    fun `un percorso lungo ore attraversa piu' giorni`() {
        val arrival = LocalDateTime.of(2026, 10, 2, 6, 0)

        assertEquals(LocalDateTime.of(2026, 10, 1, 6, 0), departureFor(arrival, 24 * 3600.0, ZoneOffset.UTC))
    }

    @Test
    fun `durata zero chiede comunque un minuto`() {
        assertEquals(LocalDateTime.of(2026, 10, 1, 14, 59), departureFor(LocalDateTime.of(2026, 10, 1, 15, 0), 0.0, ZoneOffset.UTC))
    }

    @Test
    fun `la notte in cui si passa all'ora legale un'ora di strada e' un'ora vera`() {
        // 29 marzo 2026: alle 2:00 gli orologi vanno alle 3:00. Arrivo alle 3:30 con un'ora di strada: si parte all'1:30.
        val arrival = LocalDateTime.of(2026, 3, 29, 3, 30)

        assertEquals(LocalDateTime.of(2026, 3, 29, 1, 30), departureFor(arrival, 3600.0, rome))
        assertEquals(60L, minutesBetween(LocalDateTime.of(2026, 3, 29, 1, 30), arrival, rome))
    }

    @Test
    fun `i minuti tra due orari sono negativi se il secondo viene prima`() {
        assertEquals(-45L, minutesBetween(LocalDateTime.of(2026, 10, 1, 15, 0), LocalDateTime.of(2026, 10, 1, 14, 15), ZoneOffset.UTC))
    }

    @Test
    fun `il ritardo si conta oltre mezzanotte`() {
        val arrival = LocalDateTime.of(2026, 10, 2, 0, 10)
        val now = LocalDateTime.of(2026, 10, 1, 23, 50)

        // Mezz'ora di strada: arrivo alle 0:20, dieci minuti dopo l'ora scelta.
        assertEquals(10L, minutesLate(arrival, 30 * 60.0, now, ZoneOffset.UTC))
    }

    @Test
    fun `senza strada da fare il ritardo e' il tempo gia' passato`() {
        val arrival = LocalDateTime.of(2026, 10, 1, 14, 0)

        assertEquals(5L, minutesLate(arrival, 0.0, LocalDateTime.of(2026, 10, 1, 14, 5), ZoneOffset.UTC))
        assertEquals(0L, minutesLate(arrival, 0.0, arrival, ZoneOffset.UTC))
    }

    @Test
    fun `l'avviso nella notte del cambio dell'ora legale suona all'istante giusto`() {
        // 25 ottobre 2026: le 2:30 ci sono due volte, vale la prima (ora legale, due ore avanti dell'UTC).
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), reminderTriggerMillis(LocalDateTime.of(2026, 10, 25, 2, 30), rome))
        // 29 marzo 2026: le 2:30 non esistono, l'orologio salta alle 3:30 (ora legale).
        assertEquals(Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(), reminderTriggerMillis(LocalDateTime.of(2026, 3, 29, 2, 30), rome))
    }

    @Test
    fun `all'accensione un avviso esattamente all'ora e' ancora da mostrare, non da riprogrammare`() {
        val now = 1_000_000_000L

        assertEquals(RestoreAction.SCHEDULE, restoreAction(now + 1, now))
        assertEquals(RestoreAction.NOTIFY_NOW, restoreAction(now, now))
    }

    @Test
    fun `all'accensione un avviso perso da mezz'ora si mostra, da un millisecondo di piu' si scarta`() {
        val now = 1_000_000_000L
        val thirtyMinutes = 30 * 60_000L

        assertEquals(RestoreAction.NOTIFY_NOW, restoreAction(now - thirtyMinutes, now))
        assertEquals(RestoreAction.DROP, restoreAction(now - thirtyMinutes - 1, now))
    }
}
