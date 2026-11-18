package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RegionDownloadQueueRunnerTest {
    @get:Rule
    val folder = TemporaryFolder()

    // Regione e se vuole il Wi-Fi.
    private suspend fun TestScope.queueOf(vararg regions: Pair<String, Boolean>) =
        RegionDownloadQueue(File(folder.root, RegionDownloadQueue.FILE_NAME), UnconfinedTestDispatcher(testScheduler)).apply {
            regions.forEach { (regionId, wifi) -> add(regionId, regionId, "{}", setOf(PackageKind.POI), wifi) }
        }

    @Test
    fun `scarica le voci una alla volta in ordine`() = runTest {
        val queue = queueOf("italia" to false, "francia" to false, "spagna" to false)
        val downloaded = mutableListOf<String>()
        val result = RegionDownloadQueueRunner(queue, { true }) { item ->
            downloaded += item.regionId
            DownloadOutcome.SUCCESS
        }.run()
        assertEquals(QueueRunResult.FINISHED, result)
        assertEquals(listOf("italia", "francia", "spagna"), downloaded)
        assertEquals(3, queue.state.value.done)
    }

    @Test
    fun `un fallimento a meta non blocca la voce dopo`() = runTest {
        val queue = queueOf("italia" to false, "francia" to false, "spagna" to false)
        val downloaded = mutableListOf<String>()
        RegionDownloadQueueRunner(queue, { true }) { item ->
            downloaded += item.regionId
            if (item.regionId == "francia") DownloadOutcome.FAILURE else DownloadOutcome.SUCCESS
        }.run()
        assertEquals(listOf("italia", "francia", "spagna"), downloaded)
        assertEquals(listOf("francia"), queue.state.value.failedRegionIds)
        assertEquals(2, queue.state.value.done)
    }

    @Test
    fun `un errore di rete ferma il giro e la voce va in fondo`() = runTest {
        val queue = queueOf("italia" to false, "francia" to false)
        val result = RegionDownloadQueueRunner(queue, { true }) { DownloadOutcome.RETRY }.run()
        assertEquals(QueueRunResult.RETRY, result)
        assertEquals(listOf("francia", "italia"), queue.state.value.pending.map { it.regionId })
        // Fermo il giro, chi accoda deve poterlo riavviare.
        assertTrue(queue.add("spagna", "spagna", "{}", setOf(PackageKind.POI), false))
    }

    @Test
    fun `con la sola rete mobile restano le voci da Wi-Fi`() = runTest {
        val queue = queueOf("italia" to true, "francia" to false)
        val downloaded = mutableListOf<String>()
        val result = RegionDownloadQueueRunner(queue, { false }) { item ->
            downloaded += item.regionId
            DownloadOutcome.SUCCESS
        }.run()
        assertEquals(QueueRunResult.WAITING_FOR_WIFI, result)
        assertEquals(listOf("francia"), downloaded)
        assertEquals(listOf("italia"), queue.state.value.pending.map { it.regionId })
    }

    @Test
    fun `togliere la voce in corso ne ferma solo il download`() = runTest {
        val queue = queueOf("italia" to false, "francia" to false)
        val started = CompletableDeferred<Unit>()
        val downloaded = mutableListOf<String>()
        val run = async {
            RegionDownloadQueueRunner(queue, { true }) { item ->
                if (item.regionId == "italia") {
                    started.complete(Unit)
                    awaitCancellation()
                }
                downloaded += item.regionId
                DownloadOutcome.SUCCESS
            }.run()
        }
        started.await()
        queue.remove("italia")
        assertEquals(QueueRunResult.FINISHED, run.await())
        assertEquals(listOf("francia"), downloaded)
        assertEquals(1, queue.state.value.done)
    }
}
