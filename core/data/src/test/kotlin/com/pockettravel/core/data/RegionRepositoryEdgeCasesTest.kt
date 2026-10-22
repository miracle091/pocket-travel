package com.pockettravel.core.data

import androidx.room.InvalidationTracker
import com.pockettravel.core.data.db.CityDao
import com.pockettravel.core.data.db.ConversationDao
import com.pockettravel.core.data.db.CitySectionEntity
import com.pockettravel.core.data.db.CitySectionMatch
import com.pockettravel.core.data.db.DiplomaticMissionDao
import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import com.pockettravel.core.data.db.GuideSectionMatch
import com.pockettravel.core.data.db.InstalledGuidesEntity
import com.pockettravel.core.data.db.InstalledRegionDao
import com.pockettravel.core.data.db.InstalledRegionEntity
import com.pockettravel.core.data.db.NoteDao
import com.pockettravel.core.data.db.PassportDao
import com.pockettravel.core.data.db.PoiCodeDao
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.PoiRow
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.db.CategoryTag
import com.pockettravel.core.data.db.TransportCount
import com.pockettravel.core.data.db.VaccinationDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Casi limite di RegionRepository con DAO finti. I percorsi che aprono una transazione Room (remove, removePackage,
 * inInstallTransaction) hanno bisogno di un database vero e restano ai test strumentati.
 */
class RegionRepositoryEdgeCasesTest {

    private class MemoryRegionDao : InstalledRegionDao {
        val entities = linkedMapOf<String, InstalledRegionEntity>()
        private val flow = MutableStateFlow<List<InstalledRegionEntity>>(emptyList())
        private val guides = MutableStateFlow<InstalledGuidesEntity?>(null)
        var upserts = 0

        override suspend fun upsert(region: InstalledRegionEntity) {
            upserts++
            entities[region.regionId] = region
            flow.value = entities.values.sortedBy { it.displayName }
        }

        override fun observeAll(): Flow<List<InstalledRegionEntity>> = flow
        override suspend fun findById(regionId: String): InstalledRegionEntity? = entities[regionId]
        override suspend fun deleteById(regionId: String) {
            entities.remove(regionId)
            flow.value = entities.values.toList()
        }

        override suspend fun fillCountryCode(regionId: String, countryCode: String) {
            val current = entities[regionId] ?: return
            if (current.countryCode == null) upsert(current.copy(countryCode = countryCode))
        }

        override suspend fun upsertGuides(guides: InstalledGuidesEntity) { this.guides.value = guides }
        override suspend fun guidesVersion(): String? = guides.value?.version
        override fun observeGuides(): Flow<InstalledGuidesEntity?> = guides
    }

    private object NoGuides : GuideDao {
        override suspend fun insertAll(sections: List<GuideSectionEntity>) = Unit
        override suspend fun sectionsForRegion(regionId: String): List<GuideSectionEntity> = emptyList()
        override suspend fun searchInRegionsRanked(regionIds: List<String>, query: String, candidateLimit: Int): List<GuideSectionMatch> = emptyList()
        override suspend fun deleteAll() = Unit
        override suspend fun optimizeFts() = Unit
    }

    private object NoPois : PoiDao {
        override suspend fun insertAll(pois: List<PoiRow>) = Unit
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

    private object NoCities : CityDao {
        override suspend fun insertAll(sections: List<CitySectionEntity>) = Unit
        override fun citiesForRegion(regionId: String) = throw UnsupportedOperationException()
        override fun mainCitiesForRegion(regionId: String, limit: Int) = throw UnsupportedOperationException()
        override suspend fun coordinatesFor(regionId: String, city: String) = null
        override suspend fun sectionsFor(regionId: String, city: String): List<CitySectionEntity> = emptyList()
        override suspend fun searchInRegionRanked(regionId: String, query: String, city: String?, candidateLimit: Int): List<CitySectionMatch> = emptyList()
        override suspend fun deleteForRegion(regionId: String) = Unit
        override suspend fun optimizeFts() = Unit
    }

