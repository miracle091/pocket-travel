package com.pockettravel.core.data

import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import com.pockettravel.core.data.db.InstalledGuidesEntity
import com.pockettravel.core.data.db.InstalledRegionEntity
import com.pockettravel.core.data.db.PassportDao
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.db.RegionPackageDao
import androidx.room.InvalidationTracker
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    private val guides = MutableStateFlow<InstalledGuidesEntity?>(null)
    override suspend fun upsertGuides(guides: InstalledGuidesEntity) { this.guides.value = guides }
    override suspend fun guidesVersion(): String? = guides.value?.version
    override fun observeGuides(): Flow<InstalledGuidesEntity?> = guides
}

private class NoOpGuideDao : GuideDao {
    override suspend fun insertAll(sections: List<GuideSectionEntity>) = Unit
    override suspend fun sectionsForRegion(regionId: String): List<GuideSectionEntity> = emptyList()
    override suspend fun search(query: String): List<GuideSectionEntity> = emptyList()
    override suspend fun searchInRegion(regionId: String, query: String): List<GuideSectionEntity> = emptyList()
    override suspend fun deleteAll() = Unit
}

private class NoOpPoiDao : PoiDao {
    override suspend fun insertAll(pois: List<PoiEntity>) = Unit
    override suspend fun poisForRegion(regionId: String): List<PoiEntity> = emptyList()
    override suspend fun deleteForRegion(regionId: String) = Unit
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

    // Mai chiamati nei test: qui RegionDatabase non e' mai inizializzata da Room, serve solo
    // come valore-tipo per il costruttore di RegionRepository.
    override fun createInvalidationTracker(): InvalidationTracker = throw UnsupportedOperationException()
    override fun clearAllTables(): Unit = throw UnsupportedOperationException()
}

class RegionRepositoryTest {

    private fun newRepository(): Pair<RegionRepository, RegionPackageDao> {
        val regionPackageDao = FakeRegionPackageDao()
        val guideDao = NoOpGuideDao()
        val poiDao = NoOpPoiDao()
        val root = createTempDirectory("pocket-travel-region-repo-test").toFile()
        val regionStorage = RegionStorage(
            regionsDir = File(root, "regions").apply { mkdirs() },
            stagingDir = File(root, "staging").apply { mkdirs() },
        )
        val repository = RegionRepository(
            regionPackageDao = regionPackageDao,
            poiDao = poiDao,
            regionStorage = regionStorage,
            database = UnusedRegionDatabase(guideDao, poiDao, regionPackageDao),
        )
        return repository to regionPackageDao
    }

    @Test
    fun `markPackagesInstalled rende la regione visibile con le versioni di ogni pacchetto`() = runBlocking {
        val (repository, _) = newRepository()

        repository.markPackagesInstalled(
            "italia", "Italia",
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
        repository.markPackagesInstalled("italia", "Italia", mapOf(PackageKind.POI to "1"), poiSizeBytes = 10)

        assertNull(repository.installed("mai-installata"))
        assertNull(repository.installed("italia")!!.mapVersion)
        assertEquals("1", repository.installed("italia")!!.poiVersion)
    }

    @Test
    fun `markPackagesInstalled aggiorna un pacchetto e conserva gli altri`() = runBlocking {
        val (repository, _) = newRepository()
        repository.markPackagesInstalled("italia", "Italia", mapOf(PackageKind.MAP to "1", PackageKind.POI to "1"), poiSizeBytes = 500)

        repository.markPackagesInstalled("italia", "Italia", mapOf(PackageKind.MAP to "2"))

        val installed = repository.installed("italia")!!
        assertEquals("2", installed.mapVersion)
        assertEquals("1", installed.poiVersion)
        assertEquals(500L, installed.poiSizeBytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `markPackagesInstalled richiede la dimensione dei POI`() = runBlocking {
        val (repository, _) = newRepository()
        repository.markPackagesInstalled("italia", "Italia", mapOf(PackageKind.POI to "1"))
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
}
