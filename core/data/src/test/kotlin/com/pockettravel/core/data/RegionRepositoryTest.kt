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

    private var guides: InstalledGuidesEntity? = null
    override suspend fun upsertGuides(guides: InstalledGuidesEntity) { this.guides = guides }
    override suspend fun guidesVersion(): String? = guides?.version
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
    fun `markInstalled rende la regione visibile a observeInstalled e installedVersion`() = runBlocking {
        val (repository, _) = newRepository()

        repository.markInstalled(RegionPackage("italia", "Italia", mapVersion = "1", routingVersion = "2", poiVersion = "3", sizeBytes = 1000))

        assertEquals("1", repository.installedVersion("italia", PackageKind.MAP))
        assertEquals("2", repository.installedVersion("italia", PackageKind.ROUTING))
        assertEquals("3", repository.installedVersion("italia", PackageKind.POI))
        assertEquals(listOf("Italia"), repository.observeInstalled().first().map { it.displayName })
    }

    @Test
    fun `installedVersion e' null per una regione mai installata o un pacchetto assente`() = runBlocking {
        val (repository, _) = newRepository()
        repository.markInstalled(RegionPackage("italia", "Italia", mapVersion = null, routingVersion = null, poiVersion = "1", sizeBytes = 10))

        assertNull(repository.installedVersion("mai-installata", PackageKind.POI))
        assertNull(repository.installedVersion("italia", PackageKind.MAP))
        assertEquals("1", repository.installedVersion("italia", PackageKind.POI))
    }

    @Test
    fun `markInstalled sovrascrive una versione precedente della stessa regione`() = runBlocking {
        val (repository, _) = newRepository()
        repository.markInstalled(RegionPackage("italia", "Italia", mapVersion = "1", routingVersion = "1", poiVersion = "1", sizeBytes = 1000))

        repository.markInstalled(RegionPackage("italia", "Italia", mapVersion = "1", routingVersion = "1", poiVersion = "2", sizeBytes = 2000))

        assertEquals("2", repository.installedVersion("italia", PackageKind.POI))
        assertEquals("1", repository.installedVersion("italia", PackageKind.MAP))
    }

    @Test
    fun `le guide non sono installate finche' markGuidesInstalled non le registra`() = runBlocking {
        val (repository, _) = newRepository()
        assertNull(repository.installedGuidesVersion())

        repository.markGuidesInstalled("2026.09.23")

        assertEquals("2026.09.23", repository.installedGuidesVersion())
    }

    @Test
    fun `availableStorageBytes delega a RegionStorage`() = runBlocking {
        val (repository, _) = newRepository()
        assertEquals(true, repository.availableStorageBytes() >= 0)
    }
}
