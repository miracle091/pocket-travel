package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class DepartureForTest {
    private val now = LocalDateTime.of(2026, 10, 1, 14, 0, 30)

    @Test
    fun `si parte prima della durata, arrotondata al minuto per eccesso`() {
        // 21 min e 10 s -> 22 minuti prima delle 15:00.
        assertEquals(LocalDateTime.of(2026, 10, 1, 14, 38), departureFor(arrivalFor(LocalTime.of(15, 0), now), 21 * 60 + 10.0))
    }

    @Test
    fun `un orario gia' passato oggi vale per domani`() {
        assertEquals(LocalDateTime.of(2026, 10, 2, 9, 0), arrivalFor(LocalTime.of(9, 0), now))
    }

    @Test
    fun `anche un percorso brevissimo chiede almeno un minuto`() {
        assertEquals(LocalDateTime.of(2026, 10, 1, 14, 59), departureFor(LocalDateTime.of(2026, 10, 1, 15, 0), 5.0))
    }

    @Test
    fun `ritardo in minuti per eccesso, anticipo per difetto`() {
        val arrival = LocalDateTime.of(2026, 10, 1, 14, 20)
        // Arrivo alle 14:27:30: 8 minuti di ritardo (7 e mezzo arrotondati per eccesso).
        assertEquals(8L, minutesLate(arrival, 27 * 60.0, now))
        // Arrivo alle 14:10:30: 9 minuti e mezzo di anticipo, cioe' -9.
        assertEquals(-9L, minutesLate(arrival, 10 * 60.0, now))
        // Arrivo esatto all'ora scelta: in orario.
        assertEquals(0L, minutesLate(arrival, 19 * 60 + 30.0, now))
    }

    @Test
    fun `un orario passato da poco e' un ritardo di oggi, non un appuntamento di domani`() {
        assertEquals(LocalDateTime.of(2026, 10, 1, 13, 30), arrivalFor(LocalTime.of(13, 30), now))
    }

    @Test
    fun `l'ora scelta resta fissa anche quando e' passata`() {
        // Scelta alle 14:00 per le 14:25, adesso sono le 14:30: 25 minuti di cammino, 30 di ritardo.
        val arrival = arrivalFor(LocalTime.of(14, 25), now)
        assertEquals(30L, minutesLate(arrival, 25 * 60.0, LocalDateTime.of(2026, 10, 1, 14, 30)))
    }

    @Test
    fun `la notte del cambio dell'ora i minuti sono quelli veri`() {
        val rome = java.time.ZoneId.of("Europe/Rome")
        // 25 ottobre 2026: alle 3:00 si torna alle 2:00, tra l'1:30 e le 3:30 d'orologio passano 3 ore.
        val before = LocalDateTime.of(2026, 10, 25, 1, 30)
        val after = LocalDateTime.of(2026, 10, 25, 3, 30)
        assertEquals(180L, minutesBetween(before, after, rome))
        // Arrivo alle 3:30 con 2 ore di strada: si parte alle 2:30 (ora legale), non all'1:30 di un conto d'orologio.
        assertEquals(LocalDateTime.of(2026, 10, 25, 2, 30), departureFor(after, 2 * 3600.0, rome))
    }
}
