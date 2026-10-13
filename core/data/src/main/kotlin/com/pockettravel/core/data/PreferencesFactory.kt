package com.pockettravel.core.data

import android.content.SharedPreferences

/**
 * Apre un file di preferenze per nome. Le classi che salvano impostazioni lo ricevono al posto del `Context`: nell'app
 * apre le SharedPreferences del sistema, nei test una versione in memoria.
 */
fun interface PreferencesFactory {
    fun open(name: String): SharedPreferences
}
