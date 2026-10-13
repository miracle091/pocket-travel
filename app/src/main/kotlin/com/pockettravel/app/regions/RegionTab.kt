package com.pockettravel.app.regions

import androidx.annotation.StringRes
import com.pockettravel.app.R

/** Schede della pagina della regione; [key] e' il valore di `RegionHubRoute.tab`. */
internal enum class RegionTab(val key: String, @StringRes val label: Int) {
    GUIDE("guide", R.string.nav_guide),
    MAP("map", R.string.nav_map),
    NAVIGATION("navigation", R.string.nav_navigation),
    AI("ai", R.string.nav_assistant),
    ;

    companion object {
        fun fromKey(key: String): RegionTab = entries.firstOrNull { it.key == key } ?: GUIDE
    }
}
