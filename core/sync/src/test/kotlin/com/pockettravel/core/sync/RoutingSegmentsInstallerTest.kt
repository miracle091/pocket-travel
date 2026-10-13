package com.pockettravel.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class RoutingSegmentsInstallerTest {

    @Test
    fun `sposta i file rd5 in packageDir slash routing`() = runBlocking {
        val packageDir = createTempDirectory("pocket-travel-test").toFile()
        val rd5File = File(packageDir, "E5_N45.rd5")
        rd5File.writeBytes(byteArrayOf(1, 2, 3))

        RoutingSegmentsInstaller(Dispatchers.IO).install(packageDir)

        val routingDir = File(packageDir, RoutingSegmentsInstaller.ROUTING_DIR_NAME)
        assertTrue(routingDir.isDirectory)
        assertTrue(File(routingDir, "E5_N45.rd5").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse("il file va spostato, non copiato: non deve restare nella root del pacchetto", rd5File.exists())
    }

    @Test
    fun `lo spostamento passa dal dispatcher iniettato`() = runTest {
        val packageDir = createTempDirectory("pocket-travel-test").toFile()
        File(packageDir, "E5_N45.rd5").writeBytes(byteArrayOf(1))
        val dispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))

        RoutingSegmentsInstaller(dispatcher).install(packageDir)

        assertTrue(dispatcher.dispatched > 0)
        assertTrue(File(packageDir, "routing/E5_N45.rd5").isFile)
    }

    @Test
    fun `sposta piu' segmenti rd5 se la regione attraversa piu' tile`() = runBlocking {
        val packageDir = createTempDirectory("pocket-travel-test").toFile()
        File(packageDir, "E5_N45.rd5").writeBytes(byteArrayOf(1))
        File(packageDir, "E10_N45.rd5").writeBytes(byteArrayOf(2))

        RoutingSegmentsInstaller(Dispatchers.IO).install(packageDir)

        val routingDir = File(packageDir, RoutingSegmentsInstaller.ROUTING_DIR_NAME)
        assertEquals(setOf("E5_N45.rd5", "E10_N45.rd5"), routingDir.list()?.toSet())
    }

    @Test
    fun `fallisce se il pacchetto non contiene segmenti della rete stradale`() = runBlocking {
        val packageDir = createTempDirectory("pocket-travel-test").toFile()
        try {
            RoutingSegmentsInstaller(Dispatchers.IO).install(packageDir)
            fail("un pacchetto senza rd5 non e' installabile")
        } catch (_: IllegalStateException) {
            // atteso: errore definitivo del pacchetto
        }
    }
    @Test
    fun `non tocca gli altri file del pacchetto`() = runBlocking {
        val packageDir = createTempDirectory("pocket-travel-test").toFile()
        File(packageDir, "E5_N45.rd5").writeBytes(byteArrayOf(1))
        File(packageDir, "content.db").writeText("contenuto")

        RoutingSegmentsInstaller(Dispatchers.IO).install(packageDir)

        assertTrue("content.db non e' un .rd5, deve restare dov'era", File(packageDir, "content.db").exists())
    }

    @Test
    fun `con l'elenco dei segmenti installa solo quelli della richiesta`() = runBlocking {
        val packageDir = createTempDirectory("pocket-travel-test").toFile()
        File(packageDir, "E5_N45.rd5").writeBytes(byteArrayOf(1))
        // Completato da un tentativo precedente con un'altra zona: non fa parte di questa richiesta.
        File(packageDir, "E10_N45.rd5").writeBytes(byteArrayOf(2))

        RoutingSegmentsInstaller(Dispatchers.IO).install(packageDir, setOf("E5_N45.rd5"))

        assertEquals(setOf("E5_N45.rd5"), File(packageDir, RoutingSegmentsInstaller.ROUTING_DIR_NAME).list()?.toSet())
    }
}
