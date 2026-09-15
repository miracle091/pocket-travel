package com.pockettravel.app.regions

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Ricorda l'ultima regione aperta, per far puntare le voci globali Mappa/IA del drawer a un contesto valido. */
@Singleton
class RecentRegionPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("recent_region", Context.MODE_PRIVATE)

    fun lastRegionId(): String? = prefs.getString(KEY_REGION_ID, null)

    fun setLastRegionId(regionId: String) = prefs.edit { putString(KEY_REGION_ID, regionId) }

    private companion object {
        const val KEY_REGION_ID = "region_id"
    }
}
