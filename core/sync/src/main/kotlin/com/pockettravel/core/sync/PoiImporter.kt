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

    private fun readPois(regionId: String, db: SQLiteDatabase): List<PoiEntity> {
        val pois = mutableListOf<PoiEntity>()
        db.rawQuery(POI_QUERY, null).use { cursor ->
            while (cursor.moveToNext()) {
                pois += PoiEntity(
                    regionId = regionId,
                    name = cursor.getString(0),
                    category = cursor.getString(1),
                    lat = cursor.getDouble(2),
                    lon = cursor.getDouble(3),
                    osmTag = cursor.getString(4),
                    phone = if (cursor.isNull(5)) null else cursor.getString(5),
                )
            }
        }
        return pois
    }

    companion object {
        // Vedi GuidesImporter: eseguita via JDBC da un test JVM contro lo schema della pipeline.
        internal const val POI_QUERY = "SELECT name, category, lat, lon, osmTag, phone FROM poi"
    }
}
