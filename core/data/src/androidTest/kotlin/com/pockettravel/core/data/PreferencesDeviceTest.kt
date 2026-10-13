package com.pockettravel.core.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le preferenze di core/data con SharedPreferences vere: ogni scelta sopravvive a una nuova istanza (il riavvio
 * dell'app), e i valori scritti male o da una versione diversa non fanno fallire la lettura.
 */
@RunWith(AndroidJUnit4::class)
class PreferencesDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val factory = PreferencesFactory { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
    private val files = listOf("routing_variant", "region_zone", "map_detail", "world_map", "profile")

    private fun clear() = files.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }

    private fun raw(file: String) = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    @Before
    fun setUp() = clear()

    @After
    fun tearDown() = clear()

    // RoutingVariantPreferences

    @Test
    fun senzaScelteLaReteStradaleSegueIlDefaultTutti() {
        val choices = RoutingVariantPreferences(factory).choices.value
        assertEquals(RoutingVariantChoice.ALL, choices.default)
        assertEquals(RoutingVariantChoice.ALL, choices.choiceFor("italia"))
        assertTrue(RoutingVariantPreferences(factory).installedCarOnly.value.isEmpty())
    }

    @Test
    fun laSceltaSoloAutoEsplicitaSopravvivealRiavvio() {
        RoutingVariantPreferences(factory).setCarOnly("italia", true)

        val reloaded = RoutingVariantPreferences(factory).choices.value
        assertEquals(RoutingVariantChoice.CAR, reloaded.choiceFor("italia"))
        assertTrue("italia" in reloaded.explicit)
    }

    @Test
    fun tornareATuttiRestaUnaSceltaEsplicitaCheVinceSulDefault() {
        val prefs = RoutingVariantPreferences(factory)
        prefs.setDefaultChoice(RoutingVariantChoice.CAR)
        prefs.setCarOnly("italia", true)
        prefs.setCarOnly("italia", false)

        val reloaded = RoutingVariantPreferences(factory).choices.value
        assertEquals(RoutingVariantChoice.ALL, reloaded.choiceFor("italia"))
        assertEquals(RoutingVariantChoice.CAR, reloaded.choiceFor("francia"))
        assertEquals(RoutingVariantChoice.CAR, reloaded.default)
    }

    @Test
    fun biciEPiediNonRendonoEsplicitaLaScelta() {
        RoutingVariantPreferences(factory).setBikeFoot("italia", true)

        val reloaded = RoutingVariantPreferences(factory).choices.value
        assertTrue("italia" in reloaded.bikeFoot)
        assertFalse("italia" in reloaded.explicit)
        assertEquals(RoutingVariantChoice.ALL, reloaded.choiceFor("italia"))
    }

    @Test
    fun ilDefaultSconosciutoRipieganaSuTutti() {
        raw("routing_variant").edit().putString("default_choice", "VOLARE").commit()
        assertEquals(RoutingVariantChoice.ALL, RoutingVariantPreferences(factory).choices.value.default)
    }

    @Test
    fun laReteInstallataSoloAutoSiAggiungeESiToglieESiRicorda() {
        val prefs = RoutingVariantPreferences(factory)
        prefs.setInstalledCarOnly("italia", true)
        prefs.setInstalledCarOnly("francia", true)
        prefs.setInstalledCarOnly("francia", false)

        assertEquals(setOf("italia"), RoutingVariantPreferences(factory).installedCarOnly.value)
        // La rete installata non e' una scelta dell'utente.
        assertTrue(RoutingVariantPreferences(factory).choices.value.explicit.isEmpty())
    }

    // RegionZonePreferences

    @Test
    fun laZonaSiSalvaESiRilegge() {
        val zone = RegionZone(-122.5, 37.2, -121.5, 38.1)
        RegionZonePreferences(factory).setZone("stati-uniti", zone)

        assertEquals(zone, RegionZonePreferences(factory).zone("stati-uniti"))
        assertNull(RegionZonePreferences(factory).zone("francia"))
    }

    @Test
    fun nullTornaATuttaLaRegioneEUnaZonaSostituisceLaPrecedente() {
        val prefs = RegionZonePreferences(factory)
        prefs.setZone("italia", RegionZone(10.0, 42.0, 12.0, 44.0))
        prefs.setZone("italia", RegionZone(7.0, 44.0, 8.0, 45.0))
        assertEquals(RegionZone(7.0, 44.0, 8.0, 45.0), RegionZonePreferences(factory).zone("italia"))

        prefs.setZone("italia", null)
        assertNull(prefs.zone("italia"))
        assertTrue(RegionZonePreferences(factory).zones.value.isEmpty())
    }

    @Test
    fun leZoneScrittePeggioSiIgnoranoSenzaFarFallireLaLettura() {
        raw("region_zone").edit()
            .putString("rotta", "non,una,zona")
            .putString("invertita", "5,5,1,1")
            .putInt("numero", 3)
            .putString("buona", "10.0,42.0,12.0,44.0")
            .commit()

        val zones = RegionZonePreferences(factory).zones.value
        assertEquals(setOf("buona"), zones.keys)
    }

    // MapDetailPreferences

    @Test
    fun senzaSceltaLaMappaNuovaNonHaUnaPreferenza() {
        val prefs = MapDetailPreferences(factory)
        assertNull(prefs.choice("italia"))
        assertTrue(prefs.installedLight.value.isEmpty())
    }

    @Test
    fun laSceltaLeggeraODettagliataSopravvive() {
        val prefs = MapDetailPreferences(factory)
        prefs.setChoice("italia", true)
        prefs.setChoice("francia", false)

        val reloaded = MapDetailPreferences(factory)
        assertEquals(true, reloaded.choice("italia"))
        assertEquals(false, reloaded.choice("francia"))
        assertNull(reloaded.choice("spagna"))
    }

    @Test
    fun soloLeMappeInstallateLeggereContano() {
        val prefs = MapDetailPreferences(factory)
        prefs.setInstalledLight("italia", true)
        prefs.setInstalledLight("francia", true)
        prefs.setInstalledLight("francia", false)

        assertEquals(setOf("italia"), MapDetailPreferences(factory).installedLight.value)
        // Una scelta dell'utente non diventa una mappa installata.
        assertTrue(MapDetailPreferences(factory).choices.value.isEmpty())
    }

    // WorldMapStore

    @Test
    fun ilMondoOnlineHaUnoZoomDiDefaultEUnUrlAssente() {
        val store = WorldMapStore(factory)
        assertNull(store.worldMapUrl())
        assertEquals(8, store.worldMapMaxZoom())
    }

    @Test
    fun ilMondoOnlineSiSalvaESiAzzeraSenzaPerdereLoZoom() {
        WorldMapStore(factory).save("https://github.com/o/r/world.pmtiles", 6)
        assertEquals("https://github.com/o/r/world.pmtiles", WorldMapStore(factory).worldMapUrl())
        assertEquals(6, WorldMapStore(factory).worldMapMaxZoom())

        WorldMapStore(factory).save(null, 7)
        assertNull(WorldMapStore(factory).worldMapUrl())
        assertEquals(7, WorldMapStore(factory).worldMapMaxZoom())
    }

    // NationalityPreferences

    @Test
    fun laNazionalitaSceltaSopravvive() {
        NationalityPreferences(factory).setNationality("JP")
        assertEquals("JP", NationalityPreferences(factory).nationality.value)
    }

    @Test
    fun senzaSceltaLaNazionalitaEQuellaDelTelefonoOAssente() {
        val value = NationalityPreferences(factory).nationality.value
        assertTrue(value == null || value.length == 2)
    }
}
