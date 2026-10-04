package com.pockettravel.feature.map

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.pockettravel.core.data.RoutingVariantChoice
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.poi.SIGHT_CATEGORIES
import com.pockettravel.core.ui.R as UiR

// "Da vedere" intera (SIGHT_CATEGORIES), dove una modalita' la mostra.
private val SIGHTS = SIGHT_CATEGORIES.toTypedArray()

// In ogni modalita': dove dormire e mangiare, emergenze e dove cambiare i soldi.
private val ALWAYS = setOf(
    PoiCategory.ALLOGGIO, PoiCategory.CIBO_BEVANDE, PoiCategory.BAGNI_PUBBLICI, PoiCategory.FARMACIA, PoiCategory.OSPEDALE,
    PoiCategory.POLIZIA, PoiCategory.VIGILI_DEL_FUOCO, PoiCategory.AMBASCIATA_CONSOLATO, PoiCategory.INFORMAZIONI,
    PoiCategory.CAMBIO_VALUTA,
)

/**
 * Come l'utente si sposta: decide le categorie mostrate di default sulla mappa (scegliendo una
 * modalita' i filtri tornano a queste) e il profilo BRouter per i percorsi (file .brf in
 * assets/brouter-profile, dal tag v1.7.10 di BRouter come lookups.dat).
 */
enum class UsageMode(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val routingProfile: String,
    visible: Set<PoiCategory>,
) {
    A_PIEDI(
        R.string.usage_mode_walk, UiR.drawable.ms_directions_walk, "shortest",
        setOf(
            *SIGHTS, PoiCategory.SVAGO, PoiCategory.NEGOZI, PoiCategory.BANCA, PoiCategory.BANCOMAT,
            PoiCategory.UFFICIO_POSTALE, PoiCategory.ACQUA_POTABILE, PoiCategory.PARCO_GIOCHI, PoiCategory.TRENO,
            PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TAXI, PoiCategory.TRAGHETTO, PoiCategory.PORTI_TURISTICI,
            PoiCategory.INTERNET_CAFE,
        ),
    ),
    ESCURSIONISMO(
        R.string.usage_mode_hiking, UiR.drawable.ms_hiking, "hiking-mountain",
        setOf(
            *SIGHTS, PoiCategory.ACQUA_POTABILE, PoiCategory.TAVOLI_PICNIC, PoiCategory.RIPARI,
            PoiCategory.NEGOZI, PoiCategory.BANCOMAT, PoiCategory.TRENO, PoiCategory.AUTOBUS,
        ),
    ),
    BICI(
        R.string.usage_mode_bike, UiR.drawable.ms_directions_bike, "trekking",
        setOf(
            *SIGHTS, PoiCategory.NOLEGGIO, PoiCategory.RIPARAZIONE_BICI, PoiCategory.PARCHEGGIO, PoiCategory.ACQUA_POTABILE,
            PoiCategory.TAVOLI_PICNIC, PoiCategory.NEGOZI, PoiCategory.BANCOMAT, PoiCategory.TRENO,
        ),
    ),
    AUTO(
        R.string.usage_mode_car, UiR.drawable.ms_directions_car, "car-vario",
        setOf(
            *SIGHTS, PoiCategory.CARBURANTE, PoiCategory.RICARICA, PoiCategory.PARCHEGGIO,
            PoiCategory.NOLEGGIO, PoiCategory.NEGOZI, PoiCategory.BANCOMAT, PoiCategory.AEROPORTO, PoiCategory.TRAGHETTO, PoiCategory.PORTI_TURISTICI,
        ),
    ),
    CAMPER(
        R.string.usage_mode_camper, UiR.drawable.ms_rv_hookup, "car-vario",
        setOf(
            PoiCategory.SERVIZI_CAMPER, PoiCategory.CARBURANTE, PoiCategory.PARCHEGGIO, PoiCategory.ACQUA_POTABILE,
            *SIGHTS, PoiCategory.NEGOZI, PoiCategory.BANCOMAT, PoiCategory.TRAGHETTO, PoiCategory.PORTI_TURISTICI,
        ),
    ),
    MEZZI_PUBBLICI(
        R.string.usage_mode_transit, UiR.drawable.ms_directions_bus, "shortest",
        setOf(
            PoiCategory.TRENO, PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TAXI, PoiCategory.TRAGHETTO,
            PoiCategory.AEROPORTO, PoiCategory.NOLEGGIO, *SIGHTS, PoiCategory.SVAGO, PoiCategory.NEGOZI,
            PoiCategory.BANCOMAT, PoiCategory.INTERNET_CAFE,
        ),
    ),
    ;

    val visibleCategories: Set<PoiCategory> = ALWAYS + visible

    /** Chi va a piedi, in bici, in escursione o coi mezzi pubblici: al primo avvio gli orari dei mezzi sono spuntati. */
    val proposesTransit: Boolean get() = this == A_PIEDI || this == BICI || this == ESCURSIONISMO || this == MEZZI_PUBBLICI

    /** Le categorie nascoste di default: quelle da salvare nei filtri quando si sceglie la modalita'. */
    val defaultHidden: Set<PoiCategory> get() = PoiCategory.entries.toSet() - visibleCategories

    companion object {
        /** Profilo prima che l'utente scelga una modalita'. */
        const val DEFAULT_ROUTING_PROFILE = "trekking"

        /** A piedi con "In sedia a rotelle" (routingChoice): non legato a una modalita'. */
        const val WHEELCHAIR_ROUTING_PROFILE = "wheelchair"
        val ROUTING_PROFILES: Set<String> =
            entries.mapTo(mutableSetOf()) { it.routingProfile } + DEFAULT_ROUTING_PROFILE + WHEELCHAIR_ROUTING_PROFILE
    }
}

