package com.pockettravel.feature.ai

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.feature.ai.llamacpp.CpuBackendOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuBackendOverrideTest {

    private val files = InMemoryPreferencesFactory()
    private val prefs = files.open(CpuBackendOverride.PREFS)

    @Test
    fun `senza una scelta la variante la sceglie ggml`() {
        assertNull(CpuBackendOverride.saved(prefs, debuggable = true))
    }

    @Test
    fun `la variante salvata si rilegge in una build di debug`() {
        CpuBackendOverride.save(prefs, "libggml-cpu-armv8.2_1.so")

        assertEquals("libggml-cpu-armv8.2_1.so", CpuBackendOverride.saved(prefs, debuggable = true))
    }

    @Test
    fun `in una build di release la scelta non si legge mai`() {
        CpuBackendOverride.save(prefs, "libggml-cpu-armv8.2_1.so")

        assertNull(CpuBackendOverride.saved(prefs, debuggable = false))
    }

    @Test
    fun `salvare null torna alla scelta di ggml`() {
        CpuBackendOverride.save(prefs, "libggml-cpu-armv8.2_1.so")

        CpuBackendOverride.save(prefs, null)

        assertNull(CpuBackendOverride.saved(prefs, debuggable = true))
    }

    @Test
    fun `il file e' ai_debug e la chiave resta quella dell'app installata`() {
        assertEquals("ai_debug", CpuBackendOverride.PREFS)

        CpuBackendOverride.save(files.open("ai_debug"), "libggml-cpu-x.so")

        assertEquals("libggml-cpu-x.so", files.open("ai_debug").getString("forced_cpu_backend", null))
    }

    @Test
    fun `la scelta della variante non tocca le liste di parole dello stesso file`() {
        WordListsOverride(files, isDebuggable = true).save(false)

        CpuBackendOverride.save(prefs, "libggml-cpu-x.so")
        CpuBackendOverride.save(prefs, null)

        assertEquals(false, WordListsOverride(files, isDebuggable = true).enabled())
    }

    @Test
    fun `le varianti sono i file libggml-cpu, in ordine alfabetico`() {
        val names = arrayOf("libllama.so", "libggml-cpu-b.so", "libggml-cpu.so", "libggml-base.so", "libggml-cpu-a.so", "libggml-cpu.txt")

        assertEquals(listOf("libggml-cpu-a.so", "libggml-cpu-b.so", "libggml-cpu.so"), CpuBackendOverride.variants(names))
    }

    @Test
    fun `senza cartella delle librerie non ci sono varianti`() {
        assertEquals(emptyList<String>(), CpuBackendOverride.variants(null))
    }
}
