package com.pockettravel.feature.map

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedPlaceTest {

    private val place = NavigationPlace("Colosseo", 41.8902, 12.4922, "italia")

    @Test
    fun `una meta salvata come testo si ripristina`() {
        val handle = SavedStateHandle(mapOf("to" to place.toSavedState()))

        assertEquals(place, handle.savedPlace("to"))
    }

    // Le versioni precedenti salvavano un Bundle: un valore di un altro tipo non fa fallire il ripristino.
    @Test
    fun `un valore salvato di un altro tipo si ignora`() {
        val handle = SavedStateHandle(mapOf("to" to listOf("Colosseo", 41.8902)))

        assertNull(handle.savedPlace("to"))
    }

    @Test
    fun `senza valore salvato non c'e' meta`() {
        assertNull(SavedStateHandle().savedPlace("to"))
    }
}
