package com.pockettravel.core.sync

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RegionRoutingGraphInstallerTest {

    @Test
    fun `sposta i file rd5 in packageDir slash routing`() = runBlocking {
        val packageDir = createTempDirectory("pocket-travel-test").toFile()
        val rd5File = File(packageDir, "E5_N45.rd5")
        rd5File.writeBytes(byteArrayOf(1, 2, 3))

        RegionRoutingGraphInstaller().install(packageDir)

        val routingDir = File(packageDir, RegionRoutingGraphInstaller.ROUTING_DIR_NAME)
        assertTrue(routingDir.isDirectory)
        assertTrue(File(routingDir, "E5_N45.rd5").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse("il file va spostato, non copiato: non deve restare nella root del pacchetto", rd5File.exists())
    }

    @Test
    fun `sposta piu' segmenti rd5 se la regione attraversa piu' tile`() = runBlocking {
        val packageDir = createTempDirectory("pocket-travel-test").toFile()
        File(packageDir, "E5_N45.rd5").writeBytes(byteArrayOf(1))
        File(packageDir, "E10_N45.rd5").writeBytes(byteArrayOf(2))

        RegionRoutingGraphInstaller().install(packageDir)

        val routingDir = File(packageDir, RegionRoutingGraphInstaller.ROUTING_DIR_NAME)
        assertEquals(setOf("E5_N45.rd5", "E10_N45.rd5"), routingDir.list()?.toSet())
    }

    @Test
    fun `fallisce se il pacchetto non contiene segmenti routing`() = runBlocking {
        val packageDir = createTempDirectory("pocket-travel-test").toFile()
        try {
            RegionRoutingGraphInstaller().install(packageDir)
            fail("un pacchetto senza rd5 non e' installabile")
        } catch (_: IllegalStateException) {
            // expected permanent package failure
        }
    }
    @Test
    fun `non tocca gli altri file del pacchetto`() = runBlocking {
        val packageDir = createTempDirectory("pocket-travel-test").toFile()
        File(packageDir, "E5_N45.rd5").writeBytes(byteArrayOf(1))
        File(packageDir, "content.db").writeText("contenuto")

        RegionRoutingGraphInstaller().install(packageDir)

        assertTrue("content.db non e' un .rd5, deve restare dov'era", File(packageDir, "content.db").exists())
    }
}
