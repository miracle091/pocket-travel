package com.pockettravel.core.data

import androidx.room.InvalidationTracker
import com.pockettravel.core.data.db.CategoryTag
import com.pockettravel.core.data.db.CityDao
import com.pockettravel.core.data.db.CitySectionEntity
import com.pockettravel.core.data.db.CitySectionMatch
import com.pockettravel.core.data.db.DiplomaticMissionDao
import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import com.pockettravel.core.data.db.GuideSectionMatch
import com.pockettravel.core.data.db.InstalledGuidesEntity
import com.pockettravel.core.data.db.InstalledRegionEntity
import com.pockettravel.core.data.db.NoteDao
import com.pockettravel.core.data.db.PassportDao
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.db.RegionPackageDao
import com.pockettravel.core.data.db.TransportCount
import com.pockettravel.core.data.db.VaccinationDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Le tre Dao sono interfacce Room senza logica propria: fake in-memory bastano per testare
 * RegionRepository senza un vero database. `remove()`/`inInstallTransaction()` usano
 * `RoomDatabase.withTransaction`, che richiede un'istanza Room reale (getOpenHelper() inizializzato) —
 * non coperti qui per lo stesso motivo per cui questo progetto non usa Robolectric altrove:
 * quel percorso resta verificato solo dai test strumentati esistenti. */
private class FakeRegionPackageDao : RegionPackageDao {
    private val entities = linkedMapOf<String, InstalledRegionEntity>()
    private val flow = MutableStateFlow<List<InstalledRegionEntity>>(emptyList())

    override suspend fun upsert(region: InstalledRegionEntity) {
        entities[region.regionId] = region
        flow.value = entities.values.sortedBy { it.displayName }
    }

    override fun observeAll(): Flow<List<InstalledRegionEntity>> = flow

    override suspend fun findById(regionId: String): InstalledRegionEntity? = entities[regionId]

    override suspend fun deleteById(regionId: String) {
        entities.remove(regionId)
        flow.value = entities.values.sortedBy { it.displayName }
    }

    override suspend fun fillCountryCode(regionId: String, countryCode: String) {
        val current = entities[regionId] ?: return
        if (current.countryCode == null) upsert(current.copy(countryCode = countryCode))
    }

    private val guides = MutableStateFlow<InstalledGuidesEntity?>(null)
    override suspend fun upsertGuides(guides: InstalledGuidesEntity) { this.guides.value = guides }
    override suspend fun guidesVersion(): String? = guides.value?.version
    override fun observeGuides(): Flow<InstalledGuidesEntity?> = guides
}

private class NoOpGuideDao : GuideDao {
    override suspend fun insertAll(sections: List<GuideSectionEntity>) = Unit
    override suspend fun sectionsForRegion(regionId: String): List<GuideSectionEntity> = emptyList()
    override suspend fun searchInRegionRanked(regionId: String, query: String, candidateLimit: Int): List<GuideSectionMatch> = emptyList()
    override suspend fun deleteAll() = Unit
    override suspend fun optimizeFts() = Unit
}

private class NoOpPoiDao : PoiDao {
    override suspend fun insertAll(pois: List<PoiEntity>) = Unit
    override suspend fun poisForRegion(regionId: String): List<PoiEntity> = emptyList()
    override suspend fun poisInBounds(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, excluded: List<String>, accessibility: Int, limit: Int): List<PoiEntity> = emptyList()
    override suspend fun spreadInBounds(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double, excluded: List<String>, accessibility: Int): List<PoiEntity> = emptyList()
    override suspend fun spreadInWideBounds(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double, excluded: List<String>, accessibility: Int): List<PoiEntity> = emptyList()
    override suspend fun categoryTagsInRegion(regionId: String): List<CategoryTag> = emptyList()
    override suspend fun transportCounts(regionId: String): List<TransportCount> = emptyList()
    override suspend fun embassiesOf(regionId: String, country: String): List<PoiEntity> = emptyList()
    override suspend fun searchByName(regionIds: List<String>, pattern: String, limit: Int): List<PoiEntity> = emptyList()
    override suspend fun nearest(
        regionIds: List<String>, lat: Double, lon: Double, lonScale: Double,
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int,
    ): List<PoiEntity> = emptyList()
    override suspend fun deleteForRegion(regionId: String, extra: Boolean?) = Unit
}

private class NoOpCityDao : CityDao {
    val deletedRegions = mutableListOf<String>()

    override suspend fun insertAll(sections: List<CitySectionEntity>) = Unit
    override fun citiesForRegion(regionId: String) = throw UnsupportedOperationException()
    override fun mainCitiesForRegion(regionId: String, limit: Int) = throw UnsupportedOperationException()
    override suspend fun coordinatesFor(regionId: String, city: String) = null
    override suspend fun sectionsFor(regionId: String, city: String): List<CitySectionEntity> = emptyList()
    override suspend fun searchInRegionRanked(regionId: String, query: String, city: String?, candidateLimit: Int): List<CitySectionMatch> = emptyList()
    override suspend fun deleteForRegion(regionId: String) { deletedRegions += regionId }
    override suspend fun optimizeFts() = Unit
}

