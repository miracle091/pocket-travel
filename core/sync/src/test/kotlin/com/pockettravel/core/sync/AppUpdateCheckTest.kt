package com.pockettravel.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckTest {

    @Test
    fun `reports an update when remote versionCode is higher`() {
        assertTrue(isAppUpdateAvailable(installedVersionCode = 2, remote = AppVersionEntry("0.3.0", 3)))
    }

    @Test
    fun `reports no update when remote versionCode matches installed`() {
        assertFalse(isAppUpdateAvailable(installedVersionCode = 2, remote = AppVersionEntry("0.2.0", 2)))
    }

    @Test
    fun `reports no update when remote versionCode is lower`() {
        assertFalse(isAppUpdateAvailable(installedVersionCode = 3, remote = AppVersionEntry("0.2.0", 2)))
    }
}
