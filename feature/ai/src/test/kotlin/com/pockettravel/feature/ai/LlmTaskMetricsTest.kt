package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class LlmTaskMetricsTest {

    @Test
    fun `cpu percent is relative to one core`() {
        assertEquals(50f, cpuPercent(cpuDeltaMs = 500, wallDeltaMs = 1000), 0.01f)
        assertEquals(300f, cpuPercent(cpuDeltaMs = 3000, wallDeltaMs = 1000), 0.01f)
    }

    @Test
    fun `cpu percent is zero on an empty or inverted interval`() {
        assertEquals(0f, cpuPercent(100, 0), 0f)
        assertEquals(0f, cpuPercent(-50, 1000), 0f)
    }

    @Test
    fun `formats bytes with binary units`() {
        assertEquals("0 B", formatBytes(0, Locale.US))
        assertEquals("512 B", formatBytes(512, Locale.US))
        assertEquals("1.5 KB", formatBytes(1536, Locale.US))
        assertEquals("533.0 MB", formatBytes(558_891_008L, Locale.US))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024, Locale.US))
        assertEquals("1,5 KB", formatBytes(1536, Locale.ITALY))
        assertEquals("0 B", formatBytes(-5, Locale.US))
    }

    @Test
    fun `parses the thread count from proc status`() {
        val lines = sequenceOf("Name:\tcom.pockettravel", "Threads:\t42", "SigQ:\t0/1")
        assertEquals(42, parseThreadCount(lines))
        assertNull(parseThreadCount(sequenceOf("Name:\tx")))
    }

    @Test
    fun `tokens per second excludes the time to first token`() {
        // 11 frammenti: 10 dopo il primo in 2 s.
        assertEquals(5f, tokensPerSecond(pieces = 11, timeToFirstTokenMs = 1000, totalMs = 3000)!!, 0.01f)
    }

    @Test
    fun `tokens per second is unknown without enough data`() {
        assertNull(tokensPerSecond(pieces = 1, timeToFirstTokenMs = 100, totalMs = 500))
        assertNull(tokensPerSecond(pieces = 5, timeToFirstTokenMs = null, totalMs = 500))
        assertNull(tokensPerSecond(pieces = 5, timeToFirstTokenMs = 500, totalMs = 500))
    }
}
