package com.pockettravel.feature.ai

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Liste di parole per categoria nella ricerca dell'assistente (CategoryWords.kt): accese per tutti (utente, 2026-10-11).
 * Nelle build di debug il task manager le spegne, per confrontare la ricerca con e senza; in release non si leggono.
 */
object WordListsOverride {
    private const val PREFS = "ai_debug"
    private const val KEY = "word_lists"

    /** False solo se spente dal task manager di una build di debug. */
    fun enabled(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || prefs(context).getBoolean(KEY, true)

    fun save(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
