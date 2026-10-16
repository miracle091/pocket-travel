package com.pockettravel.core.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
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
    ): TransitBoard =
        withContext(ioDispatcher) {
            val dir = File(regionStorage.directoryFor(regionId), RegionStorage.TRANSIT_DIR)
            val infos = runCatching { TransitFeedInfo.decode(File(dir, RegionStorage.TRANSIT_FEEDS_FILE).readText()) }.getOrDefault(emptyList())
            val boards = dir.listFiles { file -> file.isFile && file.extension == "db" }.orEmpty().mapNotNull { file ->
                runCatching {
                    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        readFeedBoard(SqliteTransitQuery(db), infos.firstOrNull { it.id == file.nameWithoutExtension }, latitude, longitude, now, stationModes)
                    }
                }.getOrNull()
            }
            combineBoards(boards)
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
