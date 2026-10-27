package com.pockettravel.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.sql.DriverManager
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GenerateTransitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun gtfs(files: Map<String, String>): File = tmp.newFile("feed.zip").also { zip ->
        ZipOutputStream(zip.outputStream()).use { out ->
            files.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.trimIndent().toByteArray())
                out.closeEntry()
            }
        }
    }

    // Lunedi' 2026-10-05: finestra di 7 giorni fino a domenica 11.
    private val monday = LocalDate.parse("2026-10-05")

    private val feed = mapOf(
        "agency.txt" to """
            agency_id,agency_name,agency_url,agency_timezone
            a,Rete,https://example.invalid,Europe/Riga
        """,
        "stops.txt" to """
            stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station
            S,Stazione Centrale,56.9460,24.1210,1,
            S1,"Stazione Centrale, binario 1",56.9461,24.1211,0,S
            B,Piazza,56.9500,24.1300,0,
            E,Entrata,56.9462,24.1212,2,S
        """,
        "routes.txt" to """
            route_id,route_short_name,route_long_name,route_type,route_color
            R1,1,Centro - Piazza,3,FF0000
        """,
        "trips.txt" to """
            route_id,service_id,trip_id,trip_headsign
            R1,FERIALE,T1,Piazza
            R1,FERIALE,T2,
            R1,FESTIVO,T3,Piazza
            R1,VECCHIO,T4,Piazza
        """,
        "stop_times.txt" to """
            trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type
            T1,08:00:00,08:00:00,S1,1,0
            T1,08:10:00,08:10:00,B,2,0
            T2,24:30:00,24:30:00,S1,1,
            T2,24:40:00,24:40:00,B,2,
            T3,10:00:00,10:00:00,B,1,1
            T3,10:05:00,10:05:00,S1,2,0
            T3,10:09:00,10:09:00,B,3,0
            T4,09:00:00,09:00:00,S1,1,0
            T4,09:10:00,09:10:00,B,2,0
        """,
        "calendar.txt" to """
            service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
            FERIALE,1,1,1,1,1,0,0,20260101,20261231
            FESTIVO,0,0,0,0,0,1,1,20260101,20261231
            VECCHIO,1,1,1,1,1,1,1,20250101,20251231
        """,
        "calendar_dates.txt" to """
            service_id,date,exception_type
            FERIALE,20261007,2
            FESTIVO,20261008,1
        """,
    )

    @Test
    fun `orari, fermate e servizi nello schema calendario`() {
        val db = tmp.root.resolve("transit.db")
        val stats = generateTransit(gtfs(feed), db, "mdb-1", monday, 7)

        // Stazione e banchina sono fermate (l'entrata no); la corsa T4 ha un servizio fuori dalla finestra.
        assertEquals(3, stats.stops)
        assertEquals(3, stats.trips)
        // Il servizio festivo c'e' fino a domenica 11 (FERIALE finisce venerdi' 9).
        assertEquals(LocalDate.parse("2026-10-11"), stats.validUntil)

        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
            fun query(sql: String): List<List<Any?>> = conn.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) }) } }
            }
            val meta = query("SELECT key, value FROM meta").associate { it[0] to it[1] }
            assertEquals("Europe/Riga", meta["timezone"])
            assertEquals("20261005", meta["window_start"])
            assertEquals("20261011", meta["valid_until"])

            // Feed senza colonne wheelchair_*: tutto NULL.
            assertEquals(listOf(listOf<Any?>(0, 0)), query("SELECT (SELECT COUNT(wheelchair) FROM stop), (SELECT COUNT(wheelchair) FROM trip)"))

            // La banchina ha come parent la stazione.
            assertEquals(listOf(listOf("Stazione Centrale, binario 1", 0)), query("SELECT name, parent FROM stop WHERE parent IS NOT NULL"))

            // Destinazione di T2 ricavata dall'ultima fermata; T1 e T3 con la loro.
            assertEquals(listOf("Piazza"), query("SELECT DISTINCT h.text FROM trip t JOIN headsign h ON h.id = t.headsign").map { it[0] })

            // Partenze: capolinea d'arrivo escluso, T3 senza salita alla prima fermata, T2 dopo mezzanotte (1470).
            assertEquals(
                listOf(listOf("Stazione Centrale, binario 1", 480), listOf("Stazione Centrale, binario 1", 605), listOf("Stazione Centrale, binario 1", 1470)),
                query("SELECT s.name, t.start + ps.offset AS minute FROM pattern_stop ps JOIN trip t ON t.pattern = ps.pattern JOIN stop s ON s.id = ps.stop ORDER BY minute"),
            )

            // Le tre corse partono tutte solo da S1 (il resto e' capolinea o senza salita): un solo pattern.
            assertEquals(listOf(listOf<Any?>(1)), query("SELECT COUNT(DISTINCT pattern) FROM trip"))

            // Feriale: lun, mar, gio, ven (mercoledi' 7 tolto); festivo: gio 8 aggiunto, sab, dom.
            val days = query("SELECT days FROM service ORDER BY id").map { it[0] as ByteArray }
            assertArrayEquals(byteArrayOf(0b0011011), days[0])
            assertArrayEquals(byteArrayOf(0b1101000), days[1])
        }
    }

    @Test
    fun `id GTFS di corse e fermate solo con gtfsIds, per le corse scritte`() {
        val plain = tmp.root.resolve("plain.db")
        generateTransit(gtfs(feed), plain, "mdb-1", monday, 7)
        DriverManager.getConnection("jdbc:sqlite:${plain.path}").use { conn ->
            conn.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'gtfs_%'").use { rs -> assertEquals(0, rs.getInt(1)) }
                s.executeQuery("SELECT COUNT(*) FROM meta WHERE key = 'gtfs_ids'").use { rs -> assertEquals(0, rs.getInt(1)) }
            }
        }

        val db = tmp.root.resolve("rt.db")
        generateTransit(tmp.root.resolve("feed.zip"), db, "mdb-1", monday, 7, gtfsIds = true)
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
            fun query(sql: String): List<List<Any?>> = conn.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) }) } }
            }
            assertEquals(listOf(listOf<Any?>("1")), query("SELECT value FROM meta WHERE key = 'gtfs_ids'"))
            // T4 ha il servizio fuori dalla finestra e non e' in trip: nemmeno in gtfs_trip.
            assertEquals(
                listOf("T1", "T2", "T3"),
                query("SELECT g.trip_id FROM trip t JOIN gtfs_trip g ON g.id = t.id ORDER BY g.trip_id").map { it[0] },
            )
            assertEquals(listOf(listOf<Any?>(0)), query("SELECT COUNT(*) FROM gtfs_trip WHERE id NOT IN (SELECT id FROM trip)"))
            // Ogni fermata con il suo stop_id, l'entrata (location_type 2) non c'e'.
            assertEquals(
                listOf(listOf("B", "Piazza"), listOf("S", "Stazione Centrale"), listOf("S1", "Stazione Centrale, binario 1")),
                query("SELECT g.stop_id, s.name FROM stop s JOIN gtfs_stop g ON g.id = s.id ORDER BY g.stop_id"),
            )
        }
    }

    @Test
    fun `accessibilita' in sedia a rotelle di fermate e corse, null se non indicata`() {
        val accessible = feed + mapOf(
            "stops.txt" to """
                stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station,wheelchair_boarding
                S,Stazione Centrale,56.9460,24.1210,1,,0
                S1,Binario 1,56.9461,24.1211,0,S,1
                B,Piazza,56.9500,24.1300,0,,2
            """,
            "trips.txt" to """
                route_id,service_id,trip_id,trip_headsign,wheelchair_accessible
                R1,FERIALE,T1,Piazza,1
                R1,FERIALE,T2,Piazza,2
                R1,FESTIVO,T3,Piazza,0
                R1,VECCHIO,T4,Piazza,1
            """,
        )
        val db = tmp.root.resolve("transit.db")
        generateTransit(gtfs(accessible), db, "mdb-1", monday, 7)

        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
            fun query(sql: String): List<List<Any?>> = conn.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) }) } }
            }
            // 0 e vuoto = nessuna informazione: NULL, non "no".
            assertEquals(
                listOf(listOf<Any?>("Binario 1", 1), listOf<Any?>("Piazza", 2), listOf<Any?>("Stazione Centrale", null)),
                query("SELECT name, wheelchair FROM stop ORDER BY name"),
            )
            assertEquals(listOf<List<Any?>>(listOf(1), listOf(2), listOf(null)), query("SELECT wheelchair FROM trip ORDER BY service, id"))
            assertEquals("1", query("SELECT value FROM meta WHERE key = 'wheelchair'").single()[0])
            assertEquals("2", query("SELECT value FROM meta WHERE key = 'format'").single()[0])
        }
    }

    @Test
    fun `fermate, linee e corse ripetute nel feed contano una volta`() {
        val repeated = feed + mapOf(
            "stops.txt" to feed.getValue("stops.txt").trimEnd() + "\n            B,Piazza di nuovo,56.9500,24.1300,0,\n",
            "routes.txt" to feed.getValue("routes.txt").trimEnd() + "\n            R1,1,Doppia,3,FF0000\n",
            "trips.txt" to feed.getValue("trips.txt").trimEnd() + "\n            R1,FERIALE,T1,Doppia\n",
        )
        val stats = generateTransit(gtfs(repeated), tmp.root.resolve("transit.db"), "mdb-1", monday, 7)

        assertEquals(3, stats.stops)
        assertEquals(3, stats.trips)
    }

    @Test
    fun `nessun servizio nella finestra, nessuna data di fine`() {
        val db = tmp.root.resolve("transit.db")
        val stats = generateTransit(gtfs(feed), db, "mdb-1", LocalDate.parse("2030-01-01"), 7)
        assertEquals(0, stats.trips)
        assertNull(stats.validUntil)
    }

    @Test
    fun `minuti GTFS e bit dei giorni`() {
        assertEquals(1470, gtfsMinutes("24:30:00"))
        assertEquals(5, gtfsMinutes(" 0:05:00"))
        assertNull(gtfsMinutes(""))
        assertArrayEquals(byteArrayOf(0b101, 0b1), dayBits(booleanArrayOf(true, false, true, false, false, false, false, false, true)))
    }

    @Test
    fun `CSV con BOM, virgolette e virgole nei campi`() {
        val rows = mutableListOf<Map<String, String>>()
        readCsv(BufferedReader(StringReader("﻿a,b\r\n\"x, \"\"y\"\"\",2\r\n\r\n3,\n"))) { rows += it }
        assertEquals(listOf(mapOf("a" to "x, \"y\"", "b" to "2"), mapOf("a" to "3", "b" to "")), rows)
    }

    // --- generateTransit: limiti, cartelle nello zip, calendari, filtri ---

    private fun zipOf(name: String, files: Map<String, String>): File = tmp.newFile(name).also { zip ->
        ZipOutputStream(zip.outputStream()).use { out ->
            files.forEach { (entry, text) ->
                out.putNextEntry(ZipEntry(entry))
                out.write(text.trimIndent().toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
    }

    private fun queryDb(db: File, sql: String): List<List<Any?>> =
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
            conn.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) }) } }
            }
        }

    private val feedStops = """
        stop_id,stop_name,stop_lat,stop_lon
        A,Alfa,56.0000,24.0000
        B,Beta,56.0100,24.0100
        C,Gamma,56.0200,24.0200
    """

    private val feedCalendar = """
        service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
        FERIALE,1,1,1,1,1,0,0,20260101,20261231
    """

    private fun miniFeed(
        trips: String,
        stopTimes: String,
        stops: String = feedStops,
        routes: String = "route_id,route_type\nR1,3",
        calendar: String = feedCalendar,
    ) = mapOf(
        "stops.txt" to stops,
        "routes.txt" to routes,
        "trips.txt" to trips,
        "stop_times.txt" to stopTimes,
        "calendar.txt" to calendar,
    )

    @Test
    fun `windowDays fuori da 1 e 366 e' rifiutato, i due estremi sono accettati`() {
        listOf(0, -1, 367).forEach { days ->
            assertThrows(IllegalArgumentException::class.java) {
                generateTransit(zipOf("fuori-$days.zip", feed), tmp.root.resolve("fuori-$days.db"), "mdb-1", monday, days)
            }
        }
        val one = tmp.root.resolve("uno.db")
        val year = tmp.root.resolve("anno.db")
        assertEquals(monday, generateTransit(zipOf("uno.zip", feed), one, "mdb-1", monday, 1).validUntil)
        generateTransit(zipOf("anno.zip", feed), year, "mdb-1", monday, 366)

        assertEquals(listOf(listOf<Any?>(1)), queryDb(one, "SELECT LENGTH(days) FROM service ORDER BY id LIMIT 1"))
        assertEquals(listOf(listOf<Any?>(46)), queryDb(year, "SELECT LENGTH(days) FROM service ORDER BY id LIMIT 1"))
        assertEquals("1", queryDb(one, "SELECT value FROM meta WHERE key = 'window_days'").single()[0])
    }

    @Test
    fun `i file del feed possono stare in una sottocartella dello zip`() {
        val stats = generateTransit(zipOf("sub.zip", feed.mapKeys { "feed/${it.key}" }), tmp.root.resolve("sub.db"), "mdb-1", monday, 7)

        assertEquals(3, stats.stops)
        assertEquals(3, stats.trips)
    }

    @Test
    fun `statistiche del feed di prova - linee, orari e rettangolo delle fermate`() {
        val stats = generateTransit(zipOf("stat.zip", feed), tmp.root.resolve("stat.db"), "mdb-1", monday, 7)

        assertEquals(1, stats.routes)
        assertEquals(3, stats.stopTimes)
        // Ordine: lon minima, lat minima, lon massima, lat massima; l'entrata (location_type 2) non conta.
        assertArrayEquals(doubleArrayOf(24.1210, 56.9460, 24.1300, 56.9500), stats.bbox, 1e-9)
    }

    @Test
    fun `senza fermate il rettangolo e' nullo`() {
        val noStops = miniFeed(
            trips = "route_id,service_id,trip_id\nR1,FERIALE,T1",
            stopTimes = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,08:00:00,08:00:00,A,1",
            stops = "stop_id,stop_name,stop_lat,stop_lon",
        )
        val stats = generateTransit(zipOf("nostops.zip", noStops), tmp.root.resolve("nostops.db"), "mdb-1", monday, 7)

        assertEquals(0, stats.stops)
        assertEquals(0, stats.trips)
        assertNull(stats.bbox)
    }

    @Test
    fun `senza calendar_txt bastano le date aggiunte di calendar_dates, quelle fuori finestra si ignorano`() {
        val onlyDates = miniFeed(
            trips = "route_id,service_id,trip_id\nR1,X,T1",
            stopTimes = """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                T1,08:00:00,08:00:00,A,1
                T1,08:10:00,08:10:00,B,2
            """,
        ).minus("calendar.txt") + ("calendar_dates.txt" to "service_id,date,exception_type\nX,20261006,1\nX,20260101,1\nX,20261030,1\nX,20261007,3")
        val db = tmp.root.resolve("dates.db")
        val stats = generateTransit(zipOf("dates.zip", onlyDates), db, "mdb-1", monday, 7)

        assertEquals(1, stats.trips)
        assertEquals(LocalDate.parse("2026-10-06"), stats.validUntil)
        assertArrayEquals(byteArrayOf(0b10), queryDb(db, "SELECT days FROM service").single()[0] as ByteArray)
    }

    @Test
    fun `la data di fine viene solo dai servizi con corse scritte`() {
        // Corsica Ferries: il calendario arriva a fine anno, ma le corse con orari si fermano prima.
        val noTimes = miniFeed(
            trips = "route_id,service_id,trip_id\nR1,X,T1\nR1,Y,T2",
            stopTimes = """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                T1,08:00:00,08:00:00,A,1
                T1,08:10:00,08:10:00,B,2
            """,
        ).minus("calendar.txt") + ("calendar_dates.txt" to "service_id,date,exception_type\nX,20261006,1\nY,20261009,1")
        val stats = generateTransit(zipOf("notimes.zip", noTimes), tmp.root.resolve("notimes.db"), "mdb-1", monday, 7)

        assertEquals(1, stats.trips)
        assertEquals(LocalDate.parse("2026-10-06"), stats.validUntil)
    }

    @Test
    fun `un servizio cui le eccezioni tolgono ogni giorno non da' corse ne' data di fine`() {
        val removed = miniFeed(
            trips = "route_id,service_id,trip_id\nR1,FERIALE,T1",
            stopTimes = """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                T1,08:00:00,08:00:00,A,1
                T1,08:10:00,08:10:00,B,2
            """,
        ) + ("calendar_dates.txt" to "service_id,date,exception_type\nFERIALE,20261005,2\nFERIALE,20261006,2\nFERIALE,20261007,2\nFERIALE,20261008,2\nFERIALE,20261009,2")
        val stats = generateTransit(zipOf("removed.zip", removed), tmp.root.resolve("removed.db"), "mdb-1", monday, 5)

        assertEquals(0, stats.trips)
        assertNull(stats.validUntil)
    }

    @Test
    fun `fermate non valide si saltano, stazioni e fermate si tengono, il nome unicode si conserva`() {
        val stops = """
            stop_id,stop_name,stop_lat,stop_lon,location_type
            A,Škoda 東京 😀,56.0000,24.0000,
            B,Stazione,56.0100,24.0100,1
            C,Entrata,56.0200,24.0200,2
            D,Latitudine rotta,abc,24.0300,0
            E,Longitudine vuota,56.0400,,0
            F,Nodo generico,56.0500,24.0500,3
            G,Senza posizione,0.000000,0.000000,0
        """
        val db = tmp.root.resolve("stops.db")
        val feedWithStops = miniFeed(
            trips = "route_id,service_id,trip_id\nR1,FERIALE,T1",
            stopTimes = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,08:00:00,08:00:00,A,1\nT1,08:10:00,08:10:00,B,2",
            stops = stops,
        )
        val stats = generateTransit(zipOf("stops.zip", feedWithStops), db, "mdb-1", monday, 7)

        assertEquals(2, stats.stops)
        assertEquals(listOf(listOf<Any?>("Škoda 東京 😀"), listOf("Stazione")), queryDb(db, "SELECT name FROM stop ORDER BY id"))
        assertArrayEquals(doubleArrayOf(24.0, 56.0, 24.01, 56.01), stats.bbox, 1e-9)
    }

    @Test
    fun `le coordinate si salvano in milionesimi di grado interi`() {
        val db = tmp.root.resolve("e6.db")
        generateTransit(zipOf("e6.zip", feed), db, "mdb-1", monday, 7)

        assertEquals(listOf(listOf<Any?>(56950000, 24130000)), queryDb(db, "SELECT latE6, lonE6 FROM stop WHERE name = 'Piazza'"))
    }

    @Test
    fun `orari - arrivo al posto della partenza vuota, orari illeggibili e fermate sconosciute si saltano`() {
        val times = miniFeed(
            trips = "route_id,service_id,trip_id\nR1,FERIALE,T1",
            stopTimes = """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type
                T1,,08:00:00,A,1,0
                T1,08:10:00,,B,2,0
                T1,08:20:00,08:20:00,SCONOSCIUTA,3,0
                T1,xx,xx,A,4,0
                T1,09:00:00,09:00:00,C,5,0
            """,
        )
        val db = tmp.root.resolve("times.db")
        val stats = generateTransit(zipOf("times.zip", times), db, "mdb-1", monday, 7)

        assertEquals(2, stats.stopTimes)
        assertEquals(
            listOf(listOf<Any?>("Alfa", 0), listOf("Beta", 10)),
            queryDb(db, "SELECT s.name, ps.offset FROM pattern_stop ps JOIN stop s ON s.id = ps.stop ORDER BY ps.offset"),
        )
        assertEquals(listOf(listOf<Any?>(480)), queryDb(db, "SELECT start FROM trip"))
    }

    @Test
    fun `corse con la stessa sequenza di fermate e intervalli condividono il pattern`() {
        val shared = miniFeed(
            trips = "route_id,service_id,trip_id\nR1,FERIALE,T1\nR1,FERIALE,T2\nR1,FERIALE,T3",
            stopTimes = """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                T1,08:00:00,08:00:00,A,1
                T1,08:10:00,08:10:00,B,2
                T1,08:20:00,08:20:00,C,3
                T2,09:00:00,09:00:00,A,1
                T2,09:10:00,09:10:00,B,2
                T2,09:25:00,09:25:00,C,3
                T3,10:00:00,10:00:00,A,1
                T3,10:15:00,10:15:00,B,2
                T3,10:20:00,10:20:00,C,3
            """,
        )
        val db = tmp.root.resolve("patterns.db")
        val stats = generateTransit(zipOf("patterns.zip", shared), db, "mdb-1", monday, 7)

        assertEquals(3, stats.trips)
        assertEquals(6, stats.stopTimes)
        assertEquals(listOf(listOf<Any?>(2, 3)), queryDb(db, "SELECT COUNT(DISTINCT pattern), COUNT(*) FROM trip"))
        assertEquals(listOf(listOf<Any?>(4)), queryDb(db, "SELECT COUNT(*) FROM pattern_stop"))
    }

    @Test
    fun `la stessa fermata allo stesso minuto nella stessa corsa conta una volta`() {
        val repeated = miniFeed(
            trips = "route_id,service_id,trip_id\nR1,FERIALE,T1",
            stopTimes = """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                T1,08:00:00,08:00:00,A,1
                T1,08:00:00,08:00:00,A,2
                T1,08:10:00,08:10:00,B,3
            """,
        )
        val db = tmp.root.resolve("repeated.db")
        val stats = generateTransit(zipOf("repeated.zip", repeated), db, "mdb-1", monday, 7)

        assertEquals(1, stats.stopTimes)
        assertEquals(listOf(listOf<Any?>(1)), queryDb(db, "SELECT COUNT(*) FROM pattern_stop"))
    }

    @Test
    fun `corse senza partenze utili o con linea sconosciuta non si scrivono`() {
        val trips = miniFeed(
            trips = "route_id,service_id,trip_id\nR1,FERIALE,T1\nRX,FERIALE,T2\nR1,FERIALE,T3",
            stopTimes = """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                T1,08:00:00,08:00:00,A,1
                T2,08:00:00,08:00:00,A,1
                T2,08:10:00,08:10:00,B,2
                T3,09:00:00,09:00:00,A,1
                T3,09:10:00,09:10:00,B,2
            """,
        )
        val db = tmp.root.resolve("trips.db")
        val stats = generateTransit(zipOf("trips.zip", trips), db, "mdb-1", monday, 7)

        assertEquals(1, stats.trips)
        assertEquals(listOf(listOf<Any?>(1)), queryDb(db, "SELECT COUNT(*) FROM trip"))
    }

    @Test
    fun `linee - tipo mancante o non numerico e' bus, i campi vuoti sono NULL`() {
        val routes = """
            route_id,route_short_name,route_long_name,route_type,route_color,route_text_color
            R1,,Lunga,abc,,
            R2,2,,1,00FF00,000000
            R3,3,Senza tipo,,,
        """
        val db = tmp.root.resolve("routes.db")
        generateTransit(
            zipOf("routes.zip", miniFeed(trips = "route_id,service_id,trip_id\nR1,FERIALE,T1", stopTimes = "trip_id,departure_time,stop_id,stop_sequence\nT1,08:00:00,A,1", routes = routes)),
            db, "mdb-1", monday, 7,
        )

        assertEquals(
            listOf(
                listOf<Any?>(null, "Lunga", 3, null, null),
                listOf("2", null, 1, "00FF00", "000000"),
                listOf("3", "Senza tipo", 3, null, null),
            ),
            queryDb(db, "SELECT short_name, long_name, type, color, text_color FROM route ORDER BY id"),
        )
    }

    @Test
    fun `meta - formato, finestra, accessibilita' e fuso orario vuoto senza agency_txt, nessun valid_until senza servizio`() {
        val db = tmp.root.resolve("meta.db")
        generateTransit(zipOf("meta.zip", feed.minus("agency.txt")), db, "mdb-9", LocalDate.parse("2030-01-01"), 7)

        val meta = queryDb(db, "SELECT key, value FROM meta").associate { it[0] to it[1] }
        assertEquals(
            mapOf<Any?, Any?>(
                "feed_id" to "mdb-9", "format" to "2", "timezone" to "", "window_start" to "20300101", "window_days" to "7", "wheelchair" to "1",
            ),
            meta,
        )
    }

    @Test
    fun `un transit_db preesistente nello stesso percorso viene sostituito`() {
        val db = tmp.root.resolve("vecchio.db").apply { writeText("non e' un database") }

        generateTransit(zipOf("vecchio.zip", feed), db, "mdb-1", monday, 7)

        assertEquals(listOf(listOf<Any?>(3)), queryDb(db, "SELECT COUNT(*) FROM stop"))
    }

    @Test
    fun `l'agenzia con piu' righe dichiara il fuso della prima`() {
        val twoAgencies = feed + ("agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\na,Uno,https://x.invalid,Europe/Riga\nb,Due,https://y.invalid,Europe/Rome")
        val db = tmp.root.resolve("agenzie.db")
        generateTransit(zipOf("agenzie.zip", twoAgencies), db, "mdb-1", monday, 7)

        assertEquals("Europe/Riga", queryDb(db, "SELECT value FROM meta WHERE key = 'timezone'").single()[0])
    }

    @Test
    fun `una colonna obbligatoria assente fa fallire la conversione`() {
        assertThrows(java.util.NoSuchElementException::class.java) {
            generateTransit(zipOf("senza-viaggi.zip", feed.minus("calendar.txt").minus("calendar_dates.txt") + ("trips.txt" to "route_id,trip_id\nR1,T1")), tmp.root.resolve("x.db"), "mdb-1", monday, 7)
        }
    }

    // --- funzioni di supporto ---

    @Test
    fun `gtfsWheelchair accetta solo 1 e 2, anche con spazi`() {
        assertEquals(1, gtfsWheelchair("1"))
        assertEquals(2, gtfsWheelchair(" 2 "))
        assertNull(gtfsWheelchair("0"))
        assertNull(gtfsWheelchair("3"))
        assertNull(gtfsWheelchair("-1"))
        assertNull(gtfsWheelchair("si"))
        assertNull(gtfsWheelchair(""))
        assertNull(gtfsWheelchair(null))
    }

    @Test
    fun `gtfsMinutes - senza secondi, a mezzanotte, ore oltre 24 e valori non numerici`() {
        assertEquals(510, gtfsMinutes("08:30"))
        assertEquals(0, gtfsMinutes("00:00:00"))
        assertEquals(1440, gtfsMinutes("24:00:00"))
        assertEquals(1620, gtfsMinutes("27:00:00"))
        assertNull(gtfsMinutes("8"))
        assertNull(gtfsMinutes("ab:cd"))
        assertNull(gtfsMinutes("08:xx:00"))
        assertNull(gtfsMinutes("   "))
    }

    @Test
    fun `dayBits - vuoto, un byte pieno, nove giorni e tutti accesi`() {
        assertArrayEquals(ByteArray(0), dayBits(BooleanArray(0)))
        assertArrayEquals(byteArrayOf(0), dayBits(BooleanArray(8)))
        assertArrayEquals(byteArrayOf(-1), dayBits(BooleanArray(8) { true }))
        assertArrayEquals(byteArrayOf(0, 0b1), dayBits(BooleanArray(9).also { it[8] = true }))
        assertArrayEquals(byteArrayOf(-128), dayBits(BooleanArray(8).also { it[7] = true }))
        assertEquals(46, dayBits(BooleanArray(366)).size)
    }

    private fun csvRows(text: String): List<Map<String, String>> {
        val rows = mutableListOf<Map<String, String>>()
        readCsv(BufferedReader(StringReader(text))) { rows += it }
        return rows
    }

    @Test
    fun `CSV - vuoto, solo intestazione e ultima riga senza a capo`() {
        assertEquals(emptyList<Map<String, String>>(), csvRows(""))
        assertEquals(emptyList<Map<String, String>>(), csvRows("a,b\n"))
        assertEquals(emptyList<Map<String, String>>(), csvRows("a,b"))
        assertEquals(listOf(mapOf("a" to "1", "b" to "2")), csvRows("a,b\n1,2"))
        assertEquals(listOf(mapOf("a" to "1", "b" to "")), csvRows("a,b\n1,"))
    }

    @Test
    fun `CSV - righe corte, righe lunghe e spazi attorno ai valori`() {
        assertEquals(listOf(mapOf("a" to "1", "b" to "")), csvRows("a,b\n1\n"))
        assertEquals(listOf(mapOf("a" to "1", "b" to "2")), csvRows("a,b\n1,2,3,4\n"))
        assertEquals(listOf(mapOf("a" to "x", "b" to "y")), csvRows(" a , b \n x , y \n"))
    }

    @Test
    fun `CSV - a capo e virgolette doppie dentro un campo tra virgolette, CRLF e righe vuote`() {
        assertEquals(listOf(mapOf("a" to "riga1\nriga2", "b" to "z")), csvRows("a,b\n\"riga1\nriga2\",z\n"))
        assertEquals(listOf(mapOf("a" to "x\"y")), csvRows("a\n\"x\"\"y\"\n"))
        assertEquals(listOf(mapOf("a" to "1"), mapOf("a" to "2")), csvRows("a\r\n1\r\n\r\n\r\n2\r\n"))
    }

    @Test
    fun `CSV - il BOM si toglie solo all'inizio e il campo tra virgolette non chiuso arriva a fine file`() {
        assertEquals(listOf(mapOf("a" to "1")), csvRows("﻿a\n1\n"))
        assertEquals(listOf(mapOf("a" to "aperto, senza chiusura")), csvRows("a\n\"aperto, senza chiusura"))
    }

    @Test
    fun `CSV - testo unicode e virgole in campi tra virgolette`() {
        assertEquals(listOf(mapOf("nome" to "Škoda, 東京 😀", "n" to "1")), csvRows("nome,n\n\"Škoda, 東京 😀\",1\n"))
    }
}
