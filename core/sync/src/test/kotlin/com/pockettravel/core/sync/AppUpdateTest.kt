package com.pockettravel.core.sync

import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.pockettravel.core.data.RegionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

class AppUpdateTest {

    private val server = MockWebServer()
    private lateinit var root: File
    private lateinit var storage: AppUpdateStorage
    private lateinit var fetcher: AppUpdateFetcher
    private val installed = ApkIdentity("com.pockettravel.app", 10, setOf("aa"))
    private var archive: ApkIdentity? = ApkIdentity("com.pockettravel.app", 11, setOf("aa"))

    private val inspector = object : ApkInspector {
        override fun installed() = this@AppUpdateTest.installed
        override fun archive(file: File) = this@AppUpdateTest.archive
    }

    @Before
    fun setUp() {
        server.start()
        root = createTempDirectory("pocket-travel-app-update-test").toFile()
        storage = AppUpdateStorage(File(root, "app-update"))
        val regionStorage = RegionStorage(File(root, "regions").apply { mkdirs() }, File(root, "staging").apply { mkdirs() })
        fetcher = AppUpdateFetcher(
            RegionPackageDownloader(OkHttpClient(), regionStorage, Dispatchers.IO),
            inspector,
            storage,
            Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        root.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun entry(bytes: ByteArray, sha: String = sha256Hex(bytes)) =
        AppVersionEntry("0.15.0", 11, sha, bytes.size.toLong())

    @Test
    fun `l'indirizzo dell'APK e' quello della release del tag`() {
        assertEquals(
            "https://github.com/miracle091/pocket-travel/releases/download/v0.15.0/app-release.apk",
            SyncConfig.appApkUrl("0.15.0"),
        )
    }

    @Test
    fun `controllo dell'archivio`() {
        assertNull(checkApk(archive, installed))
        assertEquals(AppUpdateFailure.UNREADABLE, checkApk(null, installed))
        assertEquals(AppUpdateFailure.PACKAGE, checkApk(archive!!.copy(packageName = "altro"), installed))
        assertEquals(AppUpdateFailure.NOT_NEWER, checkApk(archive!!.copy(versionCode = 10), installed))
        assertEquals(AppUpdateFailure.NOT_NEWER, checkApk(archive!!.copy(versionCode = 9), installed))
        assertEquals(AppUpdateFailure.SIGNATURE, checkApk(archive!!.copy(signers = setOf("bb")), installed))
        assertEquals(AppUpdateFailure.SIGNATURE, checkApk(archive!!.copy(signers = setOf("aa", "bb")), installed))
        assertEquals(AppUpdateFailure.SIGNATURE, checkApk(archive!!.copy(signers = emptySet()), installed))
    }

    @Test
    fun `pulizia all'avvio`() {
        assertTrue(isStaleUpdateFile("10.apk", 10))
        assertTrue(isStaleUpdateFile("9.apk.part", 10))
        assertFalse(isStaleUpdateFile("11.apk", 10))
        assertFalse(isStaleUpdateFile("11.apk.part", 10))
        assertTrue(isStaleUpdateFile("note.txt", 10))
        assertTrue(isStaleUpdateFile("11.apk.bak", 10))

        storage.dir.mkdirs()
        listOf("9.apk", "10.apk.part", "11.apk", "12.apk.part", "altro.txt").forEach { File(storage.dir, it).writeText("x") }
        storage.cleanUp(10)
        assertEquals(listOf("11.apk", "12.apk.part"), storage.dir.list().orEmpty().sorted())
    }

    @Test
    fun `scarica, controlla e lascia l'APK pronto`() = runBlocking {
        val bytes = "apk di prova".toByteArray()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val failure = fetcher.fetch(entry(bytes), server.url("/app-release.apk").toString())
        assertNull(failure)
        assertEquals(bytes.toList(), storage.apkFile(11).readBytes().toList())
        assertFalse(File(storage.dir, "11.apk.part").exists())
    }

    @Test
    fun `l'impronta in maiuscolo vale come in minuscolo`() = runBlocking {
        val bytes = "apk di prova".toByteArray()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        assertNull(fetcher.fetch(entry(bytes, sha256Hex(bytes).uppercase()), server.url("/a.apk").toString()))
    }

    @Test
    fun `un'impronta sbagliata scarta il file`() = runBlocking {
        val bytes = "apk di prova".toByteArray()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        try {
            fetcher.fetch(entry(bytes, "0".repeat(64)), server.url("/a.apk").toString())
            fail("atteso PermanentRegionPackageException")
        } catch (_: PermanentRegionPackageException) {
            // atteso
        }
        assertEquals(emptyList<String>(), storage.dir.list().orEmpty().toList())
    }

    @Test
    fun `un APK con un controllo fallito si cancella`() = runBlocking {
        val bytes = "apk di prova".toByteArray()
        archive = requireNotNull(archive).copy(signers = setOf("bb"))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        assertEquals(AppUpdateFailure.SIGNATURE, fetcher.fetch(entry(bytes), server.url("/a.apk").toString()))
        assertFalse(storage.apkFile(11).exists())
    }

    @Test
    fun `stato dal lavoro`() {
        assertEquals(AppUpdateState.Idle, appUpdateState(null, Data.EMPTY, Data.EMPTY, storage))
        assertEquals(AppUpdateState.Idle, appUpdateState(WorkInfo.State.CANCELLED, Data.EMPTY, Data.EMPTY, storage))
        val progress = Data.Builder()
            .putLong(AppUpdateDownloadWorker.KEY_BYTES_DOWNLOADED, 5)
            .putLong(AppUpdateDownloadWorker.KEY_TOTAL_BYTES, 20)
            .build()
        assertEquals(AppUpdateState.Downloading(5, 20), appUpdateState(WorkInfo.State.RUNNING, Data.EMPTY, progress, storage))
        assertEquals(AppUpdateState.Downloading(0, 0), appUpdateState(WorkInfo.State.ENQUEUED, Data.EMPTY, Data.EMPTY, storage))
        val failed = Data.Builder().putString(AppUpdateDownloadWorker.KEY_FAILURE, "SIGNATURE").putInt(AppUpdateDownloadWorker.KEY_VERSION_CODE, 11).build()
        assertEquals(AppUpdateState.Failed(AppUpdateFailure.SIGNATURE, 11), appUpdateState(WorkInfo.State.FAILED, failed, Data.EMPTY, storage))

        val done = Data.Builder().putInt(AppUpdateDownloadWorker.KEY_VERSION_CODE, 11).build()
        assertEquals(AppUpdateState.Idle, appUpdateState(WorkInfo.State.SUCCEEDED, done, Data.EMPTY, storage))
        storage.dir.mkdirs()
        storage.apkFile(11).writeText("x")
        assertEquals(
            AppUpdateState.Ready(11, storage.apkFile(11)),
            appUpdateState(WorkInfo.State.SUCCEEDED, done, Data.EMPTY, storage),
        )
    }

    @Test
    fun `download fermo per la rete legge i byte dal file parziale`() {
        storage.dir.mkdirs()
        storage.partFile(11).writeBytes(ByteArray(5))
        val tags = setOf(TAG_VERSION_CODE + 11, TAG_TOTAL_BYTES + 20)
        assertEquals(
            AppUpdateState.Downloading(5, 20, AppUpdateWait.NETWORK),
            appUpdateState(WorkInfo.State.ENQUEUED, Data.EMPTY, Data.EMPTY, storage, tags, AppUpdateWait.NETWORK),
        )
        val progress = Data.Builder()
            .putLong(AppUpdateDownloadWorker.KEY_BYTES_DOWNLOADED, 8)
            .putLong(AppUpdateDownloadWorker.KEY_TOTAL_BYTES, 20)
            .build()
        assertEquals(AppUpdateState.Downloading(8, 20), appUpdateState(WorkInfo.State.RUNNING, Data.EMPTY, progress, storage, tags))
    }

    @Test
    fun `rete che manca al download`() {
        val none = ActiveNetwork(connected = false, unmetered = false)
        val mobile = ActiveNetwork(connected = true, unmetered = false)
        val wifi = ActiveNetwork(connected = true, unmetered = true)
        assertEquals(AppUpdateWait.NETWORK, networkWait(NetworkType.CONNECTED, none))
        assertNull(networkWait(NetworkType.CONNECTED, mobile))
        assertEquals(AppUpdateWait.WIFI, networkWait(NetworkType.UNMETERED, mobile))
        assertEquals(AppUpdateWait.WIFI, networkWait(NetworkType.UNMETERED, none))
        assertNull(networkWait(NetworkType.UNMETERED, wifi))
        assertNull(networkWait(null, none))
    }
}
