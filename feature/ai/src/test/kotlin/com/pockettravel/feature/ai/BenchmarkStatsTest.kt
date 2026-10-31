package com.pockettravel.feature.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkStatsTest {

    @Test
    fun `mediana con numero dispari e pari di valori`() {
        assertEquals(3.0, median(listOf(5.0, 1.0, 3.0)), 0.0)
        assertEquals(2.5, median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
    }

    @Test
    fun `cv campionario in percentuale`() {
        // media 10, scarto campionario 1 -> 10%
        assertEquals(10.0, cvPercent(listOf(9.0, 10.0, 11.0)), 1e-9)
        assertEquals(0.0, cvPercent(listOf(7.0, 7.0, 7.0)), 0.0)
        assertEquals(0.0, cvPercent(listOf(7.0)), 0.0)
    }

    @Test
    fun `misure stabili dopo un riscaldamento e cinque ripetizioni`() = runTest {
        val calls = mutableListOf<Int>()
        val result = measureStable { 20.0.also { calls += calls.size } }
        assertEquals(1 + 5, calls.size)
        assertEquals(5, result.samples.size)
        assertEquals(20.0, result.median, 0.0)
        assertFalse(result.unstable)
    }

    @Test
    fun `il riscaldamento non entra nei campioni`() = runTest {
        val values = ArrayDeque(listOf(1.0, 10.0, 10.0, 10.0, 10.0, 10.0))
        val result = measureStable { values.removeFirst() }
        assertEquals(listOf(10.0, 10.0, 10.0, 10.0, 10.0), result.samples)
    }

    @Test
    fun `con cv sopra il 5 per cento si fanno altre cinque ripetizioni`() = runTest {
        var n = 0
        // 5 campioni 10, 11, 10, 11, 10 hanno CV circa 5,2%; con altri cinque uguali scende sotto il 10%
        val sequence = listOf(0.0, 10.0, 11.0, 10.0, 11.0, 10.0, 10.0, 11.0, 10.0, 11.0, 10.0)
        val result = measureStable { sequence[n++] }
        assertEquals(11, n)
        assertEquals(10, result.samples.size)
        assertFalse(result.unstable)
    }

    @Test
    fun `con cv sopra il 10 per cento la misura e' instabile`() = runTest {
        var n = 0
        val result = measureStable { if (n++ % 2 == 0) 10.0 else 20.0 }
        assertEquals(10, result.samples.size)
        assertTrue(result.unstable)
    }

    @Test
    fun `velocita' calata oltre il 15 per cento tra le prime e le ultime ripetizioni`() {
        assertTrue(isThrottled(listOf(60.0, 61.0, 58.0, 50.0, 49.0)))
        assertFalse(isThrottled(listOf(60.0, 61.0, 58.0, 55.0, 54.0)))
        // Una ripetizione lenta da sola non basta: le ultime due insieme.
        assertFalse(isThrottled(listOf(60.0, 61.0, 58.0, 45.0, 60.0)))
        assertFalse(isThrottled(listOf(60.0, 40.0, 30.0)))
    }

    @Test
    fun `token al secondo`() {
        assertEquals(100.0, tokensPerSecond(128, 1_280_000_000L), 1e-9)
    }

    @Test
    fun `core veloci come nel Pixel 8`() {
        val pixel8 = listOf(1_704_000L, 1_704_000L, 1_704_000L, 1_704_000L, 2_367_000L, 2_367_000L, 2_367_000L, 2_367_000L, 2_914_000L)
        assertEquals(5, fastCoreCount(pixel8, fallback = 4))
    }

    @Test
    fun `core tutti uguali o frequenze illeggibili`() {
        assertEquals(4, fastCoreCount(List(4) { 2_000_000L }, fallback = 2))
        assertEquals(3, fastCoreCount(emptyList(), fallback = 3))
        assertEquals(3, fastCoreCount(listOf(0L, 0L), fallback = 3))
    }

    @Test
    fun `json nello schema comune`() {
        val record = BenchRecord(
            source = "app", modelId = "qwen3.5-0.8b", modelFile = "m.gguf", modelSha256 = "a".repeat(64), llamaCpp = "b1-abc",
            seed = 42, device = "Google Pixel 8", threads = 5, nPrompt = 512, nGen = 128,
            pp = StableMeasure(listOf(100.0, 102.0), 101.0, 1.4, false),
            tg = StableMeasure(listOf(10.0), 10.0, 0.0, false),
            overheating = false, loadTimeMs = null, ranAt = 1L,
        )
        val json = record.toJson()
        assertEquals(1, json.getValue("schema").jsonPrimitive.int)
        assertEquals("none", json.getValue("sampler").jsonPrimitive.content)
        assertEquals("aaaaaaaaaaaa/b1-abc/Google Pixel 8/t5/pp512tg128", json.getValue("comparison_key").jsonPrimitive.content)
        assertEquals("qwen3.5-0.8b", json.getValue("model").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(101.0, json.getValue("pp").jsonObject.getValue("median_tps").jsonPrimitive.double, 0.0)
        assertEquals(2, json.getValue("pp").jsonObject.getValue("samples_tps").jsonArray.size)
        assertFalse(json.getValue("unstable").jsonPrimitive.boolean)
    }
}
