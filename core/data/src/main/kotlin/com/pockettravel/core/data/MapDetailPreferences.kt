package com.pockettravel.core.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mappa leggera (senza la z14) per regione: [choices] la scelta dell'utente (true leggera, false dettagliata; assente =
 * automatica, leggera solo se la mappa completa e' molto pesante), [installedLight] le regioni la cui mappa installata
 * e' leggera, scritto dopo ogni estrazione: con la scelta automatica l'interruttore mostra com'e' andata.
 */
@Singleton
class MapDetailPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("map_detail", Context.MODE_PRIVATE)
    private val _choices = MutableStateFlow(read(CHOICE_PREFIX).mapValues { it.value as Boolean })
    private val _installedLight = MutableStateFlow(read(INSTALLED_PREFIX).filterValues { it == true }.keys)
    val choices: StateFlow<Map<String, Boolean>> = _choices.asStateFlow()
    val installedLight: StateFlow<Set<String>> = _installedLight.asStateFlow()

    fun choice(regionId: String): Boolean? = _choices.value[regionId]

    fun setChoice(regionId: String, light: Boolean) {
        prefs.edit { putBoolean(CHOICE_PREFIX + regionId, light) }
        _choices.value = _choices.value + (regionId to light)
    }

    fun setInstalledLight(regionId: String, light: Boolean) {
        prefs.edit { putBoolean(INSTALLED_PREFIX + regionId, light) }
        _installedLight.value = if (light) _installedLight.value + regionId else _installedLight.value - regionId
    }

    private fun read(prefix: String): Map<String, Any?> =
        prefs.all.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }

    private companion object {
        const val CHOICE_PREFIX = "choice:"
        const val INSTALLED_PREFIX = "installed:"
    }
}
