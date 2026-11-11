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

    private fun field(key: Int, body: ByteArray): ByteArray {
        var length = body.size
        val prefix = mutableListOf(key.toByte())
        while (length >= 0x80) {
            prefix += ((length and 0x7F) or 0x80).toByte()
            length = length ushr 7
        }
        prefix += length.toByte()
        return prefix.toByteArray() + body
    }

    // FeedMessage { header, entity { trip_update { trip { trip_id }, stop_time_update * stopUpdates } } * trips }.
    private fun entity(tripId: String, stopUpdate: ByteArray, count: Int): ByteArray =
        field(0x12, field(0x1A, field(0x0A, field(0x0A, tripId.toByteArray())) + stopUpdate.times(count)))

    private fun ByteArray.times(count: Int): ByteArray {
        val out = ByteArray(size * count)
        repeat(count) { copyInto(out, it * size) }
        return out
    }

    private val header = byteArrayOf(0x0A, 0x00)
    private val stopWithId = field(0x12, field(0x22, "S".toByteArray()))
    private val emptyStop = byteArrayOf(0x12, 0x00)

    @Test
    fun `gli StopTimeUpdate vuoti non fanno una fermata e non contano nei tetti`() {
        val feed = decodeTripUpdates(header + entity("T", emptyStop, 5_000) + entity("U", emptyStop + stopWithId, 1))
        assertEquals(0, feed.trips.getValue("T").single().stops.size)
        assertEquals(1, feed.trips.getValue("U").single().stops.size)
        // NO_DATA senza fermata (schedule_relationship 2) e' un'informazione: si tiene.
        val noData = field(0x12, byteArrayOf(0x28, 0x02))
        assertTrue(decodeTripUpdates(header + entity("T", noData, 1)).trips.getValue("T").single().stops.single().noData)
    }

    @Test
    fun `oltre i tetti di fermate per corsa e di fermate in tutto il feed e' rifiutato`() {
        assertEquals(1_000, decodeTripUpdates(header + entity("T", stopWithId, 1_000)).trips.getValue("T").single().stops.size)
        assertThrows(IllegalArgumentException::class.java) { decodeTripUpdates(header + entity("T", stopWithId, 1_001)) }
        val trip = entity("T", stopWithId, 1_000)
        assertThrows(IllegalArgumentException::class.java) { decodeTripUpdates(header + trip.times(1_001)) }
    }

    @Test
    fun `oltre il tetto di corse il feed e' rifiutato`() {
        val trip = entity("T", stopWithId, 0)
        assertThrows(IllegalArgumentException::class.java) { decodeTripUpdates(header + trip.times(200_001)) }
    }

    private fun str(key: Int, value: String) = field(key, value.toByteArray())

    private fun num(key: Int, value: Long): ByteArray {
        val out = mutableListOf(key.toByte())
        var rest = value
        while (rest >= 0x80) {
            out += ((rest and 0x7F) or 0x80).toByte()
            rest = rest ushr 7
        }
        out += rest.toByte()
        return out.toByteArray()
    }

    // TranslatedString { translation { text, language } * translations }.
    private fun translated(vararg translations: Pair<String, String?>): ByteArray =
        translations.fold(ByteArray(0)) { acc, (text, language) ->
            acc + field(0x0A, str(0x0A, text) + (language?.let { str(0x12, it) } ?: ByteArray(0)))
        }

    // FeedMessage entity { id, [is_deleted], alert { body } }.
    private fun alertEntity(body: ByteArray, deleted: Boolean = false): ByteArray =
        field(0x12, (if (deleted) num(0x10, 1) else ByteArray(0)) + field(0x2A, body))

    private fun headerText(text: String) = field(0x52, translated(text to null))

    private fun alertsOf(vararg entities: ByteArray) = decodeTripUpdates(header + entities.fold(ByteArray(0)) { a, e -> a + e }).alerts

    @Test
    fun `un avviso con due traduzioni sceglie la lingua dell'app, poi l'inglese, poi senza lingua, poi la prima`() {
        val body = field(0x52, translated("Sciopero" to "it", "Strike" to "en", "Streik" to null)) +
            field(0x5A, translated("Dettagli" to "it")) + field(0x42, translated("https://example.org/it" to "it"))
        val alert = alertsOf(alertEntity(body)).single()
        val title = checkNotNull(alert.header)
        assertEquals(3, title.translations.size)
        assertEquals("Sciopero", title.pick("it"))
        assertEquals("Sciopero", title.pick("IT-it"))
        assertEquals("Strike", title.pick("fr"))
        assertEquals("Dettagli", alert.description?.pick("de"))
        assertEquals("https://example.org/it", alert.url?.pick("en"))
        assertEquals("Streik", TransitText(listOf(TransitTranslation("Deux", "fr"), TransitTranslation("Streik", null))).pick("de"))
        assertEquals("Deux", TransitText(listOf(TransitTranslation("Deux", "fr"), TransitTranslation("Drei", "es"))).pick("de"))
        assertNull(TransitText(emptyList()).pick("it"))
    }

    @Test
    fun `active_period con un estremo solo, cause, effect e severity`() {
        val body = headerText("H") + field(0x0A, num(0x08, 1_792_000_000)) + field(0x0A, num(0x10, 1_793_000_000)) +
            field(0x0A, byteArrayOf()) + num(0x30, 9) + num(0x38, 1) + num(0x70, 3)
        val alert = alertsOf(alertEntity(body)).single()
        assertEquals(
            listOf(
                TransitActivePeriod(Instant.ofEpochSecond(1_792_000_000), null),
                TransitActivePeriod(null, Instant.ofEpochSecond(1_793_000_000)),
                TransitActivePeriod(null, null),
            ),
            alert.activePeriods,
        )
        assertEquals(Triple(9, 1, 3), Triple(alert.cause, alert.effect, alert.severity))
        assertNull(alertsOf(alertEntity(headerText("H"))).single().cause)
    }

    @Test
    fun `informed_entity per linea, fermata e corsa`() {
        val trip = str(0x0A, "T1") + str(0x12, "08:30:00") + str(0x1A, "20261111") + str(0x2A, "R9") + num(0x30, 1)
        val body = headerText("H") + field(0x2A, str(0x0A, "A") + str(0x12, "R1") + num(0x18, 3) + num(0x30, 0)) +
            field(0x2A, str(0x2A, "S1")) + field(0x2A, field(0x22, trip))
        val entities = alertsOf(alertEntity(body)).single().informedEntities
        assertEquals(
            listOf(
                TransitInformedEntity(agencyId = "A", routeId = "R1", routeType = 3, directionId = 0),
                TransitInformedEntity(stopId = "S1"),
                TransitInformedEntity(tripId = "T1", tripStartTime = "08:30:00", tripStartDate = "20261111", tripRouteId = "R9", tripDirectionId = 1),
            ),
            entities,
        )
    }

    @Test
    fun `avvisi e aggiornamenti delle corse nello stesso feed`() {
        val feed = decodeTripUpdates(header + entity("T", stopWithId, 1) + alertEntity(headerText("H")) + entity("U", stopWithId, 1))
        assertEquals(setOf("T", "U"), feed.trips.keys)
        assertEquals("H", feed.alerts.single().header?.pick("it"))
        assertTrue(feed.tripsWithoutId.isEmpty())
    }

    @Test
    fun `un avviso is_deleted si scarta, come uno senza titolo ne' descrizione`() {
        assertTrue(alertsOf(alertEntity(headerText("H"), deleted = true)).isEmpty())
        assertTrue(alertsOf(alertEntity(field(0x2A, str(0x2A, "S1")))).isEmpty())
    }

    @Test
    fun `oltre i tetti degli avvisi si salta, senza rifiutare il feed`() {
        val one = alertEntity(headerText("H"))
        assertEquals(2_000, alertsOf(one.times(2_001)).size)
        val entities = headerText("H") + field(0x2A, str(0x2A, "S")).times(501)
        assertEquals(500, alertsOf(alertEntity(entities)).single().informedEntities.size)
        val translations = field(0x52, translated(*Array(21) { "t$it" to "l$it" }))
        assertEquals(20, alertsOf(alertEntity(translations)).single().header?.translations?.size)
        // Una traduzione oltre 4.000 caratteri si salta; se e' l'unica, l'avviso non ha niente da mostrare.
        val long = "x".repeat(4_001)
        assertEquals(listOf("ok"), alertsOf(alertEntity(field(0x52, translated(long to "it", "ok" to "en")))).single().header?.translations?.map { it.text })
        assertTrue(alertsOf(alertEntity(field(0x52, translated(long to "it")))).isEmpty())
        assertEquals(4_000, alertsOf(alertEntity(field(0x52, translated("x".repeat(4_000) to "it")))).single().header?.translations?.single()?.text?.length)
    }

    // TripUpdate { trip { start_time, start_date, route_id, direction_id }, stop_time_update { stop_sequence, stop_id } }.
    private fun hslEntity(tripDescriptor: ByteArray, stopUpdate: ByteArray = ByteArray(0)): ByteArray =
        field(0x12, field(0x1A, field(0x0A, tripDescriptor) + stopUpdate))

    @Test
    fun `una corsa senza trip_id si tiene con linea, direzione, ora e giorno (HSL)`() {
        val trip = str(0x12, "25:10:00") + str(0x1A, "20261111") + str(0x2A, "2550") + num(0x30, 1)
        val stop = field(0x12, num(0x08, 7) + str(0x22, "1020"))
        val feed = decodeTripUpdates(header + hslEntity(trip, stop) + entity("T", stopWithId, 1))
        assertEquals(setOf("T"), feed.trips.keys)
        val hsl = feed.tripsWithoutId.single()
        assertEquals("", hsl.tripId)
        assertEquals("2550", hsl.routeId)
        assertEquals(1, hsl.directionId)
        assertEquals("25:10:00", hsl.startTime)
        assertEquals(LocalDate.parse("2026-11-11"), hsl.startDate)
        assertEquals(RealtimeStop("1020", false, null, null, stopSequence = 7), hsl.stops.single())
        assertNull(feed.trips.getValue("T").single().routeId)
    }

    @Test
    fun `una corsa senza trip_id ne' linea, ora e giorno si scarta`() {
        val withoutTime = str(0x1A, "20261111") + str(0x2A, "2550")
        val withoutRoute = str(0x12, "08:00:00") + str(0x1A, "20261111")
        val withoutDate = str(0x12, "08:00:00") + str(0x2A, "2550")
        val feed = decodeTripUpdates(header + hslEntity(withoutTime) + hslEntity(withoutRoute) + hslEntity(withoutDate) + hslEntity(ByteArray(0)))
        assertTrue(feed.trips.isEmpty() && feed.tripsWithoutId.isEmpty())
    }
}
