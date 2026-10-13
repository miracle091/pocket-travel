package com.pockettravel.feature.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Aeroporti e compagnie letti dagli asset dell'APK vero: il plugin Android decomprime gli asset `.gz` e toglie
 * l'estensione, quindi il caricamento deve funzionare con il nome che hanno nell'APK.
 */
@RunWith(AndroidJUnit4::class)
class TravelDataLoaderDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun caricaAeroportiECompagnieDagliAssetDellApk() = runBlocking {
        val data = TravelDataLoader.load(context)

        assertEquals("ORD", data.airports.search("ohare").first().code)
        assertTrue(data.airlines.search("alitalia").isNotEmpty() || data.airlines.search("ita").isNotEmpty())
    }
}
