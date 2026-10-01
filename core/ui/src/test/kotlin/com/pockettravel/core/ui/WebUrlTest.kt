package com.pockettravel.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUrlTest {
    @Test
    fun accettaHttpEHttpsConHost() {
        assertTrue(isSafeWebUrl("https://www.esempio.it/pagina?x=1"))
        assertTrue(isSafeWebUrl("http://esempio.it"))
        assertTrue(isSafeWebUrl("HTTPS://ESEMPIO.IT"))
    }

    @Test
    fun scartaGliAltriSchemi() {
        listOf(
            "javascript:alert(1)",
            "file:///sdcard/x",
            "content://com.esempio/x",
            "intent://scan/#Intent;scheme=zxing;end",
            "market://details?id=com.esempio",
            "tel:+39123",
            "ftp://esempio.it",
        ).forEach { assertFalse(it, isSafeWebUrl(it)) }
    }

    @Test
    fun scartaVuotiMalformatiESenzaHost() {
        listOf("", "   ", "https://", "https:///x", "//esempio.it", "https://esem pio.it").forEach {
            assertFalse(it, isSafeWebUrl(it))
        }
    }

    @Test
    fun senzaSchemaSiAssumeHttps() {
        assertEquals("https://www.esempio.it", safeWebUrl("www.esempio.it"))
        assertEquals("https://www.esempio.it", safeWebUrl("  www.esempio.it "))
    }

    @Test
    fun safeWebUrlMantieneHttpEScartaIlResto() {
        assertEquals("http://esempio.it", safeWebUrl("http://esempio.it"))
        assertNull(safeWebUrl("javascript:alert(1)"))
        assertNull(safeWebUrl("file:///etc/hosts"))
        assertNull(safeWebUrl("intent://x#Intent;end"))
        assertNull(safeWebUrl(""))
    }
}