/** Le categorie mostrate con piu' modalita': l'unione di quelle di ciascuna. */
fun Set<UsageMode>.visibleCategories(): Set<PoiCategory> = flatMapTo(mutableSetOf()) { it.visibleCategories }

/** Le categorie da nascondere nei filtri scegliendo le modalita'; senza modalita' (non scelto) nessuna. */
fun Set<UsageMode>.defaultHidden(): Set<PoiCategory> =
    if (isEmpty()) emptySet() else PoiCategory.entries.toSet() - visibleCategories()

/** Gli orari dei mezzi si propongono se almeno una modalita' li propone. */
fun Set<UsageMode>.proposesTransit(): Boolean = any { it.proposesTransit }

/**
 * Profilo BRouter di default: quello del mezzo iniziale del Navigatore ([RouteProfile.from]); solo con Escursionismo
 * da solo quello dei sentieri, senza modalita' scelte quello di prima della scelta.
 */
fun Set<UsageMode>.defaultRoutingProfile(): String = when {
    isEmpty() -> UsageMode.DEFAULT_ROUTING_PROFILE
    this == setOf(UsageMode.ESCURSIONISMO) -> UsageMode.ESCURSIONISMO.routingProfile
    else -> RouteProfile.from(this).routingProfile
}

/**
 * Percorsi da scaricare per le nazioni senza una scelta nei Contenuti: solo auto o camper bastano quelli per l'auto,
 * senza mezzi a motore il pacchetto completo (bici e piedi), con modalita' miste o nessuna tutti.
 */
fun Set<UsageMode>.routingDefault(): RoutingVariantChoice {
    val motorised = count { it == UsageMode.AUTO || it == UsageMode.CAMPER }
    return when {
        isEmpty() -> RoutingVariantChoice.ALL
        motorised == size -> RoutingVariantChoice.CAR
        motorised == 0 -> RoutingVariantChoice.BIKE_FOOT
        else -> RoutingVariantChoice.ALL
    }
}

/** Le modalita' dai nomi salvati; i nomi sconosciuti (di una versione diversa) si scartano. */
internal fun usageModesFromNames(names: Collection<String>): Set<UsageMode> =
    UsageMode.entries.filterTo(mutableSetOf()) { it.name in names }
