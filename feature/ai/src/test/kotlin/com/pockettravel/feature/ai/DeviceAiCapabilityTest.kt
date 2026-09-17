package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.DeviceAiCapability.Companion.isRamSufficient
import com.pockettravel.feature.ai.DeviceAiCapability.Companion.ramTierFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GB = 1024L * 1024 * 1024

class DeviceAiCapabilityTest {

    @Test
    fun `disables on-device AI on a 3 GB device`() {
        assertFalse(isRamSufficient(3 * GB))
    }

    @Test
    fun `disables on-device AI just below the 4 GB threshold`() {
        assertFalse(isRamSufficient(4 * GB - 1))
    }

    @Test
    fun `enables on-device AI at exactly 4 GB`() {
        assertTrue(isRamSufficient(4 * GB))
    }

    @Test
    fun `enables on-device AI above 4 GB`() {
        assertTrue(isRamSufficient(6 * GB))
    }

    @Test
    fun `ramTierFor reports INSUFFICIENTE below 4 GB`() {
        assertEquals(RamTier.INSUFFICIENTE, ramTierFor(3 * GB))
    }

    @Test
    fun `ramTierFor reports MINIMO between 4 and 8 GB`() {
        assertEquals(RamTier.MINIMO, ramTierFor(4 * GB))
        assertEquals(RamTier.MINIMO, ramTierFor(8 * GB - 1))
    }

    @Test
    fun `ramTierFor reports CONFORTEVOLE between 8 and 12 GB`() {
        assertEquals(RamTier.CONFORTEVOLE, ramTierFor(8 * GB))
        assertEquals(RamTier.CONFORTEVOLE, ramTierFor(12 * GB - 1))
    }

    @Test
    fun `ramTierFor reports AMPIA from 12 GB`() {
        assertEquals(RamTier.AMPIA, ramTierFor(12 * GB))
        assertEquals(RamTier.AMPIA, ramTierFor(16 * GB))
    }
}
