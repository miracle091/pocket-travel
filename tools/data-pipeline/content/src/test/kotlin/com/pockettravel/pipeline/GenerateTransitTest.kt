package com.pockettravel.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
