package com.pockettravel.feature.ai

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

// Nessuna foto parte senza il consenso del servizio scelto: l'avviso si apre, e il consenso dato a un servizio non vale per un altro.
class PhotoConsentTest {

    private val photo = AttachedPhoto(byteArrayOf(1, 2, 3))
    private val asked = AttachedPhoto(byteArrayOf(4, 5), "Cosa dice il cartello?")
    private val modelsDir = createTempDirectory("photo-consent-test").toFile()
    private val store = AiSettingsStore(InMemoryPreferencesFactory(), modelsDir) { RamTier.MINIMO }

    @After
    fun tearDown() {
        modelsDir.deleteRecursively()
    }

    private fun state(attached: AttachedPhoto? = photo, mode: AiEngineMode = AiEngineMode.ONLINE) = AiUiState(
        mode = mode, isDeviceCapable = true, isModelDownloaded = false, isApiKeyConfigured = true,
        canAttachPhoto = true, attachedPhoto = attached, askedPhoto = asked,
    )

    @Test
    fun `senza consenso la foto allegata apre l'avviso e non parte`() {
        val flow = MutableStateFlow(state())

        assertTrue(flow.awaitsPhotoConsent(consented = false))
        assertTrue(flow.value.confirmsPhoto)
        assertNull(flow.value.photoForQuestion(followUp = true, consented = false))
    }

    @Test
    fun `con il consenso la foto allegata parte senza avviso`() {
        val flow = MutableStateFlow(state())

        assertFalse(flow.awaitsPhotoConsent(consented = true))
        assertFalse(flow.value.confirmsPhoto)
        assertSame(photo, flow.value.photoForQuestion(followUp = false, consented = true))
    }

    @Test
    fun `nessun avviso senza foto allegata o con il motore sul dispositivo`() {
        assertFalse(MutableStateFlow(state(attached = null)).awaitsPhotoConsent(consented = false))
        assertFalse(MutableStateFlow(state(mode = AiEngineMode.ON_DEVICE)).awaitsPhotoConsent(consented = false))
        assertNull(state(mode = AiEngineMode.ON_DEVICE).photoForQuestion(followUp = true, consented = true))
    }

    @Test
    fun `un seguito riparte con l'ultima foto solo con il consenso, una domanda nuova no`() {
        val followUp = state(attached = null)

        assertSame(asked, followUp.photoForQuestion(followUp = true, consented = true))
        assertNull(followUp.photoForQuestion(followUp = true, consented = false))
        assertNull(followUp.photoForQuestion(followUp = false, consented = true))
    }

    @Test
    fun `il consenso dato a Gemini non vale per Mistral`() {
        store.setOnlineProvider(OnlineProvider.GEMINI)
        store.setSendsPhotos(OnlineProvider.GEMINI, true)
        assertFalse(MutableStateFlow(state()).awaitsPhotoConsent(store.sendsPhotos()))

        store.setOnlineProvider(OnlineProvider.MISTRAL)

        val flow = MutableStateFlow(state(attached = null))
        assertNull(flow.value.photoForQuestion(followUp = true, consented = store.sendsPhotos()))
        flow.value = state()
        assertTrue(flow.awaitsPhotoConsent(store.sendsPhotos()))
    }
}
