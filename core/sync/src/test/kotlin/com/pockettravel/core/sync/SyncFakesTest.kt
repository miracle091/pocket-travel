package com.pockettravel.core.sync

import com.pockettravel.core.sync.testing.FakeManifestClient
import com.pockettravel.core.sync.testing.FakeRegionSyncScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** I fake di scheduler e client del catalogo rispondono come i veri nei casi di base. */
class SyncFakesTest {

    @Test
    fun `lo scheduler finto registra la sincronizzazione delle guide e la segna in coda`() = runTest {
        val scheduler = FakeRegionSyncScheduler()
        assertFalse(scheduler.isGuidesSyncPending())

        scheduler.enqueueGuidesSync(onlyOnWifi = true)

        assertTrue(scheduler.isGuidesSyncPending())
        assertEquals(listOf(FakeRegionSyncScheduler.GuidesSyncRequest(onlyOnWifi = true, automatic = true)), scheduler.guidesSyncs)
    }

    @Test
    fun `il client finto lancia l'errore impostato e conta le richieste`() = runTest {
        val client = FakeManifestClient()
        client.failure = IOException("rete assente")

        val error = runCatching { client.fetchManifest() }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(1, client.fetchCount)
    }
}