    private class NeverOpenedDatabase(private val regions: InstalledRegionDao) : RegionDatabase() {
        override fun guideDao() = NoGuides
        override fun poiDao() = NoPois
        override fun poiCodeDao(): PoiCodeDao = throw UnsupportedOperationException()
        override fun installedRegionDao() = regions
        override fun passportDao(): PassportDao = throw UnsupportedOperationException()
        override fun emergencyNumbersDao(): EmergencyNumbersDao = throw UnsupportedOperationException()
        override fun cityDao(): CityDao = NoCities
        override fun noteDao(): NoteDao = throw UnsupportedOperationException()
        override fun diplomaticMissionDao(): DiplomaticMissionDao = throw UnsupportedOperationException()
        override fun vaccinationDao(): VaccinationDao = throw UnsupportedOperationException()
        override fun conversationDao(): ConversationDao = throw UnsupportedOperationException()
        override fun createInvalidationTracker(): InvalidationTracker = throw UnsupportedOperationException()
        override fun clearAllTables(): Unit = throw UnsupportedOperationException()
    }

    private val dao = MemoryRegionDao()
    private val storage: RegionStorage
    private val repository: RegionRepository

    init {
        val root = createTempDirectory("pocket-travel-region-edge-test").toFile()
        storage = RegionStorage(File(root, "regions").apply { mkdirs() }, File(root, "staging").apply { mkdirs() })
        repository = RoomRegionRepository(dao, NoPois, storage, NeverOpenedDatabase(dao), NoCities, Dispatchers.IO)
    }

    private fun write(regionId: String, packageName: String, bytes: Int, nested: String? = null) {
        val target = if (nested == null) File(storage.directoryFor(regionId), packageName) else File(storage.directoryFor(regionId), "$packageName/$nested")
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(bytes))
    }

    private fun entity(regionId: String = "italia", displayName: String = "Italia") = InstalledRegionEntity(
        regionId = regionId, displayName = displayName, countryCode = "it", mapVersion = "m", routingVersion = "r", poiVersion = "p", poiExtraVersion = "pe",
        addressesVersion = "a", previewVersion = "pv", poiSizeBytes = 1, poiExtraSizeBytes = 2, sizeBytes = 3, installedAt = 4, citiesVersion = "c",
        citiesSizeBytes = 5, transitVersion = "t",
    )

    // ---- lettura ----

    @Test
    fun `observeInstalled converte ogni campo dell'entita' e segue gli aggiornamenti`() = runBlocking {
        assertEquals(emptyList<InstalledRegion>(), repository.observeInstalled().first())
        dao.upsert(entity())

        assertEquals(
            listOf(
                InstalledRegion(
                    regionId = "italia", displayName = "Italia", countryCode = "it", mapVersion = "m", routingVersion = "r", poiVersion = "p",
                    poiExtraVersion = "pe", addressesVersion = "a", poiSizeBytes = 1, poiExtraSizeBytes = 2, sizeBytes = 3, previewVersion = "pv",
                    citiesVersion = "c", citiesSizeBytes = 5, transitVersion = "t",
                ),
            ),
            repository.observeInstalled().first(),
        )
        dao.upsert(entity("austria", "Österreich"))
        assertEquals(listOf("Italia", "Österreich"), repository.observeInstalled().first().map { it.displayName })
    }

    @Test
    fun `displayName e installed per una regione sconosciuta o con id vuoto sono null`() = runBlocking {
        dao.upsert(entity())
        assertEquals("Italia", repository.displayName("italia"))
        assertNull(repository.displayName("mai"))
        assertNull(repository.displayName(""))
        assertNull(repository.installed(""))
        assertNull(repository.installed("ITALIA"))
    }

    // ---- markPackagesInstalled ----

    @Test
    fun `la dimensione della regione somma ogni pacchetto su disco piu' i registrati`() = runBlocking {
        write("italia", RegionStorage.MAP_FILE, 100)
        write("italia", RegionStorage.ROUTING_DIR, 30, nested = "a.rd5")
        write("italia", RegionStorage.ROUTING_DIR, 20, nested = "sub/b.rd5")
        write("italia", RegionStorage.ADDRESSES_FILE, 10)
        write("italia", RegionStorage.PREVIEW_FILE, 7)
        write("italia", RegionStorage.ADDRESSES_CELLS_FILE, 5)
        write("italia", RegionStorage.TRANSIT_DIR, 3, nested = "x.db")
        write("italia", RegionStorage.ADDRESSES_SEARCH_DIR, 2, nested = "c.db")

        repository.markPackagesInstalled(
            "italia", "Italia", "it",
            mapOf(PackageKind.MAP to "1", PackageKind.POI to "1", PackageKind.POI_EXTRA to "1", PackageKind.CITIES to "1"),
            poiSizeBytes = 1_000, poiExtraSizeBytes = 200, citiesSizeBytes = 30,
        )

        assertEquals(100L + 50 + 10 + 7 + 5 + 3 + 2 + 1_000 + 200 + 30, repository.installed("italia")?.sizeBytes)
    }

