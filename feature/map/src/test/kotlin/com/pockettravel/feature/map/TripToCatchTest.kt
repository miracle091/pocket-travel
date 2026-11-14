package com.pockettravel.feature.map

import com.pockettravel.core.data.DepartureRealtime
import com.pockettravel.core.data.TransitDeparture
import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.data.TripRef
import com.pockettravel.core.data.TripStopTime
import com.pockettravel.core.data.TripView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TripToCatchTest {
    private val now = LocalDateTime.of(2026, 11, 14, 14, 0, 40)

    private fun ref(trip: Int) = TripRef("net", trip, LocalDate.of(2026, 11, 14), stop = 7, minute = 0, pattern = 1, start = 0)

    private fun departure(trip: Int, inMinutes: Int, realtime: DepartureRealtime? = null, later: List<TransitDeparture> = emptyList()) =
        TransitDeparture("12", TransitMode.BUS, null, null, "Centro", 0, inMinutes, realtime = realtime, later = later, trip = ref(trip))

    private fun stop(name: String, board: Boolean) = TripStopTime(name, 45.0, 10.0, 0, 0, pickup = true, dropOff = true, isBoardStop = board, realtime = null)

    private val view = TripView("12", TransitMode.BUS, null, null, "Centro", listOf(stop("Prima", false), stop("Piazza", true), stop("Centro", false)))
    private val board = listOf(
        departure(1, 3, later = listOf(departure(2, 13), departure(3, 23, DepartureRealtime.Cancelled), departure(4, 33, DepartureRealtime.Delay(2)))),
        departure(9, 5),
    )

    @Test
    fun `la corsa scelta con le successive della sua linea, senza le soppresse, all'ora del telefono`() {
        val toCatch = tripToCatch(view, ref(2), board, now)

        assertEquals("Piazza", toCatch?.stopName)
        assertEquals("Centro", toCatch?.headsign)
        assertEquals(listOf(LocalDateTime.of(2026, 11, 14, 14, 13), LocalDateTime.of(2026, 11, 14, 14, 33)), toCatch?.departures)
    }

    @Test
    fun `la prima partenza del tabellone porta con se' le successive`() {
        assertEquals(listOf(3, 13, 33).map { now.withSecond(0).plusMinutes(it.toLong()) }, tripToCatch(view, ref(1), board, now)?.departures)
    }

    @Test
    fun `senza la partenza nel tabellone o senza la fermata nella corsa non c'e' corsa da prendere`() {
        assertNull(tripToCatch(view, ref(5), board, now))
        assertNull(tripToCatch(view.copy(stops = listOf(stop("Prima", false))), ref(1), board, now))
    }

    @Test
    fun `persa la partenza scelta si passa alla prima successiva che si prende, se c'e'`() {
        val clock = LocalDateTime.of(2026, 11, 14, 14, 0)
        val departures = listOf(5L, 15L, 30L).map { clock.plusMinutes(it) }

        // 10 minuti a piedi: quella tra 5 si perde, quella tra 15 si prende.
        assertEquals(departures[1], nextDeparture(departures, departures[0], 600.0, clock))
        // 3 minuti: la scelta si prende ancora.
        assertNull(nextDeparture(departures, departures[0], 180.0, clock))
        // 40 minuti: nessuna si prende.
        assertNull(nextDeparture(departures, departures[0], 2_400.0, clock))
    }

    @Test
    fun `dalla riga del tabellone la partenza con le successive, alla fermata della mappa`() {
        val toCatch = tripToCatch(board[0], "Piazza", 45.1, 10.1, now)

        assertEquals("Piazza", toCatch?.stopName)
        assertEquals(45.1, toCatch?.latitude)
        assertEquals(listOf(3, 13, 33).map { now.withSecond(0).plusMinutes(it.toLong()) }, toCatch?.departures)
        assertNull(tripToCatch(departure(8, 2, DepartureRealtime.Cancelled), "Piazza", 45.1, 10.1, now))
    }
}
