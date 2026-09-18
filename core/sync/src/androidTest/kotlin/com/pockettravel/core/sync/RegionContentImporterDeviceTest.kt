package com.pockettravel.core.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.db.RegionDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RegionContentImporterSchemaTest (test JVM) verifica solo che le query SQL combacino con lo
 * schema della pipeline dati via JDBC — non esercita mai android.database.sqlite.SQLiteDatabase
 * ne' Room, entrambi disponibili solo su un device/emulatore reale. Questo test strumentato
 * chiude quel gap usando un content.db reale prodotto da tools/data-pipeline (bundlato come
 * asset androidTest, tabelle guide_sections+poi nello stesso file) e un
 * RegionDatabase Room reale in-memory.
 */
@RunWith(AndroidJUnit4::class)
class RegionContentImporterDeviceTest {

    @Test
    fun importaGuideEPoiReali() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val regionId = "test-region"

        val packageDir = File(context.cacheDir, "region-content-importer-test/$regionId")
        packageDir.deleteRecursively()
        packageDir.mkdirs()
        copyAsset(context, "content.db", File(packageDir, "content.db"))

        val db = Room.inMemoryDatabaseBuilder(context, RegionDatabase::class.java).build()
        try {
            RegionContentImporter(db.guideDao(), db.poiDao(), db.emergencyNumbersDao(), db).import(regionId, packageDir)

            val sections = db.guideDao().sectionsForRegion(regionId)
            assertEquals(2, sections.size)
            assertEquals(setOf(GuideCategory.TRASPORTI, GuideCategory.SICUREZZA), sections.map { it.category }.toSet())

            val pois = db.poiDao().poisForRegion(regionId)
            assertEquals(1, pois.size)
            assertEquals("tourism=viewpoint", pois.single().osmTag)

            assertFalse("content.db va cancellato dopo l'import, non serve piu'", File(packageDir, "content.db").exists())

            copyAsset(context, "content.db", File(packageDir, "content.db"))
            RegionContentImporter(db.guideDao(), db.poiDao(), db.emergencyNumbersDao(), db).import(regionId, packageDir)
            assertEquals(2, db.guideDao().sectionsForRegion(regionId).size)
            assertEquals(1, db.poiDao().poisForRegion(regionId).size)
        } finally {
            db.close()
        }
    }

    private fun copyAsset(context: android.content.Context, assetName: String, target: File) {
        context.assets.open(assetName).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
