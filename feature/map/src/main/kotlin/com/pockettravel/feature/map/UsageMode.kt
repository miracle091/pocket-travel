package com.pockettravel.feature.map

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.R as UiR

// In ogni modalita': dove dormire e mangiare, emergenze e i POI senza categoria precisa.
private val ALWAYS = setOf(
    PoiCategory.ALLOGGIO, PoiCategory.CIBO_BEVANDE, PoiCategory.BAGNI_PUBBLICI, PoiCategory.FARMACIA, PoiCategory.OSPEDALE,
    PoiCategory.POLIZIA, PoiCategory.VIGILI_DEL_FUOCO, PoiCategory.AMBASCIATA_CONSOLATO, PoiCategory.INFORMAZIONI,
    PoiCategory.ALTRO,
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
    // Nasconde i POI che OSM segna come non accessibili in sedia a rotelle (wheelchair=no).
    val hidesInaccessible: Boolean = false,
) {
    A_PIEDI(
        R.string.usage_mode_walk, UiR.drawable.ms_directions_walk, "shortest",
        setOf(
            PoiCategory.ATTRAZIONI, PoiCategory.SVAGO, PoiCategory.NEGOZI, PoiCategory.BANCA, PoiCategory.BANCOMAT,
            PoiCategory.UFFICIO_POSTALE, PoiCategory.ACQUA_POTABILE, PoiCategory.PARCO_GIOCHI, PoiCategory.TRENO,
            PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TAXI, PoiCategory.TRAGHETTO,
        ),
    ),
    ESCURSIONISMO(
        R.string.usage_mode_hiking, UiR.drawable.ms_hiking, "hiking-mountain",
        setOf(
            PoiCategory.ATTRAZIONI, PoiCategory.ACQUA_POTABILE, PoiCategory.TAVOLI_PICNIC, PoiCategory.RIPARI,
            PoiCategory.NEGOZI, PoiCategory.BANCOMAT, PoiCategory.TRENO, PoiCategory.AUTOBUS,
        ),
    ),
    BICI(
        R.string.usage_mode_bike, UiR.drawable.ms_directions_bike, "trekking",
        setOf(
            PoiCategory.ATTRAZIONI, PoiCategory.NOLEGGIO, PoiCategory.PARCHEGGIO, PoiCategory.ACQUA_POTABILE,
            PoiCategory.TAVOLI_PICNIC, PoiCategory.NEGOZI, PoiCategory.BANCOMAT, PoiCategory.TRENO,
        ),
    ),
    AUTO(
        R.string.usage_mode_car, UiR.drawable.ms_directions_car, "car-vario",
        setOf(
            PoiCategory.ATTRAZIONI, PoiCategory.CARBURANTE, PoiCategory.RICARICA, PoiCategory.PARCHEGGIO,
            PoiCategory.NOLEGGIO, PoiCategory.NEGOZI, PoiCategory.BANCOMAT, PoiCategory.AEROPORTO, PoiCategory.TRAGHETTO,
        ),
    ),
    CAMPER(
        R.string.usage_mode_camper, UiR.drawable.ms_rv_hookup, "car-vario",
        setOf(
            PoiCategory.SERVIZI_CAMPER, PoiCategory.CARBURANTE, PoiCategory.PARCHEGGIO, PoiCategory.ACQUA_POTABILE,
            PoiCategory.ATTRAZIONI, PoiCategory.NEGOZI, PoiCategory.BANCOMAT, PoiCategory.TRAGHETTO,
        ),
    ),
    MEZZI_PUBBLICI(
        R.string.usage_mode_transit, UiR.drawable.ms_directions_bus, "shortest",
        setOf(
            PoiCategory.TRENO, PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TAXI, PoiCategory.TRAGHETTO,
            PoiCategory.AEROPORTO, PoiCategory.NOLEGGIO, PoiCategory.ATTRAZIONI, PoiCategory.SVAGO, PoiCategory.NEGOZI,
            PoiCategory.BANCOMAT,
        ),
    ),
    // BRouter non ha un profilo ufficiale per la sedia a rotelle: "shortest", come a piedi.
    ACCESSIBILITA(
        R.string.usage_mode_wheelchair, UiR.drawable.ms_accessible, "shortest",
        setOf(
            PoiCategory.ATTRAZIONI, PoiCategory.SVAGO, PoiCategory.NEGOZI, PoiCategory.BANCA, PoiCategory.BANCOMAT,
            PoiCategory.UFFICIO_POSTALE, PoiCategory.TRENO, PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TAXI,
            PoiCategory.PARCHEGGIO,
        ),
        hidesInaccessible = true,
    ),
    ;

    val visibleCategories: Set<PoiCategory> = ALWAYS + visible

    /** Le categorie nascoste di default: quelle da salvare nei filtri quando si sceglie la modalita'. */
    val defaultHidden: Set<PoiCategory> get() = PoiCategory.entries.toSet() - visibleCategories

    companion object {
        /** Profilo prima che l'utente scelga una modalita': quello usato finora dall'app. */
        const val DEFAULT_ROUTING_PROFILE = "trekking"
        val ROUTING_PROFILES: Set<String> = entries.mapTo(mutableSetOf()) { it.routingProfile } + DEFAULT_ROUTING_PROFILE
    }
}
