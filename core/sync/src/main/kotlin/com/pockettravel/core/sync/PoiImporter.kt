package com.pockettravel.core.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.RegionDatabase
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Importa il poi.db (o, con extra, il poi-extra.db) scaricato di una regione (generato da
 * tools/data-pipeline) in region.db, l'unico database che l'app interroga via PoiDao, sostituendo
 * i POI dello stesso pacchetto gia' presenti per la regione. Le colonne corrispondono 1:1 a PoiEntity meno l'id, autogenerato all'insert. poi.db
 * viene cancellato dopo un import riuscito: tenerlo raddoppierebbe lo spazio occupato.
 */
class PoiImporter @Inject constructor(
    private val poiDao: PoiDao,
    private val database: RegionDatabase,
) {
    suspend fun import(regionId: String, poiDbFile: File, extra: Boolean = false) = withContext(Dispatchers.IO) {
        SQLiteDatabase.openDatabase(poiDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val pois = readPois(regionId, db, extra)
            database.withTransaction {
                poiDao.deletePackageForRegion(regionId, extra)
                poiDao.insertAll(pois)
            }
        }
        poiDbFile.delete()
    }

    // Le colonne facoltative dipendono da quando il file e' stato generato: i content.db v1 (vedi
    // MergeManifests.convertV1Region, tools/data-pipeline) pubblicati prima di "phone" non la hanno, i
    // poi.db pubblicati prima di "wheelchair" nemmeno questa. Selezionare una colonna assente farebbe
    // fallire l'intero download.
    private fun readPois(regionId: String, db: SQLiteDatabase, extra: Boolean): List<PoiEntity> {
        val columns = poiColumns(db)
        val pois = mutableListOf<PoiEntity>()
        db.rawQuery(poiQuery(columns), null).use { cursor ->
            val phone = cursor.getColumnIndex("phone")
            val wheelchair = cursor.getColumnIndex("wheelchair")
            while (cursor.moveToNext()) {
                pois += PoiEntity(
                    regionId = regionId,
                    name = cursor.getString(0),
                    category = cursor.getString(1),
                    lat = cursor.getDouble(2),
                    lon = cursor.getDouble(3),
                    osmTag = cursor.getString(4),
                    phone = if (phone >= 0 && !cursor.isNull(phone)) cursor.getString(phone) else null,
                    wheelchair = if (wheelchair >= 0 && !cursor.isNull(wheelchair)) cursor.getString(wheelchair) else null,
                    extra = extra,
                )
            }
        }
        return pois
    }

    private fun poiColumns(db: SQLiteDatabase): Set<String> {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(poi)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        return columns
    }

    companion object {
        // Vedi GuidesImporter: eseguita via JDBC da un test JVM contro lo schema della pipeline.
        internal fun poiQuery(columns: Set<String>): String =
            "SELECT name, category, lat, lon, osmTag" + OPTIONAL_COLUMNS.filter { it in columns }.joinToString("") { ", $it" } + " FROM poi"

        private val OPTIONAL_COLUMNS = listOf("phone", "wheelchair")
    }
}
