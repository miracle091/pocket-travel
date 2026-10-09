package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.llamacpp.ContextUsage
import com.pockettravel.feature.ai.llamacpp.CpuBackendInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmTaskReportTest {

    private val mb = 1024L * 1024L

    private fun snapshot(generating: Boolean, nativePssBytes: Long? = 512 * mb) = LlmTaskSnapshot(
        models = emptyList(),
        freeStorageBytes = 0L,
        totalPssBytes = 1536 * mb,
        nativePssBytes = nativePssBytes,
        nativeHeapBytes = 64 * mb,
        systemTotalRamBytes = 8192 * mb,
        systemAvailableRamBytes = 2048 * mb,
        lowMemory = false,
        cpuPercent = 312.4f,
        cpuCores = 8,
        threadCount = 41,
        runtime = LlmRuntimeStats(loadedModelId = "qwen-0.8b", isGenerating = generating),
    )

    @Test
    fun `report has header, sample and generation rows in CSV, then logcat`() {
        val generation = GenerationStats(
            modelId = "qwen-0.8b",
            context = ContextUsage(promptTokens = 900, generatedTokens = 21, usedTokens = 921, contextSize = 4096),
            timeToFirstTokenMs = 1000L,
            totalMs = 2000L,
            endedAtMs = 0L,
        )
        val report = buildTaskReport(
            header = listOf("device: test"),
            samples = listOf(TimedSnapshot(1_000L, snapshot(generating = true))),
            generations = listOf(generation),
            logcat = "I/llama: loaded\n",
        )
        val lines = report.lines()

        assertEquals("device: test", lines[1])
        assertTrue("1970-01-01T00:00:01Z,1536.0,512.0,64.0,2048.0,false,312,41,qwen-0.8b,true" in lines)
        assertTrue("1970-01-01T00:00:00Z,qwen-0.8b,900,21,921,4096,1000,2000,20.0,900.0" in lines)
        assertTrue(report.endsWith("## logcat\nI/llama: loaded\n"))
    }

    @Test
    fun `cpu backend header names the loaded and forced variants and the CPU extensions`() {
        val info = CpuBackendInfo(
            loadedFile = "libggml-cpu-android_armv8.6_1.so",
            forcedFile = "libggml-cpu-android_armv8.6_1.so",
            sve2 = true,
            sme = false,
            sveVectorBytes = 16,
        )

        assertEquals(
            "cpu_backend: loaded=android_armv8.6_1, forced=android_armv8.6_1, sve2=1, sme=0, sve_vector_bits=128, " +
                "available=android_armv8.0_1 android_armv8.6_1",
            cpuBackendHeader(info, listOf("libggml-cpu-android_armv8.0_1.so", "libggml-cpu-android_armv8.6_1.so")),
        )
    }

    @Test
    fun `cpu backend header before the native init says unknown`() {
        assertEquals("cpu_backend: not initialized, available=", cpuBackendHeader(null, emptyList()))
    }

    @Test
    fun `cpu backend header shows auto when no variant is forced`() {
        val info = CpuBackendInfo("libggml-cpu-x64.so", null, sve2 = false, sme = false, sveVectorBytes = 0)

        assertEquals(
            "cpu_backend: loaded=x64, forced=auto, sve2=0, sme=0, sve_vector_bits=0, available=x64",
            cpuBackendHeader(info, listOf("libggml-cpu-x64.so")),
        )
    }

    @Test
    fun `missing values stay empty in the CSV`() {
        val generation = GenerationStats(null, null, null, 50L)
        val report = buildTaskReport(emptyList(), emptyList(), listOf(generation), "")

        assertTrue("1970-01-01T00:00:00Z,,,,,,,50,," in report.lines())
    }

    @Test
    fun `native PSS not reported by the system stays empty`() {
        val sample = TimedSnapshot(0L, snapshot(generating = false, nativePssBytes = null))
        val report = buildTaskReport(emptyList(), listOf(sample), emptyList(), "")

        assertTrue("1970-01-01T00:00:00Z,1536.0,,64.0,2048.0,false,312,41,qwen-0.8b,false" in report.lines())
    }
}
