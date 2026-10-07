package com.pockettravel.feature.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.feature.map.di.RouteEngineModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifica su device/emulatore reale (Pixel 8 AVD) che il wiring di
 * produzione — copia del profilo dagli asset dell'app, costruzione di RoutingContext/
 * RoutingEngine tramite RouteEngineModule — funzioni su Android ART reale: il risultato atteso e'
 * pulito, nessuna eccezione.
 *
 * Non verifica un percorso reale: servirebbe un segmento .rd5 vero (12 MB), non incluso nel repo;
 * il calcolo con segmenti veri e' in RouteEngineBenchmarkDeviceTest.
 */
@RunWith(AndroidJUnit4::class)
class RouteEngineFactoryDeviceTest {

    @Test
    fun ilWiringDiProduzioneFunzionaSuDeviceRealeSenzaSegmentiDisponibili() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val regionsDir = File(context.cacheDir, "route-engine-factory-test/regions")
        regionsDir.deleteRecursively()
        regionsDir.mkdirs()
        val regionId = "test-region"
        File(regionsDir, "$regionId/routing").mkdirs()

        val factory = RouteEngineModule.provideRouteEngineFactory(
            regionsDir,
            context,
            UsageModePreferences(context, MapFilterPreferences(context), RoutingVariantPreferences(context)),
        )
        val result = runBlocking {
            val engine = factory.create(listOf(regionId))
            engine.route(
                from = RoutePoint(45.4642, 9.1900),
                to = RoutePoint(45.4658, 9.1920),
            )
        }

        assertEquals("senza file .rd5 mancano i segmenti della rete stradale", RouteResult.NoRoutingData, result)
    }
}
