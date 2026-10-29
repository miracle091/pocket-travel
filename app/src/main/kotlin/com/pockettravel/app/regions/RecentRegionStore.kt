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

    /** Azzera l'ultima regione se e' [regionId] (eliminata): all'avvio successivo non si riapre una regione che non c'e' piu'. */
    fun clearIfLast(regionId: String) {
        if (lastRegionId() == regionId) prefs.edit { remove(KEY_REGION_ID) }
    }

    private companion object {
        const val KEY_REGION_ID = "region_id"
    }
}
