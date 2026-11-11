package com.pockettravel.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GenerateTransitTripStopsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun gtfs(files: Map<String, String>): File = tmp.newFile("feed.zip").also { writeZip(it, files) }

    private fun zipOf(name: String, files: Map<String, String>): File = tmp.newFile(name).also { writeZip(it, files) }

    @Test
    fun `tabelle di prima invariate per le app pubblicate, trip_stop e full_pattern in piu'`() {
        val db = tmp.root.resolve("transit.db")
        generateTransit(gtfs(feed), db, "mdb-1", monday, 7)

        // Righe che le app 0.14.0 e precedenti leggono, colonne per nome: stesse di prima della vista della corsa.
        assertEquals(
            listOf(
                listOf<Any?>(0, 480, 0, 0, 0, 0, null),
                listOf<Any?>(0, 1470, 1, 0, 0, 0, null),
                listOf<Any?>(0, 605, 2, 0, 1, 0, null),
            ),
            queryDb(db, "SELECT pattern, start, id, route, service, headsign, wheelchair FROM trip ORDER BY id"),
        )
        assertEquals(listOf(listOf<Any?>(1, 0, 0)), queryDb(db, "SELECT stop, pattern, offset FROM pattern_stop"))
        assertEquals(listOf("pattern", "start", "id", "route", "service", "headsign", "wheelchair", "full_pattern"), queryDb(db, "SELECT name FROM pragma_table_info('trip')").map { it[0] })
        assertEquals(listOf<Any?>("2"), queryDb(db, "SELECT value FROM meta WHERE key = 'format'").single())

        // T1 e T2 hanno le stesse fermate agli stessi intervalli: un pattern completo; T3 ne ha un altro.
        assertEquals(listOf(listOf<Any?>(0, 0), listOf<Any?>(1, 0), listOf<Any?>(2, 1)), queryDb(db, "SELECT id, full_pattern FROM trip ORDER BY id"))
        // S1 = 1, B = 2. T3: B senza salita prima della prima fermata utile (-5), poi S1, poi il capolinea B (arrivo 10:09).
        assertEquals(
            listOf(
                listOf<Any?>(0, 1, 1, 0, 0), listOf<Any?>(0, 2, 2, 10, 0),
                listOf<Any?>(1, 1, 2, -5, 1), listOf<Any?>(1, 2, 1, 0, 0), listOf<Any?>(1, 3, 2, 4, 0),
            ),
            decodedTripStops(db),
        )
        // Una riga per pattern completo.
        assertEquals(listOf(listOf<Any?>(2)), queryDb(db, "SELECT COUNT(*) FROM trip_stop"))
        // Tabellone e vista della corsa dicono lo stesso minuto: trip.start + offset (T3: salita a S1 alle 10:05, capolinea 10:09).
        assertEquals(
            listOf(605, 609),
            decodedTripStops(db).filter { it[0] == 1 && it[1] in 2..3 }.map { 605 + it[3] as Int },
        )
        assertEquals(listOf(listOf<Any?>("1")), queryDb(db, "SELECT value FROM meta WHERE key = 'trip_stops'"))
        assertEquals(listOf(listOf<Any?>(0)), queryDb(db, "SELECT COUNT(*) FROM meta WHERE key = 'gtfs_routes'"))
    }

    @Test
    fun `trip_stop - capolinea con l'arrivo, fermata senza discesa e fermata con orario diverso in partenza`() {
        val feed = miniFeed(
            trips = "route_id,service_id,trip_id\nR1,FERIALE,T1",
            stopTimes = """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type
                T1,08:00:00,08:00:00,A,10,0,1
                T1,08:10:00,08:12:00,B,20,0,0
                T1,08:20:00,08:21:00,C,30,0,0
            """,
        )
        val db = tmp.root.resolve("terminus.db")
        generateTransit(zipOf("terminus.zip", feed), db, "mdb-1", monday, 7)

        // Fermata B in partenza alle 08:12 (offset 12), capolinea C all'arrivo 08:20 (offset 20, non 21); seq = stop_sequence.
        assertEquals(
            listOf(listOf<Any?>(10, 0, 2), listOf<Any?>(20, 12, 0), listOf<Any?>(30, 20, 0)),
            decodedTripStops(db).map { listOf(it[1], it[3], it[4]) },
        )
        // Il tabellone resta com'era: A e B, non il capolinea.
        assertEquals(listOf(listOf<Any?>(0), listOf<Any?>(12)), queryDb(db, "SELECT offset FROM pattern_stop ORDER BY offset"))
    }

    @Test
    fun `gtfs_route con gtfsIds, direction_id e start_time delle corse solo con tripKeys`() {
        val feed = miniFeed(
            trips = "route_id,service_id,trip_id,direction_id\nR1,FERIALE,T1,1\nR1,FERIALE,T2,\nR1,FERIALE,T3,0",
            stopTimes = """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                T1,08:10:00,08:10:30,B,2
                T1,08:00:00,08:00:15,A,1
                T1,08:20:00,08:20:00,C,3
                T2,24:30:05,24:30:05,A,1
                T2,24:40:00,24:40:00,B,2
                T3,xx,xx,A,1
                T3,09:10:00,09:10:00,B,2
                T3,09:20:00,09:20:00,C,3
            """,
        )
        val zip = zipOf("ids.zip", feed)
        val db = tmp.root.resolve("ids.db")
        // tripKeys da solo implica gli id GTFS.
        generateTransit(zip, db, "mdb-1", monday, 7, tripKeys = true)

        assertEquals(listOf(listOf<Any?>(0, "R1")), queryDb(db, "SELECT id, route_id FROM gtfs_route"))
        assertEquals(
            listOf(listOf<Any?>("T1", 1, 28815), listOf<Any?>("T2", null, 88205), listOf<Any?>("T3", 0, null)),
            queryDb(db, "SELECT trip_id, direction_id, start_time FROM gtfs_trip ORDER BY trip_id"),
        )
        assertEquals(
            listOf("gtfs_ids", "gtfs_routes", "trip_keys"),
            queryDb(db, "SELECT key FROM meta WHERE key IN ('gtfs_ids', 'gtfs_routes', 'trip_keys') ORDER BY key").map { it[0] },
        )

        // Solo gtfsIds: gtfs_trip come prima, (id, trip_id), e niente trip_keys.
        val ids = tmp.root.resolve("ids-only.db")
        generateTransit(zip, ids, "mdb-1", monday, 7, gtfsIds = true)
        assertEquals(listOf("id", "trip_id"), queryDb(ids, "SELECT name FROM pragma_table_info('gtfs_trip')").map { it[0] })
        assertEquals(listOf(listOf<Any?>(0)), queryDb(ids, "SELECT COUNT(*) FROM meta WHERE key = 'trip_keys'"))
        assertEquals(listOf(listOf<Any?>(1)), queryDb(ids, "SELECT COUNT(*) FROM gtfs_route"))

        val plain = tmp.root.resolve("plain.db")
        generateTransit(zip, plain, "mdb-1", monday, 7)
        assertEquals(listOf(listOf<Any?>(0)), queryDb(plain, "SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'gtfs_%'"))
    }

    @Test
    fun `encodeTripStops - byte esatti con delta negativi di fermata e di offset`() {
        // (seq, fermata, offset, flags): offset -5, 10, 4 (ultimo delta -6) e fermate 5, 3, 700 (primo delta -2).
        val bytes = encodeTripStops(intArrayOf(1, 5, -5, 1, 2, 3, 10, 0, 200, 700, 4, 2))
        val expected = intArrayOf(
            0x01, 0x0A, 0x09, 0x01, // seq +1, stop +5 (zigzag 10), offset -5 (zigzag 9), senza salita
            0x01, 0x03, 0x1E, 0x00, // seq +1, stop -2 (zigzag 3), offset +15 (zigzag 30)
            0xC6, 0x01, 0xF2, 0x0A, 0x0B, 0x02, // seq +198 (due byte), stop +697 (zigzag 1394, due byte), offset -6 (zigzag 11), senza discesa
        ).map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, bytes)
        assertEquals(0, encodeTripStops(IntArray(0)).size)
    }
}