/** Mai usata per una transazione reale in questi test (vedi nota sopra) — serve solo a
 * soddisfare il tipo del costruttore di RegionRepository. */
private class UnusedRegionDatabase(
    private val guide: GuideDao,
    private val poi: PoiDao,
    private val regionPackage: RegionPackageDao,
) : RegionDatabase() {
    override fun guideDao() = guide
    override fun poiDao() = poi
    override fun regionPackageDao() = regionPackage
    override fun passportDao(): PassportDao = throw UnsupportedOperationException()
    override fun emergencyNumbersDao(): EmergencyNumbersDao = throw UnsupportedOperationException()
    override fun cityDao(): CityDao = throw UnsupportedOperationException()
    override fun noteDao(): NoteDao = throw UnsupportedOperationException()
    override fun diplomaticMissionDao(): DiplomaticMissionDao = throw UnsupportedOperationException()
    override fun vaccinationDao(): VaccinationDao = throw UnsupportedOperationException()

    // Mai chiamati nei test: qui RegionDatabase non e' mai inizializzata da Room, serve solo
    // come valore-tipo per il costruttore di RegionRepository.
    override fun createInvalidationTracker(): InvalidationTracker = throw UnsupportedOperationException()
    override fun clearAllTables(): Unit = throw UnsupportedOperationException()
}

class RegionRepositoryTest {

    // RegionStorage dell'ultimo newRepository(), per i test che scrivono file dei pacchetti.
    private lateinit var regionStorage: RegionStorage

    private fun newRepository(): Pair<RegionRepository, RegionPackageDao> {
        val regionPackageDao = FakeRegionPackageDao()
        val guideDao = NoOpGuideDao()
        val poiDao = NoOpPoiDao()
        val root = createTempDirectory("pocket-travel-region-repo-test").toFile()
        regionStorage = RegionStorage(
            regionsDir = File(root, "regions").apply { mkdirs() },
            stagingDir = File(root, "staging").apply { mkdirs() },
        )
        val repository = RegionRepository(
            regionPackageDao = regionPackageDao,
            poiDao = poiDao,
            regionStorage = regionStorage,
            database = UnusedRegionDatabase(guideDao, poiDao, regionPackageDao),
            cityDao = NoOpCityDao(),
        )
        return repository to regionPackageDao
    }

    @Test
    fun `markPackagesInstalled rende la regione visibile con le versioni di ogni pacchetto`() = runBlocking {
        val (repository, _) = newRepository()

        repository.markPackagesInstalled(
            "italia", "Italia", "it",
            mapOf(PackageKind.MAP to "1", PackageKind.ROUTING to "2", PackageKind.POI to "3"),
            poiSizeBytes = 1000,
        )

        val installed = repository.installed("italia")!!
        assertEquals("1", installed.mapVersion)
        assertEquals("2", installed.routingVersion)
        assertEquals("3", installed.poiVersion)
        assertEquals(1000L, installed.sizeBytes)
        assertEquals(listOf("Italia"), repository.observeInstalled().first().map { it.displayName })
    }

