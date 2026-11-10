package com.pockettravel.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoLoaderTest {

    @Test
    fun `una foto piu' piccola del bersaglio non si riduce`() {
        assertEquals(1, sampleSizeFor(width = 100, height = 50, targetPx = 72))
    }

    @Test
    fun `la riduzione si ferma prima che il lato corto scenda sotto il bersaglio`() {
        // 3000 / 32 = 93 >= 72, 3000 / 64 = 46 < 72.
        assertEquals(32, sampleSizeFor(width = 4000, height = 3000, targetPx = 72))
        assertEquals(32, sampleSizeFor(width = 3000, height = 4000, targetPx = 72))
    }

    @Test
    fun `il lato corto esattamente doppio del bersaglio dimezza`() {
        assertEquals(2, sampleSizeFor(width = 2160, height = 2160, targetPx = 1080))
    }
}
