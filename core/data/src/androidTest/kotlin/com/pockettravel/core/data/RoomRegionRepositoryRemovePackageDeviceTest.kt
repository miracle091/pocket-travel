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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.io.path.createTempDirectory

/** `removePackage` su un database Room vero: la transazione non si puo' provare con i DAO finti della JVM. */
@RunWith(AndroidJUnit4::class)
class RoomRegionRepositoryRemovePackageDeviceTest {

    private lateinit var db: RegionDatabase
    private lateinit var root: File
    private lateinit var repository: RoomRegionRepository

    // Dopo la prima lettura di removePackage registra un altro pacchetto, come farebbe un'installazione in parallelo.
    private var onFirstRead: (suspend () -> Unit)? = null

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, RegionDatabase::class.java).build()
        root = createTempDirectory("pocket-travel-remove-package-test").toFile()
        val storage = RegionStorage(File(root, "regions").apply { mkdirs() }, File(root, "staging").apply { mkdirs() })
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
}
