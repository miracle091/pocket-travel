package com.pockettravel.app.regions

import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import javax.inject.Inject
import javax.inject.Singleton

/** Ricorda l'ultima regione aperta, per riaprirne la mappa all'avvio (vedi StartDestinationViewModel). */
@Singleton
class RecentRegionStore @Inject constructor(preferences: PreferencesFactory) {

    private val prefs = preferences.open("recent_region")

    fun lastRegionId(): String? = prefs.getString(KEY_REGION_ID, null)

    fun setLastRegionId(regionId: String) = prefs.edit { putString(KEY_REGION_ID, regionId) }

    private companion object {
        const val KEY_REGION_ID = "region_id"
    }
}
