package com.pockettravel.core.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pockettravel.core.data.PackageKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * RegionSyncScheduler con il WorkManager reale: vincoli di rete, nomi e tag dei lavori, voce della regione passata
 * in un file senza le reti dei mezzi pubblici non richieste, blocco quando l'app e' troppo vecchia per i dati.
 * Non si asserisce lo stato dei lavori (il worker senza Hilt fallisce subito): solo cio' che e' stato accodato.
 */
@RunWith(AndroidJUnit4::class)
class RegionSyncSchedulerDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { ignoreUnknownKeys = true }
    private val compatibility = AppCompatibility(context) { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
    private val scheduler = WorkManagerRegionSyncScheduler(context, json, compatibility)
    private val workManager get() = WorkManager.getInstance(context)
    private val sha = "a".repeat(64)

    private fun file(name: String) = RegionManifestFile(name, "https://github.com/o/r/releases/download/x/$name", 1, sha)

    private fun entry(regionId: String) = RegionManifestEntry(
        regionId = regionId,
        displayName = regionId,
        updatedAt = "2026-10-01T00:00:00Z",
        map = MapPackageEntry("m1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", 0.0, 0.0, 1.0, 1.0, 0, 14)),
        routing = RoutingPackageEntry("r1", listOf(file("E0_N0.rd5"))),
        poi = PoiPackageEntry("p1", file("poi.db")),
        transit = RegionTransitEntry(
            listOf(
                TransitFeed(
                    "t1", "Rete", listOf(regionId), "CC0-1.0", "Attribuzione", null, "v1", file("transit.db"),
                ),
            ),
        ),
    )

    private fun uniqueWork(name: String) = workManager.getWorkInfosForUniqueWork(name).get()

    private fun downloadsOf(regionId: String) = workManager.getWorkInfosByTag("${SyncConfig.DOWNLOAD_TAG}:$regionId").get()

    // Le voci passate ai worker stanno in un file: le regioni del test hanno un id proprio.
    private fun requestedEntries(): List<RegionManifestEntry> =
        File(context.noBackupFilesDir, "download-requests").listFiles().orEmpty()
            .mapNotNull { runCatching { json.decodeFromString(RegionManifestEntry.serializer(), it.readText()) }.getOrNull() }
            .filter { it.regionId.startsWith(REGION_PREFIX) }

    @Before
    fun setUp() {
        compatibility.update(null)
        cleanUp()
    }

    @After
    fun tearDown() {
        cleanUp()
        compatibility.update(null)
    }

    private fun cleanUp() {
        workManager.cancelAllWork().result.get()
        workManager.pruneWork().result.get()
        File(context.noBackupFilesDir, "download-requests").listFiles().orEmpty().forEach { file ->
            val regionId = runCatching { json.decodeFromString(RegionManifestEntry.serializer(), file.readText()).regionId }.getOrNull()
            if (regionId?.startsWith(REGION_PREFIX) == true) file.delete()
        }
    }

    @Test
    fun ilControlloPeriodicoDelManifestVaSuQualunqueRete() {
        scheduler.schedulePeriodicManifestCheck()
        val work = uniqueWork(SyncConfig.PERIODIC_SYNC_WORK_NAME).first()
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertTrue(RegionManifestSyncWorker::class.java.name in work.tags)
    }

    @Test
    fun ilDownloadHaIlTagDellaRegioneEDelElenco() {
        val region = "${REGION_PREFIX}a"
        assertNotNull(scheduler.enqueueDownload(entry(region), setOf(PackageKind.MAP, PackageKind.POI)))

        val work = downloadsOf(region).single()
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
        assertTrue(SyncConfig.DOWNLOAD_TAG in work.tags)
    }

    @Test
    fun ilDownloadAccodatoPerLaStessaRegioneNonDipendeDaQuelloPrecedente() {
        val region = "${REGION_PREFIX}h"
        scheduler.enqueueDownload(entry(region), setOf(PackageKind.MAP))
        scheduler.enqueueDownload(entry(region), setOf(PackageKind.POI))

        // In catena il secondo sarebbe BLOCKED e fallirebbe insieme al primo.
        val works = downloadsOf(region)
        assertEquals(2, works.size)
        assertTrue(works.none { it.state == WorkInfo.State.BLOCKED })
    }

    @Test
    fun ilDownloadAccodaAncheLeGuideSenzaVincoloDiWifi() {
        scheduler.enqueueDownload(entry("${REGION_PREFIX}b"), setOf(PackageKind.MAP))
        val guides = uniqueWork(SyncConfig.GUIDES_SYNC_WORK_NAME).single()
        assertEquals(NetworkType.CONNECTED, guides.constraints.requiredNetworkType)
    }

    @Test
    fun leGuideInAutomaticoSuWifiAspettanoUnaReteSenzaLimiti() {
        scheduler.enqueueGuidesSync(onlyOnWifi = true)
        assertEquals(NetworkType.UNMETERED, uniqueWork(SyncConfig.GUIDES_SYNC_WORK_NAME).single().constraints.requiredNetworkType)
    }

    @Test
    fun leGuideSuRichiestaDellUtenteSostituisconoQuelleInAttesaDelWifi() {
        scheduler.enqueueGuidesSync(onlyOnWifi = true)
        val waiting = uniqueWork(SyncConfig.GUIDES_SYNC_WORK_NAME).single()

        scheduler.enqueueGuidesSync(onlyOnWifi = false, automatic = false)
        // L'accodamento e' asincrono: si aspetta che il nuovo lavoro compaia sotto il nome.
        val deadline = System.currentTimeMillis() + 5_000
        var requested = uniqueWork(SyncConfig.GUIDES_SYNC_WORK_NAME).firstOrNull { it.id != waiting.id }
        while (requested == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            requested = uniqueWork(SyncConfig.GUIDES_SYNC_WORK_NAME).firstOrNull { it.id != waiting.id }
        }
        assertEquals(NetworkType.CONNECTED, checkNotNull(requested) { "nessun lavoro nuovo per le guide" }.constraints.requiredNetworkType)
        // Quello in attesa del Wi-Fi e' annullato (o, se la rete c'era, gia' finito); con REPLACE non e' piu' sotto il
        // nome, lo si legge per id.
        assertTrue(workManager.getWorkInfoById(waiting.id).get()?.state?.isFinished ?: true)
    }

    @Test
    fun laVoceDellaRegioneSenzaTransitNonPortaLeReti() {
        val region = "${REGION_PREFIX}c"
        scheduler.enqueueDownload(entry(region), setOf(PackageKind.MAP))

        val sent = requestedEntries().single { it.regionId == region }
        assertNull(sent.transit)
    }

    @Test
    fun laVoceDellaRegioneConTransitPortaLeReti() {
        val region = "${REGION_PREFIX}d"
        scheduler.enqueueDownload(entry(region), setOf(PackageKind.MAP, PackageKind.TRANSIT))

        val sent = requestedEntries().single { it.regionId == region }
        assertEquals(listOf("t1"), checkNotNull(sent.transit).feeds.map { it.id })
    }

    @Test
    fun conLAppTroppoVecchiaIlDownloadNonSiAccodaEIlControlloAutomaticoNeanche() {
        compatibility.update(Int.MAX_VALUE)
        val region = "${REGION_PREFIX}e"

        assertNull(scheduler.enqueueDownload(entry(region), setOf(PackageKind.MAP)))
        scheduler.enqueueGuidesSync(onlyOnWifi = true)

        assertTrue(downloadsOf(region).isEmpty())
        assertTrue(uniqueWork(SyncConfig.GUIDES_SYNC_WORK_NAME).isEmpty())
        assertTrue(requestedEntries().none { it.regionId == region })
    }

    @Test
    fun annullareUnDownloadLoTogliePerLaSuaRegioneSoltanto() {
        val a = "${REGION_PREFIX}f"
        val b = "${REGION_PREFIX}g"
        scheduler.enqueueDownload(entry(a), setOf(PackageKind.MAP))
        scheduler.enqueueDownload(entry(b), setOf(PackageKind.MAP))

        scheduler.cancelDownload(a)

        assertTrue(downloadsOf(a).all { it.state.isFinished })
        assertEquals(1, downloadsOf(b).size)
        assertTrue(!runBlocking { scheduler.isDownloadPending(a) })
    }

    private companion object {
        const val REGION_PREFIX = "zz-scheduler-test-"
    }
}
