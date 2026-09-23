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
 * Importa il poi.db scaricato di una regione (generato da tools/data-pipeline) in region.db,
 * l'unico database che l'app interroga via PoiDao, sostituendo i POI gia' presenti per la
 * regione. Le colonne corrispondono 1:1 a PoiEntity meno l'id, autogenerato all'insert. poi.db
 * viene cancellato dopo un import riuscito: tenerlo raddoppierebbe lo spazio occupato.
 */
class PoiImporter @Inject constructor(
    private val poiDao: PoiDao,
    private val database: RegionDatabase,
) {
    suspend fun import(regionId: String, poiDbFile: File) = withContext(Dispatchers.IO) {
        SQLiteDatabase.openDatabase(poiDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val pois = readPois(regionId, db)
            database.withTransaction {
                poiDao.deleteForRegion(regionId)
                poiDao.insertAll(pois)
            }
        }
        poiDbFile.delete()
    }

    // Le regioni ancora nel formato v1 hanno come pacchetto POI il loro content.db (vedi
    // MergeManifests.convertV1Region, tools/data-pipeline): quelli pubblicati prima della colonna
    // phone non la hanno, e selezionarla farebbe fallire l'intero download.
    private fun readPois(regionId: String, db: SQLiteDatabase): List<PoiEntity> {
        val hasPhone = hasPhoneColumn(db)
        val pois = mutableListOf<PoiEntity>()
        db.rawQuery(if (hasPhone) POI_QUERY else POI_QUERY_LEGACY, null).use { cursor ->
            while (cursor.moveToNext()) {
                pois += PoiEntity(
                    regionId = regionId,
                    name = cursor.getString(0),
                    category = cursor.getString(1),
                    lat = cursor.getDouble(2),
                    lon = cursor.getDouble(3),
                    osmTag = cursor.getString(4),
                    phone = if (hasPhone && !cursor.isNull(5)) cursor.getString(5) else null,
                )
            }
        }
        return pois
    }

    private fun hasPhoneColumn(db: SQLiteDatabase): Boolean {
        db.rawQuery("PRAGMA table_info(poi)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "phone") return true
            }
        }
        return false
    }

    companion object {
        // Vedi GuidesImporter: eseguita via JDBC da un test JVM contro lo schema della pipeline.
        internal const val POI_QUERY = "SELECT name, category, lat, lon, osmTag, phone FROM poi"
        internal const val POI_QUERY_LEGACY = "SELECT name, category, lat, lon, osmTag FROM poi"
    }
}
