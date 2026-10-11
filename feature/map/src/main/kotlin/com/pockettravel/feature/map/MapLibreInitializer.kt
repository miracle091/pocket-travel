package com.pockettravel.feature.map

import android.content.Context
import android.util.Log
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

// Cache ambient del motore nativo (mappa mondiale online z0-8, stile, glifi): il limite predefinito e' di 50 MB.
private const val AMBIENT_CACHE_MAX_BYTES = 20L * 1024 * 1024
private const val TAG = "MapLibreInitializer"

object MapLibreInitializer {
    @Volatile private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            MapLibre.getInstance(appContext)
            limitAmbientCache(appContext)
            initialized = true
        }
    }

    // Asincrono, errori solo nel log: la mappa funziona anche con la cache di dimensione predefinita.
    private fun limitAmbientCache(context: Context) {
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(
            AMBIENT_CACHE_MAX_BYTES,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit

                override fun onError(message: String) {
                    Log.w(TAG, "Limite della cache ambient non impostato: $message")
                }
            },
        )
    }
}
