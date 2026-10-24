package com.pockettravel.feature.ai

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Liste di parole nella scelta del contesto (CategoryWords.kt): accese per tutti.
 * Nelle build di debug il task manager le spegne, per confrontare la ricerca con e senza; in release non si leggono.
 */
@Singleton
class WordListsOverride(
    preferencesFactory: PreferencesFactory,
    private val isDebuggable: Boolean,
) {

    @Inject
    constructor(
        preferencesFactory: PreferencesFactory,
        @ApplicationContext context: Context,
    ) : this(preferencesFactory, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)

    private val prefs = preferencesFactory.open(PREFS)

    /** False solo se spente dal task manager di una build di debug. */
    fun enabled(): Boolean = !isDebuggable || prefs.getBoolean(KEY, true)

    fun save(enabled: Boolean) {
        prefs.edit { putBoolean(KEY, enabled) }
    }

    private companion object {
        const val PREFS = "ai_debug"
        const val KEY = "word_lists"
    }
}