    @Test
    fun `i parametri di un pacchetto non indicato tra le versioni si ignorano`() = runBlocking {
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.POI to "1"), poiSizeBytes = 10)

        // Dimensioni passate ma pacchetti non nelle versioni: valgono quelle registrate.
        write("italia", RegionStorage.MAP_FILE, 4)
        repository.markPackagesInstalled(
            "italia", "Italia", "it", mapOf(PackageKind.MAP to "2"), poiSizeBytes = 999, poiExtraSizeBytes = 999, citiesSizeBytes = 999,
        )

        val installed = repository.installed("italia")
        assertEquals(10L, installed?.poiSizeBytes)
        assertNull(installed?.poiExtraSizeBytes)
        assertNull(installed?.citiesSizeBytes)
        assertEquals(14L, installed?.sizeBytes)
    }

    @Test
    fun `una dimensione zero e' valida e un POI senza dimensione e' un errore che nomina la regione`() = runBlocking {
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.POI to "1"), poiSizeBytes = 0)
        assertEquals(0L, repository.installed("italia")?.poiSizeBytes)

        val extra = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.markPackagesInstalled("francia", "Francia", "fr", mapOf(PackageKind.POI_EXTRA to "1")) }
        }
        assertTrue(extra.message.orEmpty().contains("francia"))
        assertNull(repository.installed("francia"))
    }

    @Test
    fun `una dimensione al limite dei 64 bit si conserva`() = runBlocking {
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.POI to "1"), poiSizeBytes = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, repository.installed("italia")?.poiSizeBytes)
        assertEquals(Long.MAX_VALUE, repository.installed("italia")?.sizeBytes)
    }

    @Test
    fun `markPackagesInstalled aggiorna il nome e un codice paese esplicito sostituisce quello vecchio`() = runBlocking {
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.CITIES to "1"), citiesSizeBytes = 1)
        repository.markPackagesInstalled("italia", "Italia (Roma)", "xx", mapOf(PackageKind.CITIES to "2"), citiesSizeBytes = 2)

        val installed = repository.installed("italia")
        assertEquals("Italia (Roma)", installed?.displayName)
        assertEquals("xx", installed?.countryCode)
        assertEquals("2", installed?.citiesVersion)
        assertEquals(2L, installed?.citiesSizeBytes)
    }

    @Test
    fun `l'aggiornamento di un pacchetto tiene la dimensione degli altri registrati e cambia solo la sua`() = runBlocking {
        repository.markPackagesInstalled(
            "italia", "Italia", "it", mapOf(PackageKind.POI to "1", PackageKind.CITIES to "1"), poiSizeBytes = 100, citiesSizeBytes = 40,
        )
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.POI to "2"), poiSizeBytes = 150)

        val installed = repository.installed("italia")
        assertEquals(150L, installed?.poiSizeBytes)
        assertEquals(40L, installed?.citiesSizeBytes)
        assertEquals("1", installed?.citiesVersion)
        assertEquals(190L, installed?.sizeBytes)
    }

    @Test
    fun `l'id con un percorso fuori dalla cartella e' rifiutato e non si registra nulla`() = runBlocking {
        for (bad in listOf("../fuori", "a/b", "..", ".")) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.markPackagesInstalled(bad, "Fuori", null, mapOf(PackageKind.CITIES to "1"), citiesSizeBytes = 1) }
            }
        }
        assertTrue(dao.entities.isEmpty())
    }

    @Test
    fun `i nomi con Unicode si registrano e si rileggono uguali`() = runBlocking {
        repository.markPackagesInstalled("sao-tome", "São Tomé 東京 🇸🇹", "st", mapOf(PackageKind.CITIES to "1"), citiesSizeBytes = 1)
        assertEquals("São Tomé 東京 🇸🇹", repository.displayName("sao-tome"))
    }

    // ---- packageBytes ----

    @Test
    fun `packageBytes legge dal disco i pacchetti su file e dai registri gli altri`() {
        write("italia", RegionStorage.MAP_FILE, 11)
        write("italia", RegionStorage.ROUTING_DIR, 22, nested = "s.rd5")
        write("italia", RegionStorage.ADDRESSES_FILE, 33)
        write("italia", RegionStorage.ADDRESSES_SEARCH_DIR, 4, nested = "c.db")
        write("italia", RegionStorage.TRANSIT_DIR, 55, nested = "t.db")
        val region = InstalledRegion(
            "italia", "Italia", "it", mapVersion = "1", routingVersion = "1", poiVersion = "1", poiExtraVersion = "1", addressesVersion = "1",
            poiSizeBytes = 100, poiExtraSizeBytes = 200, sizeBytes = 0, citiesVersion = "1", citiesSizeBytes = 300, transitVersion = "1",
        )

        assertEquals(11L, repository.packageBytes(region, PackageKind.MAP))
        assertEquals(22L, repository.packageBytes(region, PackageKind.ROUTING))
        assertEquals(37L, repository.packageBytes(region, PackageKind.ADDRESSES))
        assertEquals(55L, repository.packageBytes(region, PackageKind.TRANSIT))
        assertEquals(100L, repository.packageBytes(region, PackageKind.POI))
        assertEquals(200L, repository.packageBytes(region, PackageKind.POI_EXTRA))
        assertEquals(300L, repository.packageBytes(region, PackageKind.CITIES))
    }

    @Test
    fun `packageBytes e' null per un pacchetto non installato anche se ha dimensione o file`() {
        write("italia", RegionStorage.MAP_FILE, 11)
        val region = InstalledRegion(
            "italia", "Italia", null, mapVersion = null, routingVersion = null, poiVersion = null, poiExtraVersion = null, addressesVersion = null,
            poiSizeBytes = 100, poiExtraSizeBytes = 200, sizeBytes = 0, citiesSizeBytes = 5,
        )
        for (kind in PackageKind.entries) assertNull("$kind", repository.packageBytes(region, kind))
    }

    @Test
    fun `packageBytes di un pacchetto installato senza file su disco e' zero`() {
        val region = InstalledRegion(
            "vuota", "Vuota", null, mapVersion = "1", routingVersion = "1", poiVersion = null, poiExtraVersion = null, addressesVersion = "1",
            poiSizeBytes = null, poiExtraSizeBytes = null, sizeBytes = 0, transitVersion = "1",
        )
        assertEquals(0L, repository.packageBytes(region, PackageKind.MAP))
        assertEquals(0L, repository.packageBytes(region, PackageKind.ROUTING))
        assertEquals(0L, repository.packageBytes(region, PackageKind.ADDRESSES))
        assertEquals(0L, repository.packageBytes(region, PackageKind.TRANSIT))
    }

    // ---- forgetMissingPackages ----

    @Test
    fun `forgetMissingPackages considera mancante anche un file vuoto e non tocca i pacchetti nel database`() = runBlocking {
        write("italia", RegionStorage.MAP_FILE, 0)
        write("italia", RegionStorage.ADDRESSES_FILE, 6)
        repository.markPackagesInstalled(
            "italia", "Italia", "it",
            mapOf(PackageKind.MAP to "1", PackageKind.ADDRESSES to "1", PackageKind.POI to "1", PackageKind.CITIES to "1"),
            poiSizeBytes = 9, citiesSizeBytes = 8,
        )

        // POI e citta' non hanno file: non si dimenticano mai.
        assertEquals(setOf(PackageKind.MAP), repository.forgetMissingPackages("italia"))
        val installed = repository.installed("italia")
        assertNull(installed?.mapVersion)
        assertEquals("1", installed?.addressesVersion)
        assertEquals("1", installed?.poiVersion)
        assertEquals("1", installed?.citiesVersion)
        assertEquals(6L + 9 + 8, installed?.sizeBytes)
    }

    @Test
    fun `forgetMissingPackages non scrive nulla se i file ci sono`() = runBlocking {
        write("italia", RegionStorage.MAP_FILE, 1)
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "1"))
        val before = dao.upserts

        assertTrue(repository.forgetMissingPackages("italia").isEmpty())
        assertEquals(before, dao.upserts)
    }

    // ---- removePackage / fillCountryCode / guide ----

    @Test
    fun `removePackage di una regione sconosciuta non fa nulla`() = runBlocking {
        repository.removePackage("mai", PackageKind.MAP)
        assertTrue(dao.entities.isEmpty())
    }

    @Test
    fun `fillCountryCode su una regione sconosciuta non crea nulla`() = runBlocking {
        repository.fillCountryCode("mai", "it")
        assertTrue(dao.entities.isEmpty())
    }

    @Test
    fun `markGuidesInstalled sostituisce la versione e la dimensione precedenti`() = runBlocking {
        assertNull(repository.observeInstalledGuides().first())
        repository.markGuidesInstalled("1", 10)
        repository.markGuidesInstalled("2", 0)

        assertEquals("2", repository.installedGuidesVersion())
        assertEquals(InstalledGuides("2", 0), repository.observeInstalledGuides().first())
    }

    // ---- recoverInterruptedActivations ----

    private fun stage(regionId: String, name: String, content: String): File {
        val staged = File(storage.stagingDirectoryFor(regionId, "v-$name"), name)
        staged.parentFile?.mkdirs()
        if (name.contains('.')) staged.writeText(content) else File(staged, "f").also { staged.mkdirs() }.writeText(content)
        return staged
    }

    private fun content(regionId: String, name: String): String? {
        val file = File(storage.directoryFor(regionId), name)
        return when {
            file.isFile -> file.readText()
            file.isDirectory -> File(file, "f").takeIf { it.exists() }?.readText()
            else -> null
        }
    }

    @Test
    fun `un'attivazione interrotta col database alla versione vecchia torna al pacchetto precedente`() = runBlocking {
        write("italia", RegionStorage.MAP_FILE, 0)
        File(storage.directoryFor("italia"), RegionStorage.MAP_FILE).writeText("vecchia")
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "1", PackageKind.CITIES to "1"), citiesSizeBytes = 1)
        // Crash dopo l'attivazione di "2" e prima di commit e del database.
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stage("italia", RegionStorage.MAP_FILE, "nuova"), "2")

        repository.recoverInterruptedActivations("italia")

        assertEquals("vecchia", content("italia", RegionStorage.MAP_FILE))
    }

    @Test
    fun `un'attivazione interrotta dopo la registrazione nel database tiene il pacchetto nuovo`() = runBlocking {
        File(storage.directoryFor("italia"), RegionStorage.ROUTING_DIR).apply { mkdirs() }
        File(storage.directoryFor("italia"), "${RegionStorage.ROUTING_DIR}/f").writeText("vecchio")
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.ROUTING to "1"))
        storage.activatePackage("italia", RegionStorage.ROUTING_DIR, stage("italia", RegionStorage.ROUTING_DIR, "nuovo"), "2")
        // Il database ha gia' la versione "2".
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.ROUTING to "2"))

        repository.recoverInterruptedActivations("italia")

        assertEquals("nuovo", content("italia", RegionStorage.ROUTING_DIR))
        assertFalse(File(storage.directoryFor("italia"), ".${RegionStorage.ROUTING_DIR}.backup").exists())
    }

    @Test
    fun `una prima installazione mai registrata viene cancellata dal recupero`() = runBlocking {
        storage.activatePackage("nuova", RegionStorage.PREVIEW_FILE, stage("nuova", RegionStorage.PREVIEW_FILE, "x"), "1")

        repository.recoverInterruptedActivations("nuova")

        assertNull(content("nuova", RegionStorage.PREVIEW_FILE))
    }

    @Test
    fun `il recupero usa la versione dei civici anche per l'elenco delle celle e la ricerca`() = runBlocking {
        File(storage.directoryFor("italia"), RegionStorage.ADDRESSES_CELLS_FILE).apply { parentFile?.mkdirs() }.writeText("{}")
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.ADDRESSES to "7"))
        storage.activatePackage("italia", RegionStorage.ADDRESSES_CELLS_FILE, stage("italia", RegionStorage.ADDRESSES_CELLS_FILE, "{\"1/2/3\":\"9\"}"), "7")

        repository.recoverInterruptedActivations("italia")

        // La versione registrata dei civici (7) e' quella in attivazione: il file nuovo resta.
        assertEquals(mapOf("1/2/3" to "9"), repository.installedAddressCells("italia"))
    }

    @Test
    fun `recoverInterruptedActivations senza nulla da recuperare non cambia i file`() = runBlocking {
        write("italia", RegionStorage.MAP_FILE, 5)
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "1"))
        repository.recoverInterruptedActivations("italia")
        repository.recoverInterruptedActivations("mai-esistita")

        assertEquals(5L, repository.packageBytes(requireNotNull(repository.installed("italia")), PackageKind.MAP))
    }

    @Test
    fun `installedAddressCells e' vuoto senza il file e legge quello scritto`() {
        assertEquals(emptyMap<String, String>(), repository.installedAddressCells("italia"))
        File(storage.directoryFor("italia"), RegionStorage.ADDRESSES_CELLS_FILE).apply { parentFile?.mkdirs() }
            .writeText(RegionStorage.encodeAddressCells(mapOf("14/8/5" to "2026.01", "14/8/6" to "2026.02")))
        assertEquals(mapOf("14/8/5" to "2026.01", "14/8/6" to "2026.02"), repository.installedAddressCells("italia"))
    }
}
