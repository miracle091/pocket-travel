package com.pockettravel.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualHintsTest {

    private val prefs = MapSharedPreferences()

    @Test
    fun `un suggerimento mai mostrato non e' visto`() {
        assertFalse(ContextualHints.isSeen(prefs, "map_filters"))
    }

    @Test
    fun `dopo markSeen il suggerimento e' visto, gli altri no`() {
        ContextualHints.markSeen(prefs, "map_filters")

        assertTrue(ContextualHints.isSeen(prefs, "map_filters"))
        assertFalse(ContextualHints.isSeen(prefs, "other"))
    }

    @Test
    fun `il flag e' un booleano salvato con l'id come chiave`() {
        ContextualHints.markSeen(prefs, "map_filters")

        assertEquals(mapOf<String, Any?>("map_filters" to true), prefs.values)
    }

    @Test
    fun `reset rimostra tutti i suggerimenti`() {
        ContextualHints.markSeen(prefs, "a")
        ContextualHints.markSeen(prefs, "b")

        ContextualHints.reset(prefs)

        assertFalse(ContextualHints.isSeen(prefs, "a"))
        assertFalse(ContextualHints.isSeen(prefs, "b"))
        assertTrue(prefs.values.isEmpty())
    }
}
