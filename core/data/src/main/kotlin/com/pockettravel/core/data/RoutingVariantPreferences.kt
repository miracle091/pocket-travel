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
 * Percorsi "solo auto" per regione: [carOnly] le regioni in cui l'utente li ha scelti (si scaricano al posto di quelli
 * completi), [installedCarOnly] quelle i cui percorsi installati sono solo per l'auto, scritto dopo ogni installazione:
 * con quelli il Navigatore non calcola percorsi a piedi, in bici o in carrozzina.
 */
@Singleton
class RoutingVariantPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("routing_variant", Context.MODE_PRIVATE)
    private val _carOnly = MutableStateFlow(read(CHOICE_PREFIX))
    private val _installedCarOnly = MutableStateFlow(read(INSTALLED_PREFIX))
    val carOnly: StateFlow<Set<String>> = _carOnly.asStateFlow()
    val installedCarOnly: StateFlow<Set<String>> = _installedCarOnly.asStateFlow()

    fun setCarOnly(regionId: String, carOnly: Boolean) {
        prefs.edit { putBoolean(CHOICE_PREFIX + regionId, carOnly) }
        _carOnly.value = if (carOnly) _carOnly.value + regionId else _carOnly.value - regionId
    }

    fun setInstalledCarOnly(regionId: String, carOnly: Boolean) {
        prefs.edit { putBoolean(INSTALLED_PREFIX + regionId, carOnly) }
        _installedCarOnly.value = if (carOnly) _installedCarOnly.value + regionId else _installedCarOnly.value - regionId
    }

    private fun read(prefix: String): Set<String> =
        prefs.all.filter { it.key.startsWith(prefix) && it.value == true }.keys.map { it.removePrefix(prefix) }.toSet()

    private companion object {
        const val CHOICE_PREFIX = "choice:"
        const val INSTALLED_PREFIX = "installed:"
    }
}
