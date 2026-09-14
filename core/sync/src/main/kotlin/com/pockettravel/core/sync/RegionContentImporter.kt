package com.pockettravel.core.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.RegionDatabase
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Importa il content.db scaricato (file SQLite semplice generato dalla pipeline dati in
 * tools/data-pipeline, non una replica dello schema Room — tabelle "guide_sections" e "poi"
 * insieme nello stesso file, accorpate da due file separati guide.db/poi.db per evitare di
 * scaricare/verificare/aprire due file quando finiscono comunque nello stesso posto)
 * dentro region.db, l'unico database che l'app interroga via GuideDao/PoiDao.
 * Le colonne corrispondono 1:1 a GuideSectionEntity/PoiEntity meno l'id, autogenerato qui
 * all'insert. content.db viene cancellato dopo un import riuscito: il contenuto e' gia' in
 * region.db, tenerlo occuperebbe spazio su disco raddoppiato per ogni regione installata senza
 * alcun beneficio.
 */
class RegionContentImporter @Inject constructor(
    private val guideDao: GuideDao,
    private val poiDao: PoiDao,
    private val database: RegionDatabase,
) {
    suspend fun import(regionId: String, packageDir: File) = withContext(Dispatchers.IO) {
        val contentDbFile = File(packageDir, "content.db")
        SQLiteDatabase.openDatabase(contentDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val sections = readGuideSections(regionId, db)
            val pois = readPois(regionId, db)
            database.withTransaction {
                guideDao.deleteForRegion(regionId)
                poiDao.deleteForRegion(regionId)
                guideDao.insertAll(sections)
                poiDao.insertAll(pois)
            }
        }
        contentDbFile.delete()
    }

    private fun readGuideSections(regionId: String, db: SQLiteDatabase): List<GuideSectionEntity> {
        val sections = mutableListOf<GuideSectionEntity>()
        db.rawQuery(GUIDE_SECTIONS_QUERY, null).use { cursor ->
            while (cursor.moveToNext()) {
                sections += GuideSectionEntity(
                    regionId = regionId,
                    category = GuideCategory.valueOf(cursor.getString(0)),
                    title = cursor.getString(1),
                    body = cursor.getString(2),
                    sourceUrl = cursor.getString(3),
                )
            }
        }
        return sections
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
                )
            }
        }
        return pois
    }

    companion object {
        // Estratte come costanti (invece che inline) cosi' un test JVM puro puo' eseguirle
        // via JDBC contro un file prodotto dalla pipeline dati, senza dipendere da
        // android.database.sqlite (non disponibile fuori da un device/emulatore reale).
        internal const val GUIDE_SECTIONS_QUERY = "SELECT category, title, body, sourceUrl FROM guide_sections"
        internal const val POI_QUERY = "SELECT name, category, lat, lon, osmTag FROM poi"
    }
}
