package com.pockettravel.app.crash

import com.pockettravel.feature.ai.LoadedModelBreadcrumb
import com.pockettravel.feature.ai.RamTier
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeExits(var exits: List<ProcessExit> = emptyList()) : ProcessExitReader {
    override fun recent(): List<ProcessExit> = exits
}

private fun exit(
    timestamp: Long,
    reason: ExitReason,
    foreground: Boolean = true,
    description: String? = null,
    trace: ByteArray? = null,
) = ProcessExit(timestamp, reason, foreground, description) { trace }

class CrashCollectorTest {
    @get:Rule val folder = TemporaryFolder()

    private val device = DeviceInfo("0.14.0 (14)", "15 (API 35)", "Google Pixel 8", 7600)
    private val exits = FakeExits()
    private lateinit var store: CrashStore
    private lateinit var breadcrumb: LoadedModelBreadcrumb

    private fun collector(now: Long = 1_000L): CrashCollector {
        val dir = folder.newFolder()
        store = CrashStore(File(dir, "crash"))
        breadcrumb = LoadedModelBreadcrumb(File(dir, "model_loaded.txt"))
        return CrashCollector(store, exits, breadcrumb, device) { now }
    }

    @Test
    fun `al primo avvio le chiusure passate non si riportano`() {
        val collector = collector(now = 5_000L)
        exits.exits = listOf(exit(2_000L, ExitReason.CRASH_NATIVE))
        collector.collect()
        assertNull(store.readPending())
        assertEquals(5_000L, store.lastSeen())
    }

    @Test
    fun `una chiusura per memoria diventa un rapporto con il modello caricato`() {
        val collector = collector()
        store.saveLastSeen(1_000L)
        breadcrumb.write(LoadedModelBreadcrumb.Entry("qwen3-4b", RamTier.AMPIA, RamTier.MINIMO))
        exits.exits = listOf(exit(2_000L, ExitReason.LOW_MEMORY, description = "lmkd"))
        collector.collect()
        val report = store.readPending()
        assertNotNull(report)
        assertEquals(CrashKind.LOW_MEMORY, report?.kind)
        assertEquals("qwen3-4b", report?.loadedModelId)
        assertEquals(RamTier.AMPIA, report?.modelRamTier)
        assertEquals(RamTier.MINIMO, report?.deviceRamTier)
        assertEquals("lmkd", report?.details)
        assertEquals(2_000L, store.lastSeen())
        assertNull("la traccia del processo morto si toglie", breadcrumb.read())
    }

    @Test
    fun `un crash nativo non porta il tombstone, un ANR porta la sua traccia leggibile`() {
        val collector = collector()
        store.saveLastSeen(1_000L)
        val tombstone = byteArrayOf(0, 1, 2) + "Mio padre non respira".toByteArray() + byteArrayOf(0, 3)
        exits.exits = listOf(exit(2_000L, ExitReason.CRASH_NATIVE, trace = tombstone))
        collector.collect()
        assertTrue(store.readPending()?.details.orEmpty().isEmpty())

        val anr = byteArrayOf(0, 1) + "\"main\" prio=5 tid=1 Blocked".toByteArray() + byteArrayOf(0)
        exits.exits = listOf(exit(3_000L, ExitReason.ANR, trace = anr))
        collector.collect()
        assertTrue(store.readPending()?.details.orEmpty().contains("\"main\" prio=5 tid=1 Blocked"))
    }

    @Test
    fun `le chiusure già viste non si riportano`() {
        val collector = collector()
        store.saveLastSeen(2_000L)
        exits.exits = listOf(exit(2_000L, ExitReason.CRASH_NATIVE), exit(1_500L, ExitReason.LOW_MEMORY))
        collector.collect()
        assertNull(store.readPending())
        assertEquals(2_000L, store.lastSeen())
    }

    @Test
    fun `i motivi senza interesse non producono un rapporto ma avanzano il timestamp`() {
        val collector = collector()
        store.saveLastSeen(1_000L)
        exits.exits = listOf(exit(3_000L, ExitReason.OTHER), exit(2_500L, ExitReason.EXIT_SELF, foreground = false))
        collector.collect()
        assertNull(store.readPending())
        assertEquals(3_000L, store.lastSeen())
    }

    @Test
    fun `il crash JVM già scritto dal gestore non si duplica`() {
        val collector = collector()
        store.saveLastSeen(1_000L)
        val written = CrashReport(CrashKind.JVM, 2_000L, "0.14.0 (14)", "15", "Pixel", 7600, screen = "RegionHubRoute", details = "java.lang.IllegalStateException")
        store.writePending(written)
        exits.exits = listOf(exit(2_010L, ExitReason.CRASH))
        collector.collect()
        assertEquals(written, store.readPending())
    }

    @Test
    fun `un crash JVM senza rapporto del gestore si riporta`() {
        val collector = collector()
        store.saveLastSeen(1_000L)
        exits.exits = listOf(exit(2_000L, ExitReason.CRASH, description = "crash"))
        collector.collect()
        assertEquals(CrashKind.JVM, store.readPending()?.kind)
    }

    @Test
    fun `un rapporto nuovo sovrascrive quello in attesa`() {
        val collector = collector()
        store.saveLastSeen(1_000L)
        store.writePending(CrashReport(CrashKind.ANR, 1_500L, "v", "a", "d", 1))
        exits.exits = listOf(exit(2_000L, ExitReason.LOW_MEMORY))
        collector.collect()
        assertEquals(CrashKind.LOW_MEMORY, store.readPending()?.kind)
    }

    @Test
    fun `tra più chiusure vale la più recente tra quelle da riportare`() {
        val picked = selectReportableExit(
            listOf(exit(5_000L, ExitReason.OTHER), exit(4_000L, ExitReason.ANR), exit(3_000L, ExitReason.CRASH)),
            lastSeen = 1_000L,
        )
        assertEquals(4_000L, picked?.timestamp)
    }

    @Test
    fun `l'uscita volontaria conta solo in primo piano`() {
        assertEquals(CrashKind.EXIT_SELF, exit(1L, ExitReason.EXIT_SELF, foreground = true).reportKind())
        assertNull(exit(1L, ExitReason.EXIT_SELF, foreground = false).reportKind())
    }

    @Test
    fun `il rapporto sopravvive al salvataggio e un file troncato si ignora`() {
        collector()
        val report = CrashReport(CrashKind.NATIVE, 9L, "v", "a", "d", 4000, "m", RamTier.AMPIA, RamTier.MINIMO, "S", "x")
        store.writePending(report)
        assertEquals(report, store.readPending())
        assertNull(CrashReport.fromJson("{\"kind\":\"JV"))
        store.clearPending()
        assertNull(store.readPending())
    }
}
