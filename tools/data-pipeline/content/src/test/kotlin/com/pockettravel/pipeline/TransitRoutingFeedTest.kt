package com.pockettravel.pipeline

import com.pockettravel.core.transitrouting.DbStop
import com.pockettravel.core.transitrouting.DbTrip
import com.pockettravel.core.transitrouting.Place
import com.pockettravel.core.transitrouting.Raptor
import com.pockettravel.core.transitrouting.RideLeg
import com.pockettravel.core.transitrouting.dayNetwork
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** RAPTOR su un transit.db scritto da generateTransit: due linee con un cambio a piedi tra due fermate vicine. */
class TransitRoutingFeedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val feed = miniFeed(
        stops = """
            stop_id,stop_name,stop_lat,stop_lon
            A,Alfa,56.0000,24.0000
            B,Beta,56.0100,24.0000
            B2,Beta est,56.0101,24.0010
            C,Gamma,56.0200,24.0000
        """,
        routes = "route_id,route_type\nR1,3\nR2,0",
        trips = "route_id,service_id,trip_id\nR1,FERIALE,T1\nR2,FERIALE,T2\nR2,FERIALE,T3",
        stopTimes = """
            trip_id,arrival_time,departure_time,stop_id,stop_sequence
            T1,08:00:00,08:00:00,A,1
            T1,08:10:00,08:10:00,B,2
            T2,08:12:00,08:12:00,B2,1
            T2,08:22:00,08:22:00,C,2
            T3,08:20:00,08:20:00,B2,1
            T3,08:30:00,08:30:00,C,2
        """,
    )

    @Test
    fun `da Alfa a Gamma con il cambio a piedi a Beta`() {
        val db = tmp.root.resolve("transit.db")
        generateTransit(zip(feed), db, "mdb-1", monday, 7)
        val tuesday = monday.plusDays(1)

        val day = readDay(db, tuesday, fromMinute = 7 * 60, toMinute = 11 * 60)
        val itinerary = Raptor(day.network).plan(Place(55.9996, 24.0), Place(56.0204, 24.0), departMinute = 7 * 60 + 55).single()

        // Beta est e' a circa 63 m da Beta: 2 minuti a piedi e uno di cambio, arriva alle 8.13 e perde le 8.12.
        val rides = itinerary.legs.filterIsInstance<RideLeg>()
        assertEquals(listOf(480 to 490, 500 to 510), rides.map { it.departMinute to it.arriveMinute })
        assertEquals(1, itinerary.transfers)
    }

    // La lettura che fara' l'app da transit.db, qui con JDBC: fermate, corse con i giorni del servizio, blob di trip_stop.
    private fun readDay(db: File, date: LocalDate, fromMinute: Int, toMinute: Int) =
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
            fun <T> query(sql: String, row: (java.sql.ResultSet) -> T): List<T> = conn.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(row(rs)) } }
            }
            val meta = query("SELECT key, value FROM meta") { it.getString(1) to it.getString(2) }.toMap()
            val windowStart = LocalDate.parse(meta.getValue("window_start"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            val stops = query("SELECT id, latE6, lonE6, parent FROM stop ORDER BY id") {
                DbStop(it.getInt(1), it.getInt(2), it.getInt(3), it.getObject(4)?.let { _ -> it.getInt(4) })
            }
            val trips = query("SELECT t.id, t.route, t.start, s.days, t.full_pattern FROM trip t JOIN service s ON s.id = t.service") {
                DbTrip(it.getInt(1), it.getInt(2), it.getInt(3), it.getBytes(4), it.getInt(5))
            }
            val tripStops = query("SELECT pattern, stops FROM trip_stop") { it.getInt(1) to it.getBytes(2) }.toMap()
            dayNetwork(stops, tripStops, trips, ChronoUnit.DAYS.between(windowStart, date).toInt(), fromMinute, toMinute, meta["window_days"]?.toInt())
        }

    private fun zip(files: Map<String, String>): File = tmp.newFile("feed.zip").also { zip ->
        ZipOutputStream(zip.outputStream()).use { out ->
            files.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.trimIndent().toByteArray())
                out.closeEntry()
            }
        }
    }
}
