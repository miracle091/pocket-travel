package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RegionDownloadQueueTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file: File get() = File(folder.root, RegionDownloadQueue.FILE_NAME)

    private fun TestScope.queue() = RegionDownloadQueue(file, UnconfinedTestDispatcher(testScheduler))

    private suspend fun RegionDownloadQueue.add(regionId: String, requiresWifi: Boolean = false, kinds: Set<PackageKind> = setOf(PackageKind.POI)) =
        add(regionId, regionId, "{}", kinds, requiresWifi)

    private suspend fun RegionDownloadQueue.claimKey(): String = requireNotNull(claimNext(unmetered = true)).key

    @Test
    fun `le voci escono nell'ordine in cui sono entrate`() = runTest {
        val queue = queue()
        queue.add("italia")
        queue.add("francia")
        queue.add("spagna")
        val order = mutableListOf<String>()
        while (true) {
            val item = queue.claimNext(unmetered = true) ?: break
            order += item.regionId
            queue.finish(item.key, DownloadOutcome.SUCCESS)
        }
        assertEquals(listOf("italia", "francia", "spagna"), order)
        assertEquals(3, queue.state.value.done)
        assertEquals(3, queue.state.value.total)
    }

    @Test
    fun `il worker va avviato solo se nessuno sta scaricando`() = runTest {
        val queue = queue()
        assertTrue(queue.add("italia"))
        queue.claimNext(unmetered = true)
        assertFalse(queue.add("francia"))
        queue.release()
        assertTrue(queue.add("spagna"))
    }

    @Test
    fun `la stessa regione in attesa resta una voce sola con tutti i pacchetti`() = runTest {
        val queue = queue()
        queue.add("italia", kinds = setOf(PackageKind.POI))
        queue.add("italia", kinds = setOf(PackageKind.MAP))
        assertEquals(listOf(setOf(PackageKind.POI, PackageKind.MAP)), queue.state.value.pending.map { it.kinds })
    }

    @Test
    fun `togliere la voce in corso e una in attesa`() = runTest {
        val queue = queue()
        queue.add("italia")
        queue.add("francia")
        queue.add("spagna")
        val current = queue.claimKey()
        queue.remove("italia")
        assertNull(queue.state.value.current)
        queue.remove("spagna")
        // La fine del download tolto non conta come fatta.
        queue.finish(current, DownloadOutcome.SUCCESS)
        assertEquals(0, queue.state.value.done)
        assertEquals("francia", queue.claimNext(unmetered = true)?.regionId)
        assertTrue(queue.state.value.pending.isEmpty())
    }

    @Test
    fun `clear svuota la coda e il conto`() = runTest {
        val queue = queue()
        queue.add("italia")
        queue.add("francia")
        queue.finish(queue.claimKey(), DownloadOutcome.SUCCESS)
        queue.clear()
        assertEquals(QueueState(), queue.state.value)
        assertNull(queue.claimNext(unmetered = true))
    }

    @Test
    fun `una nuova istanza sullo stesso file riprende dalla voce in corso`() = runTest {
        val first = queue()
        first.add("italia")
        first.add("francia")
        first.claimNext(unmetered = true)
        val second = queue()
        assertEquals("italia", second.claimNext(unmetered = true)?.regionId)
        assertEquals(listOf("francia"), second.state.value.pending.map { it.regionId })
    }

    @Test
    fun `una voce da ritentare va in fondo con un tentativo in piu`() = runTest {
        val queue = queue()
        queue.add("italia")
        queue.add("francia")
        queue.finish(queue.claimKey(), DownloadOutcome.RETRY)
        assertEquals(listOf("francia" to 0, "italia" to 1), queue.state.value.pending.map { it.regionId to it.attempts })
    }

    @Test
    fun `con la sola rete mobile le voci da Wi-Fi si saltano`() = runTest {
        val queue = queue()
        queue.add("italia", requiresWifi = true)
        queue.add("francia")
        val francia = requireNotNull(queue.claimNext(unmetered = false))
        assertEquals("francia", francia.regionId)
        queue.finish(francia.key, DownloadOutcome.SUCCESS)
        assertNull(queue.claimNext(unmetered = false))
        assertEquals("italia", queue.claimNext(unmetered = true)?.regionId)
    }

    @Test
    fun `una coda nuova dopo una vuota riparte dal conto zero`() = runTest {
        val queue = queue()
        queue.add("italia")
        queue.finish(queue.claimKey(), DownloadOutcome.FAILURE)
        assertEquals(listOf("italia"), queue.state.value.failedRegionIds)
        assertNull(queue.claimNext(unmetered = true))
        queue.add("francia")
        assertEquals(1, queue.state.value.total)
        assertTrue(queue.state.value.failedRegionIds.isEmpty())
    }

    @Test
    fun `le citta di un cambio di lingua si tolgono da sole`() = runTest {
        val queue = queue()
        queue.add("italia", "Italia", "{}", setOf(PackageKind.CITIES), requiresWifi = false, citiesReload = true)
        queue.add("francia")
        queue.removeCitiesReload()
        assertEquals(listOf("francia"), queue.state.value.pending.map { it.regionId })
    }
}
