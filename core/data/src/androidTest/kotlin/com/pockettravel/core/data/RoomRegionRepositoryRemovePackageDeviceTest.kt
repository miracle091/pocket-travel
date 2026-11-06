package com.pockettravel.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.db.InstalledRegionDao
import com.pockettravel.core.data.db.InstalledRegionEntity
import com.pockettravel.core.data.db.RegionDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.io.path.createTempDirectory

/** `removePackage` e `forgetMissingPackages` su un database Room vero: la transazione non si puo' provare con i DAO finti della JVM. */
@RunWith(AndroidJUnit4::class)
class RoomRegionRepositoryRemovePackageDeviceTest {

    private lateinit var db: RegionDatabase
    private lateinit var root: File
    private lateinit var storage: RegionStorage
    private lateinit var repository: RoomRegionRepository

    // Dopo la prima lettura di removePackage o forgetMissingPackages registra un altro pacchetto, come farebbe un'installazione in parallelo.
    private var onFirstRead: (suspend () -> Unit)? = null

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, RegionDatabase::class.java).build()
        root = createTempDirectory("pocket-travel-remove-package-test").toFile()
        storage = RegionStorage(File(root, "regions").apply { mkdirs() }, File(root, "staging").apply { mkdirs() })
        val real = db.installedRegionDao()
        val dao = object : InstalledRegionDao by real {
            override suspend fun findById(regionId: String): InstalledRegionEntity? {
                val found = real.findById(regionId)
                onFirstRead?.let { hook ->
                    onFirstRead = null
                    hook()
                }
                return found
            }
        }
        repository = RoomRegionRepository(dao, db.poiDao(), storage, db, db.cityDao(), Dispatchers.IO)
    }

    @After
    fun close() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun unPacchettoRegistratoDopoLaPrimaLetturaNonSiPerde() = runBlocking {
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "1"))
        onFirstRead = { repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.ROUTING to "2")) }

        repository.removePackage("italia", PackageKind.MAP)

        val installed = repository.installed("italia")
        assertNotNull(installed)
        assertNull(installed?.mapVersion)
        assertEquals("2", installed?.routingVersion)
    }

    @Test
    fun leCopieDelleCittaSiCancellanoConLeCitta() = runBlocking {
        val regionDir = storage.directoryFor("italia")
        File(regionDir, RegionStorage.MAP_FILE).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(5))
        val copies = File(regionDir, RegionStorage.CITIES_COPIES_DIR).apply { mkdirs() }
        File(copies, "abc").writeBytes(ByteArray(40))
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "1", PackageKind.CITIES to "1"), citiesSizeBytes = 300)

        repository.removePackage("italia", PackageKind.CITIES)

        assertFalse(copies.exists())
        assertEquals(5L, repository.installed("italia")?.sizeBytes)
    }

    @Test
    fun forgetMissingPackagesTogliePacchettiSenzaFileETieneGliAltri() = runBlocking {
        val regionDir = storage.directoryFor("sint-maarten")
        File(regionDir, RegionStorage.MAP_FILE).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(100))
        // Cartella della rete stradale rimasta vuota, come dopo la cancellazione dei segmenti; file vuoto = mancante.
        File(regionDir, RegionStorage.ROUTING_DIR).mkdirs()
        File(regionDir, RegionStorage.ADDRESSES_FILE).writeBytes(ByteArray(0))
        repository.markPackagesInstalled(
            "sint-maarten", "Sint Maarten", "sx",
            mapOf(PackageKind.MAP to "1", PackageKind.ROUTING to "1", PackageKind.ADDRESSES to "1", PackageKind.POI to "1", PackageKind.TRANSIT to "1"),
            poiSizeBytes = 50,
        )

        assertEquals(setOf(PackageKind.ROUTING, PackageKind.ADDRESSES, PackageKind.TRANSIT), repository.forgetMissingPackages("sint-maarten"))
        val installed = repository.installed("sint-maarten")
        assertEquals("1", installed?.mapVersion)
        assertNull(installed?.routingVersion)
        assertNull(installed?.addressesVersion)
        assertNull(installed?.transitVersion)
        // I POI stanno nel database, senza file: non si dimenticano.
        assertEquals("1", installed?.poiVersion)
        assertEquals(150L, installed?.sizeBytes)
    }

    @Test
    fun forgetMissingPackagesNonDimenticaUnaVersioneInstallataDopoLaPrimaLettura() = runBlocking {
        File(storage.directoryFor("italia"), RegionStorage.ROUTING_DIR).mkdirs()
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "1", PackageKind.ROUTING to "1"))
        // La mappa 2 arriva mentre si controllano i file: il controllo riguardava la 1.
        onFirstRead = { repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "2")) }

        assertEquals(setOf(PackageKind.ROUTING), repository.forgetMissingPackages("italia"))
        val installed = repository.installed("italia")
        assertEquals("2", installed?.mapVersion)
        assertNull(installed?.routingVersion)
    }
}
