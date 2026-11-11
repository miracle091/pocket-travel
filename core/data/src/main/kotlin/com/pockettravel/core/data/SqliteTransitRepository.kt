package com.pockettravel.core.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** [TransitRepository] sulle reti GTFS installate, aperte in sola lettura con SQLite. */
class SqliteTransitRepository @Inject constructor(
    private val regionStorage: RegionStorage,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TransitRepository {
    override suspend fun board(
        regionId: String,
        latitude: Double,
        longitude: Double,
        stationModes: Set<TransitMode>,
        now: Instant,
        realtime: Map<String, RealtimeFeed>,
    ): TransitBoard =
        withContext(ioDispatcher) {
            combineBoards(
                readFeeds(regionId) { query, info, id ->
                    readFeedBoard(query, info, latitude, longitude, now, stationModes, realtime[id])
                },
            )
        }

    override suspend fun nearbyStops(
        regionId: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        now: Instant,
        realtime: Map<String, RealtimeFeed>,
    ): List<NearbyStop> =
        withContext(ioDispatcher) {
            mergeNearbyStops(
                readFeeds(regionId) { query, info, id ->
                    readFeedNearbyStops(query, info, latitude, longitude, radiusMeters, now, realtime[id])
                },
            )
        }

    override suspend fun tripStops(
        regionId: String,
        ref: TripRef,
        now: Instant,
        realtime: Map<String, RealtimeFeed>,
    ): TripView? =
        withContext(ioDispatcher) {
            readFeeds(regionId, onlyNetwork = ref.network) { query, _, id -> readTripView(query, ref, now, realtime[id]) }.firstOrNull()
        }

    // Apre ogni rete installata della regione (o la sola [onlyNetwork]); una illeggibile o senza esito (null) si salta.
    private fun <T> readFeeds(regionId: String, onlyNetwork: String? = null, read: (TransitQuery, TransitFeedInfo?, String) -> T?): List<T> {
        val dir = File(regionStorage.directoryFor(regionId), RegionStorage.TRANSIT_DIR)
        val infos = runCatching { TransitFeedInfo.decode(File(dir, RegionStorage.TRANSIT_FEEDS_FILE).readText()) }.getOrDefault(emptyList())
        return dir.listFiles { file -> file.isFile && file.extension == "db" && (onlyNetwork == null || file.nameWithoutExtension == onlyNetwork) }.orEmpty().mapNotNull { file ->
            runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    read(SqliteTransitQuery(db), infos.firstOrNull { it.id == file.nameWithoutExtension }, file.nameWithoutExtension)
                }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        }
    }
}

/** L'ultimo giorno di validita' degli orari di una transit.db (`valid_until`), null se il file non si apre o la data manca. */
fun readTransitValidUntil(dbFile: File): LocalDate? =
    runCatching {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM meta WHERE key = 'valid_until'", null).use { cursor ->
                if (cursor.moveToFirst()) LocalDate.parse(cursor.getString(0), DateTimeFormatter.BASIC_ISO_DATE) else null
            }
        }
    }.getOrNull()

private class SqliteTransitQuery(private val db: SQLiteDatabase) : TransitQuery {
    override fun <T> query(sql: String, read: (TransitRow) -> T): List<T> =
        db.rawQuery(sql, null).use { cursor ->
            val row = CursorRow(cursor)
            buildList { while (cursor.moveToNext()) add(read(row)) }
        }
}

private class CursorRow(private val cursor: Cursor) : TransitRow {
    override fun int(column: Int) = cursor.getInt(column)
    override fun string(column: Int): String? = if (cursor.isNull(column)) null else cursor.getString(column)
    override fun bytes(column: Int): ByteArray? = if (cursor.isNull(column)) null else cursor.getBlob(column)
}
