package com.pockettravel.feature.ai

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.io.path.createTempDirectory

// La foto allegata e non inviata sopravvive alla chiusura dell'app da parte di Android: file in cache, nome nello stato salvato.
@OptIn(ExperimentalCoroutinesApi::class)
class SavedPhotoTest {

    private val dir = createTempDirectory("saved-photo-test").toFile()

    private fun blank() = AiUiState(mode = AiEngineMode.ONLINE, isDeviceCapable = true, isModelDownloaded = false, isApiKeyConfigured = true)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `la foto allegata torna dopo la chiusura dell'app e il file sparisce quando parte`() = runTest(UnconfinedTestDispatcher()) {
        val io = UnconfinedTestDispatcher(testScheduler)
        val saved = SavedStateHandle()
        val before = MutableStateFlow(blank())
        val scope = backgroundScope
        saved.keepPhoto(scope, before, dir, io)
        before.update { it.copy(attachedPhoto = AttachedPhoto(byteArrayOf(4, 5, 6))) }
        assertEquals(1, dir.listFiles()?.size)

        // Processo nuovo: solo lo stato salvato e il file restano.
        val restored = SavedStateHandle(mapOf("attached_photo_file" to saved.get<String>("attached_photo_file")))
        val after = MutableStateFlow(blank())
        restored.keepPhoto(scope, after, dir, io)
        assertArrayEquals(byteArrayOf(4, 5, 6), after.value.attachedPhoto?.jpeg)

        after.update { it.copy(attachedPhoto = null) }
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun `un ViewModel nuovo non trova foto e quello che finisce cancella la sua`() = runTest(UnconfinedTestDispatcher()) {
        val io = UnconfinedTestDispatcher(testScheduler)
        val state = MutableStateFlow(blank())
        val close = SavedStateHandle().keepPhoto(backgroundScope, state, dir, io)
        assertNull(state.value.attachedPhoto)

        state.update { it.copy(attachedPhoto = AttachedPhoto(byteArrayOf(1))) }
        close.close()
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun `un ViewModel nuovo cancella le foto rimaste da giorni ma non quelle recenti`() = runTest(UnconfinedTestDispatcher()) {
        val old = dir.resolve("attached-old.jpg").apply { writeBytes(byteArrayOf(1)) }
        old.setLastModified(System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L)
        val recent = dir.resolve("attached-recent.jpg").apply { writeBytes(byteArrayOf(2)) }

        SavedStateHandle().keepPhoto(backgroundScope, MutableStateFlow(blank()), dir, UnconfinedTestDispatcher(testScheduler))

        assertEquals(listOf(recent.name), dir.list()?.toList())
    }
}
