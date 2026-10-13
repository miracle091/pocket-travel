package com.pockettravel.core.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
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
