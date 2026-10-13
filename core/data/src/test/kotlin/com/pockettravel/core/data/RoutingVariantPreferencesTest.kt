package com.pockettravel.core.data

import androidx.core.content.edit
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingVariantPreferencesTest {

    @Test
    fun `senza scelte vale Tutti`() {
        val preferences = RoutingVariantPreferences(InMemoryPreferencesFactory())

        assertEquals(RoutingVariantChoices(), preferences.choices.value)
        assertTrue(preferences.installedCarOnly.value.isEmpty())
    }

    @Test
    fun `solo auto e' una scelta esplicita e resta per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        RoutingVariantPreferences(files).setCarOnly("italia", true)

        val choices = RoutingVariantPreferences(files).choices.value

        assertEquals(setOf("italia"), choices.carOnly)
        assertEquals(setOf("italia"), choices.explicit)
        assertTrue(choices.isCarOnly("italia"))
    }

    @Test
    fun `togliere solo auto lascia la scelta esplicita Tutti`() {
        val files = InMemoryPreferencesFactory()
        val preferences = RoutingVariantPreferences(files)
        preferences.setCarOnly("italia", true)
        preferences.setCarOnly("italia", false)

        val choices = RoutingVariantPreferences(files).choices.value

        assertFalse(choices.isCarOnly("italia"))
        assertEquals(setOf("italia"), choices.explicit)
        assertEquals(RoutingVariantChoice.ALL, choices.choiceFor("italia"))
    }

    @Test
    fun `Bici e piedi si salva a parte`() {
        val files = InMemoryPreferencesFactory()
        val preferences = RoutingVariantPreferences(files)
        preferences.setCarOnly("italia", false)
        preferences.setBikeFoot("italia", true)

        assertEquals(RoutingVariantChoice.BIKE_FOOT, RoutingVariantPreferences(files).choices.value.choiceFor("italia"))
    }

    @Test
    fun `la scelta di base vale per le regioni senza una esplicita`() {
        val files = InMemoryPreferencesFactory()
        val preferences = RoutingVariantPreferences(files)
        preferences.setDefaultChoice(RoutingVariantChoice.CAR)
        preferences.setCarOnly("francia", false)

        val choices = RoutingVariantPreferences(files).choices.value

        assertEquals(RoutingVariantChoice.CAR, choices.choiceFor("italia"))
        assertEquals(RoutingVariantChoice.ALL, choices.choiceFor("francia"))
    }

    @Test
    fun `una scelta di base sconosciuta torna a Tutti`() {
        val files = InMemoryPreferencesFactory()
        files.open("routing_variant").edit { putString("default_choice", "TRENO") }

        assertEquals(RoutingVariantChoice.ALL, RoutingVariantPreferences(files).choices.value.default)
    }

    @Test
    fun `la rete installata solo auto si aggiunge e si toglie`() {
        val files = InMemoryPreferencesFactory()
        val preferences = RoutingVariantPreferences(files)
        preferences.setInstalledCarOnly("italia", true)
        preferences.setInstalledCarOnly("francia", true)
        preferences.setInstalledCarOnly("francia", false)

        assertEquals(setOf("italia"), preferences.installedCarOnly.value)
        assertEquals(setOf("italia"), RoutingVariantPreferences(files).installedCarOnly.value)
        assertTrue(RoutingVariantPreferences(files).choices.value.explicit.isEmpty())
    }

    @Test
    fun `legge il file routing_variant salvato dall'app`() {
        val files = InMemoryPreferencesFactory()
        files.open("routing_variant").edit {
            putBoolean("choice:italia", true)
            putBoolean("choice:francia", false)
            putBoolean("bikefoot:spagna", true)
            putBoolean("installed:italia", true)
            putString("default_choice", "BIKE_FOOT")
        }

        val preferences = RoutingVariantPreferences(files)
        val choices = preferences.choices.value

        assertEquals(setOf("italia"), choices.carOnly)
        assertEquals(setOf("spagna"), choices.bikeFoot)
        assertEquals(setOf("italia", "francia"), choices.explicit)
        assertEquals(RoutingVariantChoice.BIKE_FOOT, choices.default)
        assertEquals(setOf("italia"), preferences.installedCarOnly.value)
    }
}