    @Test
    fun `installed e' null per una regione mai installata e un pacchetto assente ha versione null`() = runBlocking {
        val (repository, _) = newRepository()
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.POI to "1"), poiSizeBytes = 10)

        assertNull(repository.installed("mai-installata"))
        assertNull(repository.installed("italia")!!.mapVersion)
        assertEquals("1", repository.installed("italia")!!.poiVersion)
    }

    @Test
    fun `markPackagesInstalled aggiorna un pacchetto e conserva gli altri`() = runBlocking {
        val (repository, _) = newRepository()
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "1", PackageKind.POI to "1"), poiSizeBytes = 500)

        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "2"))

        val installed = repository.installed("italia")!!
        assertEquals("2", installed.mapVersion)
        assertEquals("1", installed.poiVersion)
        assertEquals(500L, installed.poiSizeBytes)
    }

    @Test
    fun `il codice paese resta aggiornando un pacchetto senza codice`() = runBlocking {
        val (repository, _) = newRepository()
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "1", PackageKind.POI to "1"), poiSizeBytes = 500)

        repository.markPackagesInstalled("italia", "Italia", null, mapOf(PackageKind.MAP to "2"))
        assertEquals("it", repository.installed("italia")!!.countryCode)
    }

    @Test
    fun `fillCountryCode riempie solo un codice mancante`() = runBlocking {
        val (repository, _) = newRepository()
        repository.markPackagesInstalled("italia", "Italia", null, mapOf(PackageKind.POI to "1"), poiSizeBytes = 10)
        repository.markPackagesInstalled("francia", "Francia", "fr", mapOf(PackageKind.POI to "1"), poiSizeBytes = 10)

        repository.fillCountryCode("italia", "it")
        repository.fillCountryCode("francia", "xx")

        assertEquals("it", repository.installed("italia")!!.countryCode)
        assertEquals("fr", repository.installed("francia")!!.countryCode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `markPackagesInstalled richiede la dimensione dei POI`() = runBlocking {
        val (repository, _) = newRepository()
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.POI to "1"))
    }

    @Test
    fun `le guide non sono installate finche' markGuidesInstalled non le registra`() = runBlocking {
        val (repository, _) = newRepository()
        assertNull(repository.installedGuidesVersion())

        repository.markGuidesInstalled("2026.09.23", 900_000)

        assertEquals("2026.09.23", repository.installedGuidesVersion())
        assertEquals(InstalledGuides("2026.09.23", 900_000), repository.observeInstalledGuides().first())
    }

    @Test
    fun `availableStorageBytes delega a RegionStorage`() = runBlocking {
        val (repository, _) = newRepository()
        assertEquals(true, repository.availableStorageBytes() >= 0)
    }

    @Test
    fun `previewVersion si aggiorna con markPackagesInstalled e resta se non indicata`() = runBlocking {
        val (repository, _) = newRepository()
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "1"), previewVersion = "2026.09.01")

        assertEquals("2026.09.01", repository.installed("italia")!!.previewVersion)

        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "2"))
        assertEquals("2026.09.01", repository.installed("italia")!!.previewVersion)

        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "3"), previewVersion = "2026.09.08")
        assertEquals("2026.09.08", repository.installed("italia")!!.previewVersion)
    }

    @Test
    fun `i POI extra contano nella dimensione della regione e non toccano quella dei POI base`() = runBlocking {
        val (repository, _) = newRepository()
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.POI to "1"), poiSizeBytes = 500)
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.POI_EXTRA to "1"), poiExtraSizeBytes = 200)

        val installed = repository.installed("italia")!!
        assertEquals("1", installed.poiExtraVersion)
        assertEquals(700L, installed.sizeBytes)
        assertEquals(500L, repository.packageBytes(installed, PackageKind.POI))
        assertEquals(200L, repository.packageBytes(installed, PackageKind.POI_EXTRA))
    }

    @Test
    fun `le guide di citta' contano nella dimensione della regione`() = runBlocking {
        val (repository, _) = newRepository()

        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.CITIES to "1"), citiesSizeBytes = 300)

        val installed = repository.installed("italia")!!
        assertEquals("1", installed.citiesVersion)
        assertEquals(300L, installed.sizeBytes)
        assertEquals(300L, repository.packageBytes(installed, PackageKind.CITIES))
    }

    @Test
    fun `forgetMissingPackages toglie i pacchetti registrati senza file e tiene gli altri`() = runBlocking {
        val (repository, _) = newRepository()
        val regionDir = regionStorage.directoryFor("sint-maarten")
        File(regionDir, RegionStorage.MAP_FILE).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(100))
        // Cartella dei percorsi rimasta vuota, come dopo la cancellazione dei segmenti.
        File(regionDir, RegionStorage.ROUTING_DIR).mkdirs()
        repository.markPackagesInstalled(
            "sint-maarten", "Sint Maarten", "sx",
            mapOf(PackageKind.MAP to "1", PackageKind.ROUTING to "1", PackageKind.POI to "1", PackageKind.TRANSIT to "1"),
            poiSizeBytes = 50,
        )

        val forgotten = repository.forgetMissingPackages("sint-maarten")

        assertEquals(setOf(PackageKind.ROUTING, PackageKind.TRANSIT), forgotten)
        val installed = repository.installed("sint-maarten")!!
        assertEquals("1", installed.mapVersion)
        assertNull(installed.routingVersion)
        assertNull(installed.transitVersion)
        assertEquals("1", installed.poiVersion)
        assertEquals(150L, installed.sizeBytes)
    }

    @Test
    fun `forgetMissingPackages non tocca una regione con tutti i file`() = runBlocking {
        val (repository, _) = newRepository()
        File(regionStorage.directoryFor("italia"), RegionStorage.MAP_FILE).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(10))
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "1", PackageKind.POI to "1"), poiSizeBytes = 5)

        assertEquals(emptySet<PackageKind>(), repository.forgetMissingPackages("italia"))
        assertEquals(emptySet<PackageKind>(), repository.forgetMissingPackages("mai-installata"))
        assertEquals("1", repository.installed("italia")!!.mapVersion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `markPackagesInstalled richiede la dimensione delle citta'`() = runBlocking {
        val (repository, _) = newRepository()
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.CITIES to "1"))
    }
}
