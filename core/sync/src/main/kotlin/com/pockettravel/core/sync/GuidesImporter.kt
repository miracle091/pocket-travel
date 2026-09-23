package com.pockettravel.core.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.EmergencyNumbersEntity
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import com.pockettravel.core.data.db.RegionDatabase
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Importa guides.db (guide Wikivoyage e numeri di emergenza di tutte le regioni, generato da
 * tools/data-pipeline) in region.db: sostituisce tutte le righe di guide_sections ed
 * emergency_numbers e registra la versione installata, in una sola transazione. guides.db viene
 * cancellato dopo l'import: il contenuto e' gia' in region.db.
 */
class GuidesImporter @Inject constructor(
    private val guideDao: GuideDao,
    private val emergencyNumbersDao: EmergencyNumbersDao,
    private val regionRepository: RegionRepository,
    private val database: RegionDatabase,
) {
    suspend fun import(guidesDbFile: File, version: String) = withContext(Dispatchers.IO) {
        SQLiteDatabase.openDatabase(guidesDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val sections = readGuideSections(db)
            val emergencyNumbers = readEmergencyNumbers(db)
            database.withTransaction {
                guideDao.deleteAll()
                emergencyNumbersDao.deleteAll()
                guideDao.insertAll(sections)
                emergencyNumbersDao.insertAll(emergencyNumbers)
                regionRepository.markGuidesInstalled(version)
            }
        }
        guidesDbFile.delete()
    }

    private fun readGuideSections(db: SQLiteDatabase): List<GuideSectionEntity> {
        val sections = mutableListOf<GuideSectionEntity>()
        db.rawQuery(GUIDE_SECTIONS_QUERY, null).use { cursor ->
            while (cursor.moveToNext()) {
                sections += GuideSectionEntity(
                    regionId = cursor.getString(0),
                    category = GuideCategory.valueOf(cursor.getString(1)),
                    title = cursor.getString(2),
                    body = cursor.getString(3),
                    sourceUrl = cursor.getString(4),
                )
            }
        }
        return sections
    }

    private fun readEmergencyNumbers(db: SQLiteDatabase): List<EmergencyNumbersEntity> {
        val numbers = mutableListOf<EmergencyNumbersEntity>()
        db.rawQuery(EMERGENCY_NUMBERS_QUERY, null).use { cursor ->
            while (cursor.moveToNext()) {
                numbers += EmergencyNumbersEntity(
                    regionId = cursor.getString(0),
                    general = if (cursor.isNull(1)) null else cursor.getString(1),
                    police = cursor.getString(2),
                    ambulance = cursor.getString(3),
                    fire = cursor.getString(4),
                )
            }
        }
        return numbers
    }

    companion object {
        // Costanti (invece che inline) cosi' un test JVM puro puo' eseguirle via JDBC contro un
        // file prodotto dalla pipeline dati, senza android.database.sqlite.
        internal const val GUIDE_SECTIONS_QUERY = "SELECT regionId, category, title, body, sourceUrl FROM guide_sections"
        internal const val EMERGENCY_NUMBERS_QUERY = "SELECT regionId, general, police, ambulance, fire FROM emergency_numbers"
    }
}
