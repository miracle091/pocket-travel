package com.pockettravel.feature.ai

import androidx.work.WorkInfo
import com.pockettravel.feature.ai.testing.FakeOnDeviceLlmEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Foto sul dispositivo: chi puo' allegarle, quando partono, il catalogo dell'mmproj e il percorso fino al motore.
class LocalPhotoTest {

    private val twoB = LlmModelCatalog.ALL.first { it.id == "pt-qwen3.5-2b-en" }
    private val photo = AttachedPhoto(byteArrayOf(1, 2, 3))

    private fun state(
        canAttachLocalPhoto: Boolean = true,
        isMmprojDownloaded: Boolean = true,
        mode: AiEngineMode = AiEngineMode.ON_DEVICE,
    ) = AiUiState(
        mode = mode, isDeviceCapable = true, isModelDownloaded = true, isApiKeyConfigured = false,
        canAttachLocalPhoto = canAttachLocalPhoto, isMmprojDownloaded = isMmprojDownloaded, attachedPhoto = photo,
    )

    @Test
    fun `solo i tre 2B leggono le foto e condividono lo stesso file`() {
        val withMmproj = LlmModelCatalog.ALL.filter { it.mmproj != null }

        assertEquals(setOf("qwen3.5-2b", "pt-qwen3.5-2b", "pt-qwen3.5-2b-en"), withMmproj.map { it.id }.toSet())
        assertEquals(1, withMmproj.map { it.mmproj?.fileName }.distinct().size)
        withMmproj.forEach {
            val mmproj = requireNotNull(it.mmproj)
            assertEquals(64, mmproj.sha256.length)
            assertTrue(mmproj.sizeBytes > 0)
            assertTrue(mmproj.url.startsWith("https://huggingface.co/"))
            assertEquals(RamTier.CONFORTEVOLE, it.minRamTier)
        }
    }

    @Test
    fun `l'mmproj non ha lo stesso nome di nessun modello del catalogo`() {
        val names = LlmModelCatalog.ALL.map { it.fileName }.toSet()
        assertTrue(LlmModelCatalog.ALL.mapNotNull { it.mmproj }.none { it.fileName in names })
    }

    @Test
    fun `la foto sul dispositivo vuole un modello con mmproj e la fascia del 2B, non i 4 GB`() {
        val zeroPointEight = LlmModelCatalog.ALL.first { it.id == "pt-qwen3.5-0.8b-en" }

        assertTrue(canReadPhotos(twoB, RamTier.CONFORTEVOLE))
        assertTrue(canReadPhotos(twoB, RamTier.AMPIA))
        assertFalse(canReadPhotos(twoB, RamTier.MINIMO))
        assertFalse(canReadPhotos(twoB, RamTier.RIDOTTA))
        assertFalse(canReadPhotos(zeroPointEight, RamTier.PUNTA))
    }

    @Test
    fun `la foto parte sul dispositivo solo con il modello giusto e l'mmproj scaricato, senza consenso online`() {
        assertSame(photo, state().photoToSend(photo))
        assertSame(photo, state().photoForQuestion(followUp = false, consented = state().photoConsented(onlineConsent = false)))
        assertNull(state(isMmprojDownloaded = false).photoToSend(photo))
        assertNull(state(canAttachLocalPhoto = false).photoToSend(photo))
        assertTrue(state(isMmprojDownloaded = false).needsMmproj)
        assertFalse(state().needsMmproj)
    }

    @Test
    fun `il file per le foto eliminato da fuori si riconosce solo sul dispositivo`() {
        assertTrue(state().lostMmproj(onDisk = false))
        assertFalse(state().lostMmproj(onDisk = true))
        assertFalse(state(isMmprojDownloaded = false).lostMmproj(onDisk = false))
        assertFalse(state(mode = AiEngineMode.ONLINE).lostMmproj(onDisk = false))
    }

    @Test
    fun `online non cambia, il modello del dispositivo non conta e il consenso resta quello del servizio`() {
        val online = state(canAttachLocalPhoto = true, isMmprojDownloaded = false, mode = AiEngineMode.ONLINE)

        assertNull(online.photoToSend(photo))
        assertFalse(online.needsMmproj)
        assertFalse(online.copy(canAttachPhoto = true).photoConsented(onlineConsent = false))
        assertSame(photo, online.copy(canAttachPhoto = true).photoToSend(photo))
    }

    @Test
    fun `il download dell'mmproj mostra l'avanzamento, riesce, fallisce o si annulla`() {
        val offer = state(isMmprojDownloaded = false).copy(offersMmproj = true)

        val running = offer.afterMmprojWork(WorkInfo.State.RUNNING, 0.4f, reportsFailure = false, failureReason = null, isDownloaded = false)
        assertEquals(0.4f, running.mmprojProgress)
        assertTrue(running.offersMmproj)

        val done = running.afterMmprojWork(WorkInfo.State.SUCCEEDED, 1f, reportsFailure = false, failureReason = null, isDownloaded = true)
        assertNull(done.mmprojProgress)
        assertFalse(done.offersMmproj)
        assertTrue(done.isMmprojDownloaded)

        val failed = running.afterMmprojWork(
            WorkInfo.State.FAILED, 0f, reportsFailure = true, failureReason = LlmModelDownloadWorker.FAILURE_REASON_INTEGRITY, isDownloaded = false,
        )
        assertEquals(R.string.ai_error_integrity, failed.errorMessage)
        assertNull(failed.mmprojProgress)

        val oldFailure = offer.afterMmprojWork(WorkInfo.State.FAILED, 0f, reportsFailure = false, failureReason = null, isDownloaded = false)
        assertNull(oldFailure.errorMessage)
        assertNull(running.afterMmprojWork(WorkInfo.State.CANCELLED, 0f, reportsFailure = false, failureReason = null, isDownloaded = false).mmprojProgress)
    }

    @Test
    fun `la foto arriva al motore con il percorso dell'mmproj, senza foto o senza mmproj no`() = runTest {
        val engine = FakeOnDeviceLlmEngine()
        val mmproj = File("fake-models", "mmproj.gguf")

        engine.generateStream("domanda", photoInput(byteArrayOf(9, 8), mmproj)).toList()
        engine.generateStream("domanda senza foto", photoInput(null, mmproj)).toList()
        engine.generateStream("domanda senza mmproj", photoInput(byteArrayOf(9), null)).toList()

        assertEquals(1, engine.images.size)
        assertEquals(mmproj.absolutePath, engine.images.single().mmprojPath)
        assertEquals(listOf<Byte>(9, 8), engine.images.single().bytes.toList())
    }
}
