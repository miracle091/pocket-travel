package com.pockettravel.feature.ai

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordListsOverrideTest {

    private val files = InMemoryPreferencesFactory()

    @Test
    fun `le liste di parole sono accese finche' nessuno le spegne`() {
        assertTrue(WordListsOverride(files, isDebuggable = true).enabled())
        assertTrue(WordListsOverride(files, isDebuggable = false).enabled())
    }

    @Test
    fun `in debug lo spegnimento resta per la prossima apertura`() {
        WordListsOverride(files, isDebuggable = true).save(false)

        assertFalse(WordListsOverride(files, isDebuggable = true).enabled())
        assertFalse(files.open("ai_debug").getBoolean("word_lists", true))
    }

    @Test
    fun `in release la scelta salvata non si legge`() {
        files.open("ai_debug").edit().putBoolean("word_lists", false).apply()

        assertTrue(WordListsOverride(files, isDebuggable = false).enabled())
    }

    @Test
    fun `riaccendere le liste le rimette in uso`() {
        val override = WordListsOverride(files, isDebuggable = true)
        override.save(false)
        override.save(true)

        assertTrue(override.enabled())
    }
}
