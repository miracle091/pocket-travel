package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Lettura di feed GTFS-RT registrati il 2026-10-22 dalle tre reti pilota (src/test/resources/gtfs-rt, ridotti alle prime
 * entita' piu' quelle con soppressioni e fermate saltate). I valori attesi vengono da una lettura indipendente del
 * protobuf (script Python sul wire format).
 */
class GtfsRealtimeDecoderTest {
    private fun recorded(name: String): RealtimeFeed =
        decodeTripUpdates(checkNotNull(javaClass.getResourceAsStream("/gtfs-rt/$name")) { "manca $name" }.use { it.readBytes() })

    private val RealtimeFeed.all get() = trips.values.flatten()

    @Test
    fun `Renfe Cercanias - solo l'arrivo alla prossima fermata e il ritardo della corsa`() {
        val feed = recorded("renfe-cercanias.pb")
        assertEquals(Instant.ofEpochSecond(1_792_656_875), feed.timestamp)
        assertEquals(43, feed.all.size)
        assertEquals(listOf("5193J35539R3"), feed.all.filter { it.cancelled }.map { it.tripId })
        assertEquals(46, feed.all.sumOf { it.stops.size })
        assertEquals(4, feed.all.sumOf { trip -> trip.stops.count { it.skipped } })
        assertEquals(9540, feed.all.sumOf { trip -> trip.stops.sumOf { it.delaySeconds ?: 0 } })
        assertEquals(9540, feed.all.sumOf { it.delaySeconds ?: 0 })
        val first = feed.trips.getValue("3093J35713C5").single()
        assertNull(first.startDate)
        // Solo l'arrivo: fromArrival.
        assertEquals(RealtimeStop("43004", skipped = false, delaySeconds = 0, timeEpochSeconds = 1_792_658_820, fromArrival = true), first.stops.single())
        // Ritardo negativo (int32 come varint di 10 byte).
        assertEquals(-60, feed.trips.getValue("3093J23529C1").single().stops.single().delaySeconds)
    }

    @Test
    fun `SNCF - partenze per fermata, fermate saltate e corse soppresse con la data`() {
        val feed = recorded("sncf.pb")
        assertEquals(30, feed.all.size)
        assertEquals(2, feed.all.count { it.cancelled })
        assertEquals(271, feed.all.sumOf { it.stops.size })
        assertEquals(35, feed.all.sumOf { trip -> trip.stops.count { it.skipped } })
        assertEquals(129_840, feed.all.sumOf { trip -> trip.stops.sumOf { it.delaySeconds ?: 0 } })
        assertTrue(feed.all.all { it.startDate == LocalDate.parse("2026-10-22") && it.delaySeconds == null })
        val first = feed.trips.getValue("OCESN5300F1187_F:OUI:FR:Line::3544567F-7F17-4253-85BB-D1E6A7B27A9A::87481002:87688887:8:1121:20261022").single()
        assertEquals(RealtimeStop("StopPoint:OCETGV INOUI-87484006", false, 0, 1_792_639_740), first.stops[1])
    }

    @Test
    fun `Autolinee Toscane - fermate NO_DATA, arrivi e partenze`() {
        val feed = recorded("autolinee-toscane.pb")
        assertEquals(25, feed.all.size)
        assertEquals(0, feed.all.count { it.cancelled })
        assertEquals(335, feed.all.sumOf { it.stops.size })
        assertEquals(25, feed.all.sumOf { trip -> trip.stops.count { it.noData } })
        assertEquals(6372, feed.all.sumOf { trip -> trip.stops.sumOf { it.delaySeconds ?: 0 } })
        assertEquals(1114, feed.all.sumOf { it.delaySeconds ?: 0 })
        val first = feed.trips.getValue("10070-8").single()
        assertEquals(LocalDate.parse("2026-10-22"), first.startDate)
        assertEquals(142, first.delaySeconds)
        // Alla fermata 156969: arrivo previsto con 194 s di ritardo, cioe' +3 minuti nel tabellone.
        val stop = first.stops.first { it.stopId == "156969" }
        val key = RealtimeKey("10070-8", "156969", LocalDate.parse("2026-10-22"), checkNotNull(stop.timeEpochSeconds) - 194)
        assertEquals(DepartureRealtime.Delay(3), realtimeOf(key, feed))
    }

    @Test
    fun `feed senza header rifiutato, campi sconosciuti saltati, byte non validi rifiutati`() {
        // Corpo vuoto (Autolinee Toscane risponde cosi' a volte): non e' un feed senza ritardi. Il solo header si'.
        assertThrows(IllegalArgumentException::class.java) { decodeTripUpdates(ByteArray(0)) }
        assertEquals(RealtimeFeed(null, emptyMap()), decodeTripUpdates(byteArrayOf(0x0A, 0x00)))
        // header { timestamp: 5 } + campo 9 sconosciuto (varint) + campo 10 sconosciuto (fixed32) + campo 11 (fixed64).
        val unknown = byteArrayOf(0x0A, 0x02, 0x18, 0x05, 0x48, 0x01, 0x55, 1, 2, 3, 4, 0x59, 1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(Instant.ofEpochSecond(5), decodeTripUpdates(unknown).timestamp)
        val bytes = checkNotNull(javaClass.getResourceAsStream("/gtfs-rt/renfe-cercanias.pb")).use { it.readBytes() }
        assertThrows(IllegalArgumentException::class.java) { decodeTripUpdates(bytes.copyOf(bytes.size - 3)) }
        // Una pagina HTML d'errore al posto del feed.
        assertThrows(IllegalArgumentException::class.java) { decodeTripUpdates("<html>503</html>".toByteArray()) }
        // Lunghezza oltre la fine del messaggio.
        assertThrows(IllegalArgumentException::class.java) { decodeTripUpdates(byteArrayOf(0x12, 0x7F, 0x00)) }
    }

    @Test
    fun `un campo noto con un tipo inatteso e i gruppi si saltano, i gruppi annidati troppo no`() {
        // Campo 1 (header) come varint invece che messaggio, poi un gruppo (campo 7) con dentro un varint, poi l'header.
        val bytes = byteArrayOf(0x08, 0x05, 0x3B, 0x08, 0x01, 0x3C, 0x0A, 0x02, 0x18, 0x09)
        assertEquals(Instant.ofEpochSecond(9), decodeTripUpdates(bytes).timestamp)
        val nested = ByteArray(40) { 0x3B } + ByteArray(40) { 0x3C }
        assertThrows(IllegalArgumentException::class.java) { decodeTripUpdates(nested) }
        // header.timestamp oltre i limiti di Instant (Long.MAX_VALUE): il feed resta leggibile, senza ora.
        assertNull(decodeTripUpdates(byteArrayOf(0x0A, 0x0A, 0x18) + ByteArray(8) { 0xFF.toByte() } + byteArrayOf(0x7F)).timestamp)
        // TripUpdate { trip { trip_id "T", schedule_relationship DELETED (7) } }: corsa da togliere, non soppressa.
        val deleted = decodeTripUpdates(byteArrayOf(0x0A, 0x00, 0x12, 0x09, 0x1A, 0x07, 0x0A, 0x05, 0x0A, 0x01, 0x54, 0x20, 0x07)).trips.getValue("T").single()
        assertTrue(deleted.deleted && !deleted.cancelled)
        // Varint oltre 10 byte.
        assertThrows(IllegalArgumentException::class.java) { decodeTripUpdates(byteArrayOf(0x08) + ByteArray(11) { 0x80.toByte() } + byteArrayOf(0x01)) }
    }
}
