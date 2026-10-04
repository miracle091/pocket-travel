package com.pockettravel.feature.map

import android.content.Context
import androidx.core.content.edit
import com.pockettravel.core.data.RoutingVariantPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// Fino alla 0.8.0 "Con disabilita'" era una modalita' a parte, con il profilo di A piedi.
private const val LEGACY_ACCESSIBLE_MODE = "ACCESSIBILITA"

/** La modalita' singola salvata prima della scelta multipla (anche il vecchio "Con disabilita'") come insieme. */
internal fun legacyUsageModes(name: String?): Set<UsageMode> =
    if (name == LEGACY_ACCESSIBLE_MODE) setOf(UsageMode.A_PIEDI) else usageModesFromNames(listOfNotNull(name))

/**
 * Modalita' d'uso scelte (onboarding o Altro, anche piu' d'una), vuote finche' l'utente non ne sceglie, la
 * casella "In sedia a rotelle", che vale con qualsiasi modalita', e "Indicazioni" (percorsi scaricati
 * con ogni regione).
 */
@Singleton
class UsageModePreferences @Inject constructor(
    @ApplicationContext context: Context,
    private val filterPreferences: MapFilterPreferences,
    private val routingVariantPreferences: RoutingVariantPreferences,
) {
    private val prefs = context.getSharedPreferences("usage_mode", Context.MODE_PRIVATE).apply {
        // Prima della scelta multipla c'era una sola modalita' (KEY_MODE): diventa un insieme di un elemento.
        val legacyMode = getString(KEY_MODE, null)
        if (legacyMode != null) {
            val migrated = if (contains(KEY_MODES)) null else legacyUsageModes(legacyMode).mapTo(mutableSetOf()) { it.name }
            edit {
                remove(KEY_MODE)
                if (legacyMode == LEGACY_ACCESSIBLE_MODE) putBoolean(KEY_ACCESSIBLE, true)
                migrated?.let { putStringSet(KEY_MODES, it) }
            }
        }
    }
    private val _modes = MutableStateFlow(usageModesFromNames(prefs.getStringSet(KEY_MODES, null).orEmpty()))
    val modes: StateFlow<Set<UsageMode>> = _modes.asStateFlow()

    private val _accessible = MutableStateFlow(prefs.getBoolean(KEY_ACCESSIBLE, false))
    /** Nasconde sulla mappa i POI che OSM segna come non accessibili in sedia a rotelle (wheelchair=no). */
    val accessible: StateFlow<Boolean> = _accessible.asStateFlow()

    /**
     * Salva le modalita', riporta i filtri della mappa alle categorie predefinite della loro unione e imposta quali
     * percorsi scaricare per le nazioni senza una scelta nei Contenuti.
     */
    fun setModes(modes: Set<UsageMode>) {
        prefs.edit { putStringSet(KEY_MODES, modes.mapTo(mutableSetOf()) { it.name }) }
        _modes.value = modes
        filterPreferences.setHidden(modes.defaultHidden())
        routingVariantPreferences.setDefaultChoice(modes.routingDefault())
    }

    private val _wantsDirections = MutableStateFlow(prefs.getBoolean(KEY_DIRECTIONS, false))
    /** Scaricare anche il pacchetto Percorsi con "Scarica", per la navigazione (spento di default: pesa). */
    val wantsDirections: StateFlow<Boolean> = _wantsDirections.asStateFlow()

    fun setWantsDirections(wants: Boolean) {
        prefs.edit { putBoolean(KEY_DIRECTIONS, wants) }
        _wantsDirections.value = wants
    }

    fun setAccessible(accessible: Boolean) {
        prefs.edit { putBoolean(KEY_ACCESSIBLE, accessible) }
        _accessible.value = accessible
    }

    private val _allowSteps = MutableStateFlow(prefs.getBoolean(KEY_ALLOW_STEPS, false))
    /** Con "In sedia a rotelle", percorsi a piedi anche con qualche gradino (spento: scale vietate). */
    val allowSteps: StateFlow<Boolean> = _allowSteps.asStateFlow()

    fun setAllowSteps(allow: Boolean) {
        prefs.edit { putBoolean(KEY_ALLOW_STEPS, allow) }
        _allowSteps.value = allow
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_MODES = "modes"
        const val KEY_ACCESSIBLE = "accessible"
        const val KEY_DIRECTIONS = "directions"
        const val KEY_ALLOW_STEPS = "allow_steps"
    }
}
