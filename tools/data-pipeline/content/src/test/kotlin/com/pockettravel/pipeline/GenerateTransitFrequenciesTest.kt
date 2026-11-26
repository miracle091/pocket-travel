package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GenerateTransitFrequenciesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `corse di frequencies_txt ripetute ogni headway_secs, la corsa modello non si scrive due volte`() {
        // Metro de Sevilla: ogni corsa e' un modello con frequenze; T2 senza frequenze resta una corsa sola.
        val feed = miniFeed(
            trips = "route_id,service_id,trip_id\nR1,FERIALE,T1\nR1,FERIALE,T2",
            stopTimes = """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                T1,06:00:00,06:00:00,A,1
                T1,06:10:00,06:10:00,B,2
                T2,09:00:00,09:00:00,A,1
                T2,09:10:00,09:10:00,B,2
            """,
        ) + ("frequencies.txt" to FREQUENCIES)
        val zip = tmp.newFile("freq.zip").also { writeZip(it, feed) }
        val db = tmp.root.resolve("freq.db")
        val stats = generateTransit(zip, db, "mdb-1", monday, 7, gtfsIds = true)

        // 06:30 e 06:43 (06:56 oltre la fine), 07:00 e 07:10 (07:20 e' la fine), la riga con la fine prima dell'inizio
        // si salta; T2 alle 09:00. Il modello alle 06:00 non e' una partenza.
        assertEquals(5, stats.trips)
        assertEquals(
            listOf(390, 403, 420, 430, 540),
            queryDb(db, "SELECT t.start + ps.offset FROM trip t JOIN pattern_stop ps ON ps.pattern = t.pattern ORDER BY 1").map { it[0] },
        )
        assertEquals(
            listOf("T1", "T1", "T1", "T1", "T2"),
            queryDb(db, "SELECT g.trip_id FROM trip t JOIN gtfs_trip g ON g.id = t.id ORDER BY t.start").map { it[0] },
        )
    }
}

private const val FREQUENCIES = """
    trip_id,start_time,end_time,headway_secs,exact_times
    T1,6:30:00,6:49:59,780,
    T1,07:00:00,07:20:00,600,1
    T1,08:00:00,07:00:00,600,1
"""
