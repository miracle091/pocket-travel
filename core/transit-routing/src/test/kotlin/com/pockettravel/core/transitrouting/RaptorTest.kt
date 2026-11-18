package com.pockettravel.core.transitrouting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaptorTest {
    // Fermate lungo un meridiano: 0,001 gradi di latitudine sono circa 111 m.
    private val s0 = TransitStop(45.000, 9.0)
    private val s1 = TransitStop(45.010, 9.0)
    private val s2 = TransitStop(45.0109, 9.0) // circa 100 m da s1: cambio a piedi
    private val s3 = TransitStop(45.020, 9.0)
    private val origin = Place(45.0005, 9.0) // circa 55 m da s0
    private val destination = Place(45.0205, 9.0) // circa 55 m da s3

    private fun trip(id: Int, vararg minutes: Int) = PatternTrip(id, minutes, minutes)

    // Linea A da s0 a s1, linea B da s2 a s3 (miniFeed del piano: due linee e un cambio a piedi).
    private val lineA = TransitPattern(1, intArrayOf(0, 1), listOf(trip(10, 480, 490), trip(11, 495, 505)))
    private val lineB = TransitPattern(2, intArrayOf(2, 3), listOf(trip(20, 500, 510), trip(21, 520, 530)))

    private fun raptor(vararg patterns: TransitPattern, stops: List<TransitStop> = listOf(s0, s1, s2, s3)) =
        Raptor(TransitNetwork(stops, patterns.toList()))

    @Test
    fun `due linee con un cambio a piedi`() {
        val result = raptor(lineA, lineB).plan(origin, destination, departMinute = 475)

        assertEquals(1, result.size)
        val legs = result.single().legs
        assertEquals(listOf(WalkLeg::class, RideLeg::class, WalkLeg::class, RideLeg::class, WalkLeg::class), legs.map { it::class })
        val toStop = legs[0]
        val rideA = legs[1]
        val change = legs[2]
        val rideB = legs[3]
        val toDestination = legs[4]
        // A piedi 55 m: un minuto, uscendo appena in tempo per la corsa delle 8.
        assertEquals(479 to 480, toStop.departMinute to toStop.arriveMinute)
        assertEquals(listOf(10, 20), listOf(rideA, rideB).map { tripId(it as RideLeg, lineA, lineB) })
        // 100 m da s1 a s2: due minuti a piedi piu' quello di cambio.
        assertEquals(490 to 493, change.departMinute to change.arriveMinute)
        assertEquals(511, toDestination.arriveMinute)
        assertEquals(1, result.single().transfers)
    }

    @Test
    fun `persa la prima corsa prende la successiva`() {
        val result = raptor(lineA, lineB).plan(origin, destination, departMinute = 482).single()

        assertEquals(listOf(11, 21), result.legs.filterIsInstance<RideLeg>().map { tripId(it, lineA, lineB) })
        assertEquals(531, result.arriveMinute)
    }

    @Test
    fun `tiene anche l'itinerario con meno cambi se arriva dopo`() {
        val slowDirect = TransitPattern(3, intArrayOf(0, 3), listOf(trip(30, 481, 540)))

        val result = raptor(lineA, lineB, slowDirect).plan(origin, destination, departMinute = 475)

        assertEquals(listOf(0 to 541, 1 to 511), result.map { it.transfers to it.arriveMinute })
    }

    @Test
    fun `oltre il numero massimo di cambi nessun itinerario`() {
        assertEquals(emptyList<Itinerary>(), raptor(lineA, lineB).plan(origin, destination, departMinute = 475, maxTransfers = 0))
    }

    @Test
    fun `una fermata senza salita non fa salire`() {
        val noBoarding = TransitPattern(1, intArrayOf(0, 1), lineA.trips, canBoard = booleanArrayOf(false, true))

        assertEquals(emptyList<Itinerary>(), raptor(noBoarding, lineB).plan(origin, destination, departMinute = 475))
    }

    @Test
    fun `alla stessa fermata il cambio richiede un minuto`() {
        val fromS1 = { departure: Int -> TransitPattern(4, intArrayOf(1, 3), listOf(trip(40, departure, departure + 10))) }

        assertEquals(emptyList<Itinerary>(), raptor(lineA, fromS1(490)).plan(origin, destination, departMinute = 475))
        assertEquals(502, raptor(lineA, fromS1(491)).plan(origin, destination, departMinute = 475).single().arriveMinute)
    }

    @Test
    fun `corse dopo mezzanotte oltre i 1440 minuti`() {
        val night = TransitPattern(5, intArrayOf(0, 3), listOf(trip(50, 1445, 1470)))

        assertEquals(1471, raptor(night).plan(origin, destination, departMinute = 1430).single().arriveMinute)
    }

    @Test
    fun `fermate oltre 800 m da partenza e arrivo non si usano`() {
        val far = Place(44.990, 9.0) // circa 1,1 km da s0

        assertTrue(raptor(lineA, lineB).plan(far, destination, departMinute = 475).isEmpty())
    }

    // L'id della corsa di [leg] tra i pattern della rete, nell'ordine in cui sono stati passati.
    private fun tripId(leg: RideLeg, vararg patterns: TransitPattern) = patterns[leg.pattern].trips[leg.trip].id
}
