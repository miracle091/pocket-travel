package com.pockettravel.feature.map

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Il ponte tra la guida e la notifica: richieste dalla notifica, aggiornamenti dello stato e conti della distanza. */
class NavigationSessionRequestsTest {

    @Test
    fun `Termina dalla notifica arriva a chi ascolta`() = runBlocking {
        val session = NavigationSession()
        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(1_000) { session.stopRequests.first() } }

        session.requestStop()

        assertEquals(Unit, received.await())
    }

    @Test
    fun `l'apertura del Navigatore arriva a chi ascolta`() = runBlocking {
        val session = NavigationSession()
        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(1_000) { session.openRequests.first() } }

        session.requestOpen()

        assertEquals(Unit, received.await())
    }

    @Test
    fun `una richiesta fatta senza nessuno in ascolto non si riesegue alla raccolta successiva`() = runBlocking {
        val session = NavigationSession()
        session.requestStop()
        session.requestOpen()

        assertNull(withTimeoutOrNull(100) { session.stopRequests.first() })
        assertNull(withTimeoutOrNull(100) { session.openRequests.first() })
    }

    @Test
    fun `le richieste di chiusura e di apertura sono separate`() = runBlocking {
        val session = NavigationSession()
        val stop = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(100) { session.stopRequests.first() } }

        session.requestOpen()

        assertNull(stop.await())
    }

    @Test
    fun `l'ultimo aggiornamento sostituisce il precedente`() {
        val session = NavigationSession()
        val first = NavigationSnapshot("Duomo", null, 0.0, 0.0, 0.0, waitingForFix = true)
        val second = NavigationSnapshot("Duomo", TurnInstruction(TurnType.LEFT, 120.0, 3), 120.0, 900.0, 600.0, street = "Via Roma")

        session.update(first)
        session.update(second)

        assertEquals(second, session.snapshot.value)
    }

    @Test
    fun `l'aggiornamento predefinito non ha strada e non aspetta il segnale`() {
        val snapshot = NavigationSnapshot("Duomo", null, 0.0, 0.0, 0.0)

        assertNull(snapshot.street)
        assertFalse(snapshot.waitingForFix)
    }

    @Test
    fun `995 metri si arrotondano a 1000 e diventano un chilometro, 994 restano metri`() {
        assertEquals(DistanceLabel.Kilometers(0.995), distanceLabel(995.0))
        assertEquals(DistanceLabel.Meters(990), distanceLabel(994.0))
    }

    @Test
    fun `zero metri sono zero metri`() {
        assertEquals(DistanceLabel.Meters(0), distanceLabel(0.0))
    }

    @Test
    fun `i secondi che restano a fine percorso sono zero`() {
        val route = Route(points = emptyList(), distanceMeters = 2_000.0, durationSeconds = 600.0)

        assertEquals(0.0, remainingSeconds(route, 0.0), 0.0)
        assertEquals(600.0, remainingSeconds(route, 2_000.0), 0.001)
    }

    @Test
    fun `l'arrivo oltre mezzanotte e' il minuto dell'epoca del giorno dopo`() {
        // 23:50:00 UTC del giorno 0 piu' 20 minuti: minuto 10 del giorno 1.
        val now = (23 * 60 + 50) * 60_000L

        assertEquals(24 * 60L + 10, arrivalMinute(now, 20 * 60.0))
    }

    @Test
    fun `l'arrivo con zero secondi restanti e' il minuto di adesso`() {
        assertEquals(600L, arrivalMinute(600 * 60_000L + 59_999, 0.0))
    }

    @Test
    fun `i minuti restanti si arrotondano al piu' vicino`() {
        assertEquals(1, remainingMinutes(89.0))
        assertEquals(2, remainingMinutes(90.0))
        assertEquals(2, remainingMinutes(149.0))
        assertEquals(3, remainingMinutes(150.0))
    }

    @Test
    fun `l'attesa della notifica a intervallo personalizzato`() {
        assertEquals(1_500L, throttleWaitMillis(lastPostMillis = 0, nowMillis = 500, intervalMillis = 2_000))
        assertEquals(0L, throttleWaitMillis(lastPostMillis = 0, nowMillis = 2_000, intervalMillis = 2_000))
    }
}
