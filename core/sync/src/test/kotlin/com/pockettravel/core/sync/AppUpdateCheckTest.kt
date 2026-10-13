package com.pockettravel.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckTest {

    @Test
    fun `segnala un aggiornamento quando il versionCode remoto e' piu' alto`() {
        assertTrue(isAppUpdateAvailable(installedVersionCode = 2, remote = AppVersionEntry("0.3.0", 3)))
    }

    @Test
    fun `nessun aggiornamento quando il versionCode remoto e' uguale a quello installato`() {
        assertFalse(isAppUpdateAvailable(installedVersionCode = 2, remote = AppVersionEntry("0.2.0", 2)))
    }

    @Test
    fun `nessun aggiornamento quando il versionCode remoto e' piu' basso`() {
        assertFalse(isAppUpdateAvailable(installedVersionCode = 3, remote = AppVersionEntry("0.2.0", 2)))
    }
}
