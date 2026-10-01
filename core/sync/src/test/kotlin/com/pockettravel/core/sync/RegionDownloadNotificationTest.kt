package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionDownloadNotificationTest {
    @Test
    fun `la percentuale della barra segue i byte`() {
        assertEquals(0, downloadPercent(0, 1_000))
        assertEquals(42, downloadPercent(420, 1_000))
        assertEquals(100, downloadPercent(1_000, 1_000))
    }

    @Test
    fun `senza totale la barra e' indeterminata`() {
        assertNull(downloadPercent(500, 0))
    }

    @Test
    fun `la percentuale non esce da 0 e 100`() {
        assertEquals(100, downloadPercent(1_200, 1_000))
        assertEquals(0, downloadPercent(-5, 1_000))
    }

    @Test
    fun `i pacchetti sono sempre nello stesso ordine`() {
        assertEquals(listOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.TRANSIT), orderedKinds(setOf(PackageKind.TRANSIT, PackageKind.MAP, PackageKind.ROUTING)))
    }

    @Test
    fun `il primo aggiornamento passa, i successivi dopo l'intervallo`() {
        val throttle = UpdateThrottle(intervalMillis = 1_000)
        assertTrue(throttle.tryAcquire(5_000))
        assertFalse(throttle.tryAcquire(5_400))
        assertFalse(throttle.tryAcquire(5_999))
        assertTrue(throttle.tryAcquire(6_000))
        assertFalse(throttle.tryAcquire(6_500))
    }

    @Test
    fun `un aggiornamento scartato non sposta l'intervallo`() {
        val throttle = UpdateThrottle(intervalMillis = 1_000)
        assertTrue(throttle.tryAcquire(0))
        assertFalse(throttle.tryAcquire(900))
        assertTrue(throttle.tryAcquire(1_000))
    }
}
