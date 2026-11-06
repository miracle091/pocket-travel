package com.pockettravel.app.updates

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingAppInstallTest {

    private val files = InMemoryPreferencesFactory()

    @Test
    fun `la versione consegnata dall'app e poi installata da' la notifica una volta sola`() {
        val pending = PendingAppInstall(files)
        pending.set(20)

        assertTrue(pending.takeInstalled(20L))
        assertFalse(pending.takeInstalled(20L))
    }

    @Test
    fun `un aggiornamento dal browser a un'altra versione non da' la notifica e toglie il segno`() {
        val pending = PendingAppInstall(files)
        pending.set(20)

        assertFalse(pending.takeInstalled(21L))
        assertFalse(pending.takeInstalled(20L))
    }

    @Test
    fun `senza un'installazione dall'app non c'e' notifica`() {
        assertFalse(PendingAppInstall(files).takeInstalled(20L))
    }

    @Test
    fun `il segno resta dopo il riavvio del processo dovuto all'aggiornamento`() {
        PendingAppInstall(files).set(20)

        assertTrue(PendingAppInstall(files).takeInstalled(20L))
    }
}
