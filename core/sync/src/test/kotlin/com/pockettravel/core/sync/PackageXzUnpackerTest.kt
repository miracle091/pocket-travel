package com.pockettravel.core.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

/**
 * unpackXz() e' la decompressione condivisa da RegionPackageInstaller (poi.db, poi-extra.db,
 * addresses.pmtiles, preview.pmtiles, esercitati anche da RegionPackageInstallerDeviceTest) e
 * GuidesInstaller (guides.db): qui si esercita da sola, senza scaricare nulla ne' toccare
 * Room/android.database.sqlite (xz-java e' Java puro, non serve un device).
 */
class PackageXzUnpackerTest {

    private lateinit var staging: File

    @Before
    fun setUp() {
        staging = createTempDirectory("pocket-travel-xz-test").toFile()
    }

    @After
    fun tearDown() {
        staging.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifestFile(name: String, bytes: ByteArray) = RegionManifestFile(
        name = name,
        url = "https://github.com/o/r/releases/download/region-data/$name",
        sizeBytes = bytes.size.toLong(),
        sha256 = sha256Hex(bytes),
    )

    private fun xzOf(bytes: ByteArray) = ByteArrayOutputStream().also { out ->
        XZOutputStream(out, LZMA2Options()).use { it.write(bytes) }
    }.toByteArray()

    @Test
    fun `decomprime e verifica un file xz`() {
        val original = "contenuto delle guide".toByteArray()
        val compressed = xzOf(original)
        File(staging, "guides.db.xz").writeBytes(compressed)

        unpackXz(staging, manifestFile("guides.db", original), manifestFile("guides.db.xz", compressed))

        assertEquals("contenuto delle guide", File(staging, "guides.db").readText())
    }

    @Test
    fun `senza fileXz non fa nulla`() {
        unpackXz(staging, manifestFile("guides.db", "x".toByteArray()), null)

        assertFalse(File(staging, "guides.db").exists())
    }

    @Test
    fun `un risultato decompresso diverso dal manifest fa fallire e cancella i file temporanei`() {
        val original = "contenuto vero".toByteArray()
        val compressed = xzOf(original)
        File(staging, "guides.db.xz").writeBytes(compressed)
        val wrongManifest = manifestFile("guides.db", original).copy(sha256 = sha256Hex("altro contenuto".toByteArray()))

        try {
            unpackXz(staging, wrongManifest, manifestFile("guides.db.xz", compressed))
            fail("un checksum sbagliato dopo la decompressione deve far fallire")
        } catch (_: PermanentRegionPackageException) {
            // atteso
        }
        assertFalse(File(staging, "guides.db").exists())
        assertFalse(File(staging, "guides.db.xz").exists())
        assertFalse(File(staging, "guides.db.unpack").exists())
    }
}
