package com.pockettravel.app.navigation

import com.pockettravel.app.regions.RegionTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenScreenTargetTest {

    @Test
    fun `region senza scheda apre la guida della regione`() {
        assertEquals(OpenScreenTarget(RegionHubRoute("toscana")), openScreenTarget("region:toscana"))
    }

    @Test
    fun `una scheda della regione arriva alla rotta`() {
        assertEquals(OpenScreenTarget(RegionHubRoute("toscana", "ai")), openScreenTarget(WidgetScreens.regionTab("toscana", RegionTab.AI)))
        assertEquals(
            OpenScreenTarget(RegionHubRoute("toscana", "navigation")),
            openScreenTarget(WidgetScreens.regionTab("toscana", RegionTab.NAVIGATION)),
        )
    }

    @Test
    fun `Navigatore e Documenti sono voci della barra`() {
        assertEquals(OpenScreenTarget(NavigatorRoute, topLevel = true), openScreenTarget("navigator"))
        assertEquals(OpenScreenTarget(VaultRoute, topLevel = true), openScreenTarget("documents"))
    }

    @Test
    fun `gli aggiornamenti della notifica restano`() {
        assertEquals(OpenScreenTarget(UpdatesRoute), openScreenTarget("updates"))
    }

    @Test
    fun `Chiedi all'assistente senza motore apre le Impostazioni`() {
        assertEquals(OpenScreenTarget(SettingsRoute), openScreenTarget("settings"))
    }

    @Test
    fun `un valore sconosciuto o malformato non apre nulla`() {
        listOf("", "impostazioni", "region:", "region:../etc", "region:Toscana", "region:toscana:segreto", "region:toscana:ai:x", "region:" + "a".repeat(81))
            .forEach { assertNull(it, openScreenTarget(it)) }
    }
}
