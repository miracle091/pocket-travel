package com.pockettravel.app.navigation

import com.pockettravel.app.regions.RegionTab
import com.pockettravel.core.sync.AlertNotifier
import com.pockettravel.core.sync.UpdateAvailableNotifier

/** Dove porta un valore di `EXTRA_OPEN_SCREEN`: [route], aperta sopra la destinazione corrente (le pagine delle regioni sopra Nazioni) o, se [topLevel], come una voce della barra. */
internal data class OpenScreenTarget(val route: Any, val topLevel: Boolean = false)

private val REGION_ID = Regex("[a-z0-9-]{1,80}")

/** Valori di `EXTRA_OPEN_SCREEN` dei widget, oltre a quelli delle notifiche. */
internal object WidgetScreens {
    const val NAVIGATOR = "navigator"
    const val DOCUMENTS = "documents"

    /** La scheda [tab] della pagina della regione [regionId] (`region:<id>:<scheda>`). */
    fun regionTab(regionId: String, tab: RegionTab) = "${AlertNotifier.OPEN_REGION_PREFIX}$regionId:${tab.key}"
}

/**
 * La destinazione di un valore di `EXTRA_OPEN_SCREEN`, o null se non e' uno dei valori noti: l'activity e' esportata e un'altra
 * app puo' passare un extra qualsiasi. Valori: "updates", "region:<id>" (Guida), "region:<id>:<scheda>", "navigator", "documents".
 */
internal fun openScreenTarget(screen: String): OpenScreenTarget? = when {
    screen == UpdateAvailableNotifier.OPEN_UPDATES -> OpenScreenTarget(UpdatesRoute)
    screen == WidgetScreens.NAVIGATOR -> OpenScreenTarget(NavigatorRoute, topLevel = true)
    screen == WidgetScreens.DOCUMENTS -> OpenScreenTarget(VaultRoute, topLevel = true)
    screen.startsWith(AlertNotifier.OPEN_REGION_PREFIX) -> {
        val parts = screen.removePrefix(AlertNotifier.OPEN_REGION_PREFIX).split(':')
        val regionId = parts[0]
        val tab = parts.getOrNull(1)
        when {
            !REGION_ID.matches(regionId) || parts.size > 2 -> null
            tab == null -> OpenScreenTarget(RegionHubRoute(regionId))
            RegionTab.entries.any { it.key == tab } -> OpenScreenTarget(RegionHubRoute(regionId, tab))
            else -> null
        }
    }
    else -> null
}
