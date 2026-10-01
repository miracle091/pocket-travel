package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationSessionTest {
    @Test
    fun `sotto il chilometro la distanza e' a decine di metri`() {
        assertEquals(DistanceLabel.Meters(0), distanceLabel(3.0))
        assertEquals(DistanceLabel.Meters(250), distanceLabel(254.0))
        assertEquals(DistanceLabel.Meters(250), distanceLabel(246.0))
    }

    @Test
    fun `996 metri sono gia' un chilometro`() {
        assertEquals(DistanceLabel.Kilometers(0.996), distanceLabel(996.0))
        assertEquals(DistanceLabel.Kilometers(4.2), distanceLabel(4_200.0))
    }

    @Test
    fun `il tempo che resta e' in proporzione ai metri`() {
        val route = Route(points = emptyList(), distanceMeters = 2_000.0, durationSeconds = 600.0)
        assertEquals(150.0, remainingSeconds(route, 500.0), 0.001)
        assertEquals(0.0, remainingSeconds(route.copy(distanceMeters = 0.0), 500.0), 0.0)
    }

    @Test
    fun `l'arrivo e' al minuto di adesso piu' i minuti che restano`() {
        // 10:00:30 piu' 90 secondi (arrotondati a 2 minuti): 10:02.
        val now = 10 * 3_600_000L + 30_000
        assertEquals(10 * 60L + 2, arrivalMinute(now, 90.0))
    }

    @Test
    fun `i minuti restanti sono almeno uno`() {
        assertEquals(1, remainingMinutes(0.0))
        assertEquals(1, remainingMinutes(20.0))
        assertEquals(12, remainingMinutes(11.6 * 60))
    }

    @Test
    fun `il primo aggiornamento della notifica non aspetta`() {
        assertEquals(0L, throttleWaitMillis(null, 5_000))
    }

    @Test
    fun `un aggiornamento troppo ravvicinato aspetta il resto dell'intervallo`() {
        assertEquals(600L, throttleWaitMillis(lastPostMillis = 10_000, nowMillis = 10_400))
    }

    @Test
    fun `passato l'intervallo non si aspetta`() {
        assertEquals(0L, throttleWaitMillis(lastPostMillis = 10_000, nowMillis = 11_000))
        assertEquals(0L, throttleWaitMillis(lastPostMillis = 10_000, nowMillis = 20_000))
    }

    @Test
    fun `se l'orologio torna indietro l'attesa non supera l'intervallo`() {
        assertEquals(NOTIFICATION_MIN_INTERVAL_MILLIS, throttleWaitMillis(lastPostMillis = 10_000, nowMillis = 1_000))
    }

    @Test
    fun `la sessione parte vuota e si svuota con clear`() {
        val session = NavigationSession()
        assertNull(session.snapshot.value)
        val snapshot = NavigationSnapshot("Duomo", null, 0.0, 0.0, 0.0)
        session.update(snapshot)
        assertEquals(snapshot, session.snapshot.value)
        session.clear()
        assertNull(session.snapshot.value)
    }
}
