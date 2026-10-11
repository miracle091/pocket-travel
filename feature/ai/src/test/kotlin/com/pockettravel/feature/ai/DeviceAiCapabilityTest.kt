package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.DeviceAiCapability.Companion.inferenceThreadCountFor
import com.pockettravel.feature.ai.DeviceAiCapability.Companion.ramTierFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GB = 1024L * 1024 * 1024

private fun Double.gib(): Long = (this * GB).toLong()

// Stessa regola di DeviceAiCapability.isOnDeviceAiSupported(), che in JVM puro non si puo' chiamare (serve un Context).
private fun enablesOnDeviceAi(totalMemBytes: Long) = ramTierFor(totalMemBytes) != RamTier.INSUFFICIENTE

class DeviceAiCapabilityTest {

    // totalMem dei telefoni veri: meno della RAM dichiarata (memoria riservata, GB decimali), mai sotto l'85%.
    @Test
    fun `a 3 GB phone has no on-device AI`() {
        assertFalse(enablesOnDeviceAi(2.8.gib()))
    }

    @Test
    fun `a 4 GB phone reporting 3,6 GiB enables on-device AI`() {
        assertTrue(enablesOnDeviceAi(3.6.gib()))
        assertEquals(RamTier.MINIMO, ramTierFor(3.6.gib()))
    }

    @Test
    fun `just below 85 percent of 4 GiB is not enough`() {
        assertFalse(enablesOnDeviceAi(DeviceAiCapability.MIN_RAM_BYTES - 1))
        assertTrue(enablesOnDeviceAi(DeviceAiCapability.MIN_RAM_BYTES))
    }

    @Test
    fun `6 and 8 GB phones are CONFORTEVOLE`() {
        assertEquals(RamTier.CONFORTEVOLE, ramTierFor(5.5.gib()))
        assertEquals(RamTier.CONFORTEVOLE, ramTierFor(7.4.gib()))
        assertEquals(RamTier.MINIMO, ramTierFor(DeviceAiCapability.COMFORTABLE_RAM_BYTES - 1))
    }

    @Test
    fun `12 GB phones are AMPIA`() {
        assertEquals(RamTier.AMPIA, ramTierFor(11.2.gib()))
        assertEquals(RamTier.AMPIA, ramTierFor(16 * GB))
        assertEquals(RamTier.CONFORTEVOLE, ramTierFor(10 * GB))
    }

    @Test
    fun `advertised RAM in decimal GB falls in the same tier`() {
        assertEquals(RamTier.MINIMO, ramTierFor(4_000_000_000L))
        assertEquals(RamTier.CONFORTEVOLE, ramTierFor(6_000_000_000L))
        assertEquals(RamTier.CONFORTEVOLE, ramTierFor(8_000_000_000L))
        assertEquals(RamTier.AMPIA, ramTierFor(12_000_000_000L))
    }
    @Test
    fun `inferenceThreadCountFor uses cores minus one below the tier cap`() {
        assertEquals(3, inferenceThreadCountFor(RamTier.AMPIA, availableCores = 4))
        assertEquals(1, inferenceThreadCountFor(RamTier.MINIMO, availableCores = 2))
    }

    @Test
    fun `inferenceThreadCountFor clamps to the tier cap on many-core devices`() {
        assertEquals(4, inferenceThreadCountFor(RamTier.MINIMO, availableCores = 16))
        assertEquals(4, inferenceThreadCountFor(RamTier.CONFORTEVOLE, availableCores = 16))
        assertEquals(6, inferenceThreadCountFor(RamTier.AMPIA, availableCores = 16))
    }

    @Test
    fun `inferenceThreadCountFor never goes below one core`() {
        assertEquals(1, inferenceThreadCountFor(RamTier.AMPIA, availableCores = 1))
        assertEquals(1, inferenceThreadCountFor(RamTier.INSUFFICIENTE, availableCores = 1))
    }
}
