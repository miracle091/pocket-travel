package com.pockettravel.core.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Per quali mezzi si scaricano i percorsi di una regione: solo [CAR] scarica la variante "solo auto". */
enum class RoutingVariantChoice { CAR, BIKE_FOOT, ALL }

/**
 * Le scelte dei percorsi per regione. [carOnly] e [bikeFoot]: le regioni scelte nei Contenuti; [explicit]: quelle con una
 * scelta nei Contenuti (anche "Tutti"), che vince sempre. Le altre seguono [default], impostato dalle modalita' d'uso.
 */
data class RoutingVariantChoices(
    val carOnly: Set<String> = emptySet(),
    val bikeFoot: Set<String> = emptySet(),
    val explicit: Set<String> = emptySet(),
    val default: RoutingVariantChoice = RoutingVariantChoice.ALL,
) {
    /** La regola unica, per il worker di download e per i Contenuti. */
    fun choiceFor(regionId: String): RoutingVariantChoice = when {
        regionId !in explicit -> default
        regionId in carOnly -> RoutingVariantChoice.CAR
        regionId in bikeFoot -> RoutingVariantChoice.BIKE_FOOT
        else -> RoutingVariantChoice.ALL
    }

    fun isCarOnly(regionId: String): Boolean = choiceFor(regionId) == RoutingVariantChoice.CAR
}

/**
 * Percorsi "solo auto" per regione: [choices] le scelte (nei Contenuti o, senza, secondo le modalita' d'uso; "solo auto" si
 * scarica al posto di quelli completi, "Bici e piedi" come "Tutti": cambia solo la scelta mostrata), [installedCarOnly]
 * le regioni i cui percorsi installati sono solo per l'auto, scritto dopo ogni installazione: con quelli il Navigatore
 * non calcola percorsi a piedi, in bici o in carrozzina.
 */
@Singleton
class RoutingVariantPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("routing_variant", Context.MODE_PRIVATE)
    private val _choices = MutableStateFlow(
        RoutingVariantChoices(
            carOnly = read(CHOICE_PREFIX),
            bikeFoot = read(BIKE_FOOT_PREFIX),
            // Con la chiave scritta (true o false) la scelta e' esplicita.
            explicit = prefs.all.keys.filter { it.startsWith(CHOICE_PREFIX) }.map { it.removePrefix(CHOICE_PREFIX) }.toSet(),
            default = prefs.getString(KEY_DEFAULT, null)?.let { name -> RoutingVariantChoice.entries.firstOrNull { it.name == name } }
                ?: RoutingVariantChoice.ALL,
        ),
    )
    val choices: StateFlow<RoutingVariantChoices> = _choices.asStateFlow()
    private val _installedCarOnly = MutableStateFlow(read(INSTALLED_PREFIX))
    val installedCarOnly: StateFlow<Set<String>> = _installedCarOnly.asStateFlow()

    fun setCarOnly(regionId: String, carOnly: Boolean) {
        prefs.edit { putBoolean(CHOICE_PREFIX + regionId, carOnly) }
        _choices.update { it.copy(carOnly = if (carOnly) it.carOnly + regionId else it.carOnly - regionId, explicit = it.explicit + regionId) }
    }

    fun setBikeFoot(regionId: String, bikeFoot: Boolean) {
        prefs.edit { putBoolean(BIKE_FOOT_PREFIX + regionId, bikeFoot) }
        _choices.update { it.copy(bikeFoot = if (bikeFoot) it.bikeFoot + regionId else it.bikeFoot - regionId) }
    }

    /** La scelta per le regioni senza una esplicita. */
    fun setDefaultChoice(choice: RoutingVariantChoice) {
        prefs.edit { putString(KEY_DEFAULT, choice.name) }
        _choices.update { it.copy(default = choice) }
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
        const val BIKE_FOOT_PREFIX = "bikefoot:"
        const val KEY_DEFAULT = "default_choice"
    }
}
