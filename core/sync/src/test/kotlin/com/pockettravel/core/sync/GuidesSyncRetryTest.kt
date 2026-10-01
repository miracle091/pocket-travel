package com.pockettravel.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidesSyncRetryTest {
    @Test
    fun `un errore di rete si riprova al massimo cinque volte`() {
        assertTrue(shouldRetryGuidesSync(0))
        assertTrue(shouldRetryGuidesSync(4))
        assertFalse(shouldRetryGuidesSync(5))
        assertFalse(shouldRetryGuidesSync(12))
    }
}
