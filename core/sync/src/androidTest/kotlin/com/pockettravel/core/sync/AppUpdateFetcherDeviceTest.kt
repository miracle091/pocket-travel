package com.pockettravel.core.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.RegionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * AppUpdateFetcher con il PackageManager vero: l'APK da controllare è quello del test stesso, servito da un
 * MockWebServer locale. Un'impronta sbagliata scarta il file; un APK leggibile si confronta con l'app installata per
 * pacchetto, versione e certificato di firma.
 */
@RunWith(AndroidJUnit4::class)
class AppUpdateFetcherDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workDir = File(context.cacheDir, "app-update-test")
    private val server = MockWebServer()
    private val realInspector = PackageManagerApkInspector(context)
    private lateinit var storage: AppUpdateStorage
    private lateinit var apkBytes: ByteArray

    @Before
    fun setUp() {
        workDir.deleteRecursively()
        server.start()
        storage = AppUpdateStorage(File(workDir, "app-update"))
        apkBytes = File(context.packageCodePath).readBytes()
    }

    @After
    fun tearDown() {
        server.shutdown()
        workDir.deleteRecursively()
    }

    // L'archivio è letto dal PackageManager; l'app installata è quella vera con versionCode e firmatari a scelta.
    private fun fetcher(installed: (ApkIdentity) -> ApkIdentity): AppUpdateFetcher {
        val inspector = object : ApkInspector {
            override fun installed() = installed(realInspector.installed())
            override fun archive(file: File) = realInspector.archive(file)
        }
        val regionStorage = RegionStorage(File(workDir, "regions").apply { mkdirs() }, File(workDir, "staging").apply { mkdirs() })
        return AppUpdateFetcher(RegionPackageDownloader(OkHttpClient(), regionStorage, Dispatchers.IO), inspector, storage, Dispatchers.IO)
    }

    private fun entry(sha256: String = sha256Hex(apkBytes)) = AppVersionEntry("0.15.0", 2, sha256, apkBytes.size.toLong())

    private fun serveApk() = server.enqueue(MockResponse().setBody(Buffer().write(apkBytes)))

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun unImprontaSbagliataScartaIlFile() {
        serveApk()
        assertThrows(PermanentRegionPackageException::class.java) {
            runBlocking { fetcher { it }.fetch(entry("0".repeat(64)), server.url("/app-release.apk").toString()) }
        }
        assertEquals(emptyList<String>(), storage.dir.list().orEmpty().toList())
    }

    @Test
    fun unApkFirmatoComeLAppInstallataEPronto() = runBlocking {
        val installed = realInspector.installed()
        assertTrue("l'app installata ha almeno un firmatario", installed.signers.isNotEmpty())
        serveApk()
        val failure = fetcher { it.copy(versionCode = it.versionCode - 1) }
            .fetch(entry(), server.url("/app-release.apk").toString())
        assertNull(failure)
        assertTrue(storage.apkFile(2).isFile)
    }

    @Test
    fun unApkFirmatoConUnAltroCertificatoSiCancella() = runBlocking {
        serveApk()
        val failure = fetcher { it.copy(versionCode = it.versionCode - 1, signers = setOf("0".repeat(64))) }
            .fetch(entry(), server.url("/app-release.apk").toString())
        assertEquals(AppUpdateFailure.SIGNATURE, failure)
        assertFalse(storage.apkFile(2).exists())
    }

    @Test
    fun unApkNonPiuNuovoSiCancella() = runBlocking {
        serveApk()
        val failure = fetcher { it }.fetch(entry(), server.url("/app-release.apk").toString())
        assertEquals(AppUpdateFailure.NOT_NEWER, failure)
        assertFalse(storage.apkFile(2).exists())
    }
}
