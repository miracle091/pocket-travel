package com.pockettravel.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.db.RegionDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PackageImporterSchemaTest (JVM) verifica solo le query via JDBC. Qui GuidesImporter e
 * PoiImporter girano con android.database.sqlite e Room reali, su guides.db e poi.db prodotti da
 * tools/data-pipeline (asset androidTest: guide di San Marino e Lettonia, POI di San Marino, content.db
 * v1 di Andorra).
 */
@RunWith(AndroidJUnit4::class)
class PackageImporterDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workDir = File(context.cacheDir, "package-importer-test")
    private lateinit var db: RegionDatabase
    private lateinit var repository: RegionRepository

    @Before
    fun setUp() {
        workDir.deleteRecursively()
        workDir.mkdirs()
        db = Room.inMemoryDatabaseBuilder(context, RegionDatabase::class.java).build()
        val storage = RegionStorage(File(workDir, "regions").apply { mkdirs() }, File(workDir, "staging").apply { mkdirs() })
        repository = RegionRepository(db.regionPackageDao(), db.poiDao(), storage, db)
    }

    @After
    fun tearDown() {
        db.close()
        workDir.deleteRecursively()
    }

    @Test
    fun importaLeGuideDiTutteLeRegioniERegistraLaVersione() = runBlocking {
        val importer = GuidesImporter(db.guideDao(), db.emergencyNumbersDao(), repository, db)
        assertNull(repository.installedGuidesVersion())

        val file = copyAsset("guides.db")
        importer.import(file, "2026.09.23")

        assertTrue(db.guideDao().sectionsForRegion("san-marino").isNotEmpty())
        assertTrue(db.guideDao().sectionsForRegion("lettonia").isNotEmpty())
        assertEquals("113", db.emergencyNumbersDao().forRegion("san-marino")?.police)
        assertEquals("2026.09.23", repository.installedGuidesVersion())
        assertFalse("guides.db va cancellato dopo l'import", file.exists())

        // Un secondo import sostituisce, non accoda.
        val sanMarinoSections = db.guideDao().sectionsForRegion("san-marino").size
        importer.import(copyAsset("guides.db"), "2026.09.30")
        assertEquals(sanMarinoSections, db.guideDao().sectionsForRegion("san-marino").size)
        assertEquals("2026.09.30", repository.installedGuidesVersion())
    }

    @Test
    fun importaIPoiDiUnaRegioneSostituendoQuelliPrecedenti() = runBlocking {
        val importer = PoiImporter(db.poiDao(), db)

        val file = copyAsset("poi.db")
        importer.import("san-marino", file)

        val pois = db.poiDao().poisForRegion("san-marino")
        assertEquals(708, pois.size)
        assertFalse("poi.db va cancellato dopo l'import", file.exists())

        importer.import("san-marino", copyAsset("poi.db"))
        assertEquals(708, db.poiDao().poisForRegion("san-marino").size)
    }

    @Test
    fun importaIlContentDbV1SenzaColonnaPhoneComePacchettoPoi() = runBlocking {
        // content.db pubblicato di Andorra (2026.09.18, prima della colonna phone): le regioni non
        // ancora rigenerate lo usano come pacchetto POI dopo la conversione del manifest v1.
        PoiImporter(db.poiDao(), db).import("andorra", copyAsset("content-v1.db"))

        val pois = db.poiDao().poisForRegion("andorra")
        assertEquals(2681, pois.size)
        assertTrue(pois.all { it.phone == null })
    }

    private fun copyAsset(name: String): File {
        val target = File(workDir, name)
        context.assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }
}
