package com.pockettravel.core.sync

import androidx.core.content.edit
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppCompatibilityTest {

    private val files = InMemoryPreferencesFactory()

    private fun compatibility(installed: Long = 10L) = AppCompatibility(files) { installed }

    @Test
    fun `senza versione minima l'app non va aggiornata`() {
        assertFalse(compatibility().requiresAppUpdate())
    }

    @Test
    fun `una versione minima piu' alta di quella installata chiede l'aggiornamento`() {
        val compatibility = compatibility(installed = 10L)
        compatibility.update(11)

        assertTrue(compatibility.requiresAppUpdate())
    }

    @Test
    fun `una versione minima uguale o piu' bassa non lo chiede`() {
        val compatibility = compatibility(installed = 10L)
        compatibility.update(10)
        assertFalse(compatibility.requiresAppUpdate())

        compatibility.update(3)
        assertFalse(compatibility.requiresAppUpdate())
    }

    @Test
    fun `null toglie la versione minima`() {
        val compatibility = compatibility(installed = 10L)
        compatibility.update(11)
        compatibility.update(null)

        assertFalse(compatibility.requiresAppUpdate())
    }

    @Test
    fun `la versione minima resta per la prossima apertura`() {
        compatibility().update(99)

        assertTrue(compatibility().requiresAppUpdate())
    }

    @Test
    fun `legge il file app_compatibility salvato dall'app`() {
        files.open("app_compatibility").edit { putInt("min_version_code", 12) }

        assertTrue(compatibility(installed = 11L).requiresAppUpdate())
        assertFalse(compatibility(installed = 12L).requiresAppUpdate())
    }

    @Test
    fun `il messaggio all'utente arriva a chi lo raccoglie, uno per richiesta`() = runTest {
        val compatibility = compatibility()
        var messages = 0
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { compatibility.updateRequiredMessages.collect { messages++ } }

        compatibility.showUpdateRequiredMessage()
        compatibility.showUpdateRequiredMessage()

        assertEquals(2, messages)
        job.cancel()
    }
}
