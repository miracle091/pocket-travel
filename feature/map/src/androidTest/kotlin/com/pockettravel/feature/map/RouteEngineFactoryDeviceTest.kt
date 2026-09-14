package com.pockettravel.feature.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.feature.map.di.RouteEngineModule
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica su device/emulatore reale (Pixel 8 AVD) che il wiring di
 * produzione — copia del profilo dagli asset dell'app, costruzione di RoutingContext/
 * RoutingEngine tramite RouteEngineModule — funzioni su Android ART reale. Stesso identico
 * livello a cui GraphHopper falliva prima (NoSuchMethodError, vedi Fase 9): qui il risultato
 * atteso è pulito, nessuna eccezione.
 *
 * Non verifica un percorso reale (richiederebbe un vero segmento .rd5, non bundlato qui per non
 * appesantire il repo con un file di 12 MB — verifica manuale fatta
 * con un segmento scaricato da brouter.de).
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

        val factory = RouteEngineModule.provideRouteEngineFactory(regionsDir, context)
        val engine = factory.create(regionId)

        val route = engine.route(
            from = RoutePoint(45.4642, 9.1900),
            to = RoutePoint(45.4658, 9.1920),
        )

        assertNull("senza segmenti non deve essere trovato nessun percorso", route)
    }
}
