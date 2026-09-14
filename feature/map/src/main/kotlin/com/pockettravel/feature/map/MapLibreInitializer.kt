package com.pockettravel.feature.map

import android.content.Context
import org.maplibre.android.MapLibre

object MapLibreInitializer {
    @Volatile private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            MapLibre.getInstance(context.applicationContext)
            initialized = true
        }
    }
}
