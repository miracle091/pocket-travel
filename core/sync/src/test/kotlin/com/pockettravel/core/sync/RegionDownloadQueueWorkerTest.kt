package com.pockettravel.core.sync

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.pockettravel.core.data.PackageKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RegionDownloadQueueWorkerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        // Rete a consumo: nessuna capacita' NOT_METERED.
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, ShadowNetworkCapabilities.newInstance())
    }

    // Scarica senza rete, segna le regioni e mostra la notifica in primo piano come il download vero.
    private class FakeDownload(private val outcome: (String) -> DownloadOutcome = { DownloadOutcome.SUCCESS }) : RegionDownload {
        val downloaded = mutableListOf<String>()

        override suspend fun run(
            entryJson: String,
            requestedKinds: Set<PackageKind>,
            attempt: Int,
            callbacks: DownloadCallbacks,
            cancelledByUser: () -> Boolean,
        ): DownloadOutcome {
            callbacks.start(entryJson, requestedKinds)
            callbacks.onFiles(50, 100)
            callbacks.onInstalling()
            downloaded += entryJson
            return outcome(entryJson)
        }
    }

    private suspend fun TestScope.queueOf(vararg regions: Pair<String, Boolean>) =
        RegionDownloadQueue(File(folder.root, RegionDownloadQueue.FILE_NAME), UnconfinedTestDispatcher(testScheduler)).apply {
            // La voce del catalogo e' il nome della regione: il download finto la restituisce cosi' com'e'.
            regions.forEach { (regionId, wifi) -> add(regionId, regionId, regionId, setOf(PackageKind.POI), wifi) }
        }

    private fun worker(queue: RegionDownloadQueue, download: RegionDownload) =
        TestListenableWorkerBuilder<RegionDownloadQueueWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                        RegionDownloadQueueWorker(appContext, workerParameters, queue, download)
                },
            )
            .build()

    @Test
    fun `scarica tre nazioni in fila e finisce`() = runTest {
        val queue = queueOf("italia" to false, "francia" to false, "spagna" to false)
        val download = FakeDownload()
        assertEquals(ListenableWorker.Result.success(), worker(queue, download).doWork())
        assertEquals(listOf("italia", "francia", "spagna"), download.downloaded)
        assertEquals(3, queue.state.value.done)
    }

    @Test
    fun `un errore di rete chiede un nuovo tentativo a WorkManager`() = runTest {
        val queue = queueOf("italia" to false)
        assertEquals(ListenableWorker.Result.retry(), worker(queue, FakeDownload { DownloadOutcome.RETRY }).doWork())
        assertEquals(1, queue.state.value.pending.single().attempts)
    }

    @Test
    fun `con la sola rete mobile si riaccoda in attesa del Wi-Fi`() = runTest {
        val queue = queueOf("italia" to true, "francia" to false)
        val download = FakeDownload()
        assertEquals(ListenableWorker.Result.success(), worker(queue, download).doWork())
        assertEquals(listOf("francia"), download.downloaded)
        val waiting = WorkManager.getInstance(context).getWorkInfosForUniqueWork(RegionDownloadQueueWorker.WORK_NAME).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, waiting.state)
        assertEquals(NetworkType.UNMETERED, waiting.constraints.requiredNetworkType)
    }
}
