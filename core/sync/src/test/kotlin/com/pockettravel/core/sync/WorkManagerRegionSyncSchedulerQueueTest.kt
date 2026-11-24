package com.pockettravel.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** I download della coda delle nazioni (RegionDownloadQueue) si riconoscono come quelli accodati a uno a uno, per tag. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class WorkManagerRegionSyncSchedulerQueueTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    private fun TestScope.newQueue() = RegionDownloadQueue(File(folder.root, RegionDownloadQueue.FILE_NAME), UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.scheduler(queue: RegionDownloadQueue) = WorkManagerRegionSyncScheduler(
        context,
        Json,
        AppCompatibility(InMemoryPreferencesFactory()) { 1L },
        UnconfinedTestDispatcher(testScheduler),
        CityPackageCopies(RegionStorage(File(folder.root, "regions"), File(folder.root, "staging"))),
        queue,
    )

    private suspend fun RegionDownloadQueue.addRegion(regionId: String) =
        add(regionId, regionId, "{}", setOf(PackageKind.POI), requiresWifi = false)

    @Test
    fun `una regione nella coda ha il download in sospeso`() = runTest {
        val queue = newQueue()
        queue.addRegion("italia")
        val scheduler = scheduler(queue)

        assertTrue(scheduler.isDownloadPending("italia"))
        assertFalse(scheduler.isDownloadPending("francia"))
    }

    @Test
    fun `la coda ritrovata da un nuovo avvio dell'app tiene il download in sospeso`() = runTest {
        newQueue().addRegion("italia")

        // Un processo nuovo: la coda si rilegge dal file.
        assertTrue(scheduler(newQueue()).isDownloadPending("italia"))
    }

    @Test
    fun `una regione tolta dalla coda non ha piu il download in sospeso`() = runTest {
        val queue = newQueue()
        queue.addRegion("italia")
        queue.remove("italia")

        assertFalse(scheduler(queue).isDownloadPending("italia"))
    }

    @Test
    fun `il download di una regione in coda si osserva dal lavoro della coda`() = runTest {
        val queue = newQueue()
        queue.addRegion("italia")
        RegionDownloadQueueWorker.enqueue(context, NetworkType.CONNECTED)
        val scheduler = scheduler(queue)

        assertEquals(WorkInfo.State.ENQUEUED, scheduler.observeDownload("italia").first()?.state)
        assertNull(scheduler.observeDownload("francia").first())
    }

    @Test
    fun `le regioni della coda stanno tra quelle in download`() = runTest {
        val queue = newQueue()
        queue.addRegion("italia")
        queue.addRegion("francia")

        assertEquals(setOf("italia", "francia"), scheduler(queue).observeDownloadingRegions().first())
    }
}
