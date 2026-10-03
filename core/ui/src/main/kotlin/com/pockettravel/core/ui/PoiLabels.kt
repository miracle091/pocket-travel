package com.pockettravel.core.ui

import androidx.annotation.StringRes
import com.pockettravel.core.poi.PoiCategory

/** Nome della categoria al plurale ("Farmacie"): legenda e segnalini della mappa, POI vicini nel contesto dell'assistente. */
@StringRes
fun PoiCategory.label(): Int = when (this) {
    PoiCategory.ALLOGGIO -> R.string.poi_lodging
    PoiCategory.CIBO_BEVANDE -> R.string.poi_food
    PoiCategory.NEGOZI -> R.string.poi_shopping
    PoiCategory.DISTRIBUTORI -> R.string.poi_vending
    PoiCategory.PARCO_GIOCHI -> R.string.poi_playground
    PoiCategory.TAVOLI_PICNIC -> R.string.poi_picnic_table
    PoiCategory.RIPARI -> R.string.poi_shelter
    PoiCategory.SPORT -> R.string.poi_sport
    PoiCategory.BIBLIOTECHE -> R.string.poi_library
    PoiCategory.MUNICIPIO -> R.string.poi_townhall
    PoiCategory.AMBULATORI -> R.string.poi_clinic
    PoiCategory.RIPARAZIONE_BICI -> R.string.poi_bike_repair
    PoiCategory.SERVIZI_CAMPER -> R.string.poi_camper_services
    PoiCategory.ACQUA_POTABILE -> R.string.poi_drinking_water
    PoiCategory.CASSETTA_POSTALE -> R.string.poi_post_box
    PoiCategory.LUOGHI_DI_CULTO -> R.string.poi_worship
    PoiCategory.MUSEI_ARTE -> R.string.poi_museums_art
    PoiCategory.LUOGHI_STORICI -> R.string.poi_historic
    PoiCategory.NATURA -> R.string.poi_nature
    PoiCategory.PANORAMI -> R.string.poi_viewpoints
    PoiCategory.PARCHI_DIVERTIMENTO -> R.string.poi_amusement
    PoiCategory.ZOO -> R.string.poi_zoo
    PoiCategory.PARCHI_ACQUATICI -> R.string.poi_water_park
    PoiCategory.ATTRAZIONI -> R.string.poi_attractions
    PoiCategory.SVAGO -> R.string.poi_entertainment
    PoiCategory.AMBASCIATA_CONSOLATO -> R.string.poi_embassy
    PoiCategory.POLIZIA -> R.string.poi_police
    PoiCategory.BAGNI_PUBBLICI -> R.string.poi_toilets
    PoiCategory.CARBURANTE -> R.string.poi_fuel
    PoiCategory.RICARICA -> R.string.poi_charging
    PoiCategory.FARMACIA -> R.string.poi_pharmacy
    PoiCategory.OSPEDALE -> R.string.poi_hospital
    PoiCategory.VIGILI_DEL_FUOCO -> R.string.poi_fire_station
    PoiCategory.VETERINARIO -> R.string.poi_veterinary
    PoiCategory.BANCA -> R.string.poi_bank
    PoiCategory.BANCOMAT -> R.string.poi_atm
    PoiCategory.UFFICIO_POSTALE -> R.string.poi_post_office
    PoiCategory.INFORMAZIONI -> R.string.poi_information
    PoiCategory.NOLEGGIO -> R.string.poi_rental
    PoiCategory.PARCHEGGIO -> R.string.poi_parking
    PoiCategory.PARCHEGGIO_PRIVATO -> R.string.poi_parking_private
    PoiCategory.PARCHEGGIO_DISABILI -> R.string.poi_parking_disabled
    PoiCategory.TRENO -> R.string.poi_train
    PoiCategory.METRO -> R.string.poi_metro
    PoiCategory.AUTOBUS -> R.string.poi_bus
    PoiCategory.TAXI -> R.string.poi_taxi
    PoiCategory.TRAGHETTO -> R.string.poi_ferry
    PoiCategory.PORTI_TURISTICI -> R.string.poi_marina
    PoiCategory.AEROPORTO -> R.string.poi_airport
    PoiCategory.ALTRO -> R.string.poi_other
}
