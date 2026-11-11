package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Scrive in [zip] i file di un feed GTFS di prova (testo con rientro tolto). */
internal fun writeZip(zip: File, files: Map<String, String>) {
    ZipOutputStream(zip.outputStream()).use { out ->
        files.forEach { (name, text) ->
            out.putNextEntry(ZipEntry(name))
            out.write(text.trimIndent().toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
    }
}

// Lunedi' 2026-10-05: finestra di 7 giorni fino a domenica 11.
internal val monday = LocalDate.parse("2026-10-05")

internal val feed = mapOf(
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

internal fun queryDb(db: File, sql: String): List<List<Any?>> =
    DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
        conn.createStatement().use { s ->
            s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) }) } }
        }
    }

/** Le righe (pattern, seq, stop, offset, flags) di trip_stop, lette con un decodificatore scritto per il test dal formato documentato. */
internal fun decodedTripStops(db: File): List<List<Any?>> = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
    conn.createStatement().use { s ->
        s.executeQuery("SELECT pattern, stops FROM trip_stop ORDER BY pattern").use { rs ->
            buildList {
                while (rs.next()) {
                    val pattern = rs.getInt(1)
                    val blob = rs.getBytes(2)
                    var pos = 0
                    fun varint(): Int {
                        var result = 0
                        var shift = 0
                        while (true) {
                            val b = blob[pos++].toInt() and 0xFF
                            result = result or ((b and 0x7F) shl shift)
                            if (b < 0x80) return result
                            shift += 7
                        }
                    }
                    fun unzigzag(n: Int) = (n ushr 1) xor -(n and 1)
                    var seq = 0
                    var stop = 0
                    var offset = 0
                    while (pos < blob.size) {
                        seq += varint(); stop += unzigzag(varint()); offset += unzigzag(varint())
                        add(listOf<Any?>(pattern, seq, stop, offset, blob[pos++].toInt()))
                    }
                }
            }
        }
    }
}

internal const val FEED_STOPS = """
    stop_id,stop_name,stop_lat,stop_lon
    A,Alfa,56.0000,24.0000
    B,Beta,56.0100,24.0100
    C,Gamma,56.0200,24.0200
"""

internal const val FEED_CALENDAR = """
    service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
    FERIALE,1,1,1,1,1,0,0,20260101,20261231
"""

internal fun miniFeed(
    trips: String,
    stopTimes: String,
    stops: String = FEED_STOPS,
    routes: String = "route_id,route_type\nR1,3",
    calendar: String = FEED_CALENDAR,
) = mapOf(
    "stops.txt" to stops,
    "routes.txt" to routes,
    "trips.txt" to trips,
    "stop_times.txt" to stopTimes,
    "calendar.txt" to calendar,
)
