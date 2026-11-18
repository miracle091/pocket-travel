package com.pockettravel.core.transitrouting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitDbTest {
    // Come encodeTripStops della pipeline: varint(seq), varint(zigzag(stop)), varint(zigzag(offset)), flags, a differenze.
    private fun blob(vararg stops: Triple<Int, Int, Int>): ByteArray {
        val out = mutableListOf<Byte>()
        fun varint(value: Int) {
            var v = value
            while (v and 0x7F.inv() != 0) {
                out += ((v and 0x7F) or 0x80).toByte()
                v = v ushr 7
            }
            out += v.toByte()
        }
        fun zigzag(n: Int) = varint((n shl 1) xor (n shr 31))
        var seq = 0
        var stop = 0
        var offset = 0
        stops.forEachIndexed { i, (id, minutes, flags) ->
            varint(i - seq).also { seq = i }
            zigzag(id - stop).also { stop = id }
            zigzag(minutes - offset).also { offset = minutes }
            out += flags.toByte()
        }
        return out.toByteArray()
    }

    private fun days(vararg on: Int) = ByteArray(2).also { bytes -> on.forEach { bytes[it / 8] = (bytes[it / 8].toInt() or (1 shl (it % 8))).toByte() } }

    private val stops = listOf(DbStop(10, 45_000_000, 9_000_000, null), DbStop(11, 45_010_000, 9_000_000, 10), DbStop(12, 45_020_000, 9_000_000, null))

    // Pattern 7: fermate 10, 11, 12 a 0, 10 e 20 minuti; nella 12 non si sale, nella 10 non si scende.
    private val tripStops = mapOf(7 to blob(Triple(10, 0, 2), Triple(11, 10, 0), Triple(12, 20, 1)))

    @Test
    fun `corse del giorno e del giorno prima dopo mezzanotte, nella finestra`() {
        val trips = listOf(
            DbTrip(1, route = 5, start = 480, serviceDays = days(3), fullPattern = 7), // oggi alle 8
            DbTrip(2, route = 5, start = 1430, serviceDays = days(2), fullPattern = 7), // ieri alle 23.50: oggi da -10 a 10
            DbTrip(3, route = 5, start = 900, serviceDays = days(3), fullPattern = 7), // oggi alle 15, fuori dalla finestra
            DbTrip(4, route = 5, start = 480, serviceDays = days(4), fullPattern = 7), // domani
        )

        val day = dayNetwork(stops, tripStops, trips, dayIndex = 3, fromMinute = 0, toMinute = 600)

        val pattern = day.network.patterns.single()
        assertEquals(listOf(2, 1), pattern.trips.map { it.id })
        assertArrayEquals(intArrayOf(-10, 0, 10), pattern.trips[0].departures)
        assertArrayEquals(intArrayOf(480, 490, 500), pattern.trips[1].arrivals)
        assertEquals(listOf(true, true, false), pattern.canBoard?.toList())
        assertEquals(listOf(false, true, true), pattern.canAlight?.toList())
        assertEquals(5, pattern.routeId)
        assertArrayEquals(intArrayOf(10, 11, 12), day.stopIds)
    }

    @Test
    fun `la banchina e la sua stazione hanno la stessa chiave`() {
        val day = dayNetwork(stops, tripStops, emptyList(), dayIndex = 0, fromMinute = 0, toMinute = 1440)

        assertEquals(day.network.stops[0].parent, day.network.stops[1].parent)
        assertTrue(day.network.stops[2].parent != day.network.stops[0].parent)
    }

    @Test
    fun `corse con una fermata che non c'e' o senza blob si saltano`() {
        val broken = mapOf(8 to blob(Triple(10, 0, 0), Triple(99, 5, 0)))
        val trips = listOf(DbTrip(1, 5, 480, days(0), fullPattern = 8), DbTrip(2, 5, 480, days(0), fullPattern = 9))

        assertEquals(0, dayNetwork(stops, broken, trips, dayIndex = 0, fromMinute = 0, toMinute = 1440).network.patterns.size)
    }

    @Test
    fun `il blob letto torna uguale`() {
        val raw = checkNotNull(decodeTripStops(blob(Triple(10, 0, 2), Triple(11, -3, 0))))

        assertEquals(listOf(10 to 0, 11 to -3), raw.map { it.stop to it.offset })
        assertEquals(listOf(false, true), raw.map { it.dropOff })
    }
}
