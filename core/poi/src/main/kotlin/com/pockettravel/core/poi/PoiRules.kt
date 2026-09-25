package com.pockettravel.core.poi

enum class PoiCategory {
    ALLOGGIO,
    CIBO_BEVANDE,
    NEGOZI,
    DISTRIBUTORI,
    ATTRAZIONI,
    SVAGO,
    PARCO_GIOCHI,
    TAVOLI_PICNIC,
    AMBASCIATA_CONSOLATO,
    POLIZIA,
    BAGNI_PUBBLICI,
    ACQUA_POTABILE,
    CARBURANTE,
    RICARICA,
    FARMACIA,
    OSPEDALE,
    VIGILI_DEL_FUOCO,
    VETERINARIO,
    BANCA,
    BANCOMAT,
    UFFICIO_POSTALE,
    CASSETTA_POSTALE,
    INFORMAZIONI,
    NOLEGGIO,
    PARCHEGGIO,
    PARCHEGGIO_PRIVATO,
    TRENO,
    METRO,
    AUTOBUS,
    TAXI,
    TRAGHETTO,
    AEROPORTO,
    ALTRO,
}

private val accommodationValues = setOf("hotel", "guest_house", "hostel", "motel", "apartment", "camp_site", "caravan_site", "chalet")
private val foodDrinkValues = setOf("restaurant", "cafe", "bar", "pub", "fast_food", "food_court", "ice_cream", "biergarten")
private val rentalTags = setOf(
    "amenity=car_rental", "amenity=bicycle_rental", "amenity=motorcycle_rental", "amenity=scooter_rental",
    "amenity=boat_rental", "amenity=ski_rental",
)
private val entertainmentTags = setOf(
    "amenity=cinema", "amenity=theatre", "amenity=nightclub", "amenity=casino", "leisure=bowling_alley",
    "leisure=amusement_arcade",
)
private val attractionValues = setOf(
    "attraction", "museum", "viewpoint", "gallery", "artwork", "zoo", "theme_park", "water_park", "park", "garden",
    "monastery", "place_of_worship", "fountain", "nature_reserve",
)

/**
 * Raggruppa il tag OSM grezzo di un POI (Poi.category/osmTag, uno dei tanti valori possibili di
 * amenity/shop/tourism/leisure/historic/railway/aeroway — vedi poiTagKeys in GeneratePoi.kt) in una manciata di
 * macro-categorie per la mappa (icona + filtro), sullo stesso spirito delle 5 GuideCategory
 * pratiche già in uso per le guide testuali.
 */
fun poiCategoryOf(category: String, osmTag: String): PoiCategory = when {
    category == "embassy" -> PoiCategory.AMBASCIATA_CONSOLATO
    osmTag == "amenity=police" -> PoiCategory.POLIZIA
    osmTag == "amenity=toilets" -> PoiCategory.BAGNI_PUBBLICI
    osmTag == "amenity=fuel" -> PoiCategory.CARBURANTE
    osmTag == "amenity=charging_station" -> PoiCategory.RICARICA
    osmTag == "amenity=pharmacy" -> PoiCategory.FARMACIA
    osmTag == "amenity=hospital" -> PoiCategory.OSPEDALE
    osmTag == "amenity=fire_station" -> PoiCategory.VIGILI_DEL_FUOCO
    osmTag == "amenity=veterinary" -> PoiCategory.VETERINARIO
    osmTag == "amenity=bank" -> PoiCategory.BANCA
    osmTag == "amenity=atm" -> PoiCategory.BANCOMAT
    osmTag == "amenity=post_office" -> PoiCategory.UFFICIO_POSTALE
    // Tipi del pacchetto POI extra (vedi poiPackageOf); i parchi giochi con nome sono nel base.
    osmTag == "amenity=drinking_water" -> PoiCategory.ACQUA_POTABILE
    osmTag == "leisure=picnic_table" -> PoiCategory.TAVOLI_PICNIC
    osmTag == "leisure=playground" -> PoiCategory.PARCO_GIOCHI
    osmTag == "amenity=vending_machine" -> PoiCategory.DISTRIBUTORI
    osmTag == "amenity=post_box" -> PoiCategory.CASSETTA_POSTALE
    // "information_office": tourism=information con information=office, deciso dalla pipeline (GeneratePoi.kt).
    category == "information_office" -> PoiCategory.INFORMAZIONI
    osmTag in rentalTags -> PoiCategory.NOLEGGIO
    // "parking_private": access privato, deciso dalla pipeline (GeneratePoi.kt).
    osmTag == "amenity=parking" && category == "parking_private" -> PoiCategory.PARCHEGGIO_PRIVATO
    osmTag == "amenity=parking" || osmTag == "amenity=parking_entrance" -> PoiCategory.PARCHEGGIO
    // Ogni altro parcheggio (bici, moto...), tranne quelli delle barche.
    osmTag.startsWith("amenity=") && osmTag.endsWith("_parking") && osmTag != "amenity=boat_parking" ->
        PoiCategory.PARCHEGGIO
    // "subway_station": railway=station della metropolitana, deciso dalla pipeline (GeneratePoi.kt).
    osmTag == "railway=station" && category == "subway_station" -> PoiCategory.METRO
    osmTag == "railway=station" || osmTag == "railway=halt" -> PoiCategory.TRENO
    osmTag == "amenity=bus_station" -> PoiCategory.AUTOBUS
    osmTag == "amenity=taxi" -> PoiCategory.TAXI
    osmTag == "amenity=ferry_terminal" -> PoiCategory.TRAGHETTO
    osmTag == "aeroway=aerodrome" -> PoiCategory.AEROPORTO
    osmTag in entertainmentTags -> PoiCategory.SVAGO
    osmTag.startsWith("shop=") -> PoiCategory.NEGOZI
    osmTag.startsWith("historic=") -> PoiCategory.ATTRAZIONI
    category in accommodationValues -> PoiCategory.ALLOGGIO
    category in foodDrinkValues -> PoiCategory.CIBO_BEVANDE
    category in attractionValues -> PoiCategory.ATTRAZIONI
    else -> PoiCategory.ALTRO
}

// Arredo urbano fitto e inutile per chi viaggia (cestini, panchine, tavoli da picnic, singoli stalli)
// e tipi che non servono a un turista (scuole, riciclo, fontanelle...): coprirebbero i segnalini
// utili. ("waste_box" non e' un valore OSM standard ma compare nei dati.)
private val hiddenOnMapTags = setOf(
    "amenity=waste_basket", "amenity=waste_disposal", "amenity=waste_box", "amenity=bench", "amenity=parking_space",
    "leisure=picnic_table",
    "leisure=beach_resort", "amenity=drinking_water", "amenity=recycling", "amenity=photo_booth",
    "amenity=driving_school", "amenity=kindergarten", "amenity=school", "amenity=childcare", "amenity=university",
    "amenity=bus_rental", "amenity=dancing_school", "amenity=telecommunication", "leisure=adult_gaming_centre",
    "amenity=compressed_air", "amenity=fish_spa", "amenity=music_school", "amenity=sanitary_dump_station",
    "amenity=surf_school",
)

// Servizi che in OSM di solito non hanno un nome (bagni, bancomat, parcheggi...): restano sulla mappa
// anche senza, a differenza di tutti gli altri POI.
private val namelessOnMapCategories = setOf(
    PoiCategory.BAGNI_PUBBLICI, PoiCategory.BANCOMAT, PoiCategory.PARCHEGGIO, PoiCategory.CARBURANTE,
    PoiCategory.RICARICA, PoiCategory.FARMACIA, PoiCategory.OSPEDALE, PoiCategory.TAXI, PoiCategory.UFFICIO_POSTALE,
)

/** false se OSM non ha un nome: la pipeline mette allora il valore del tag (es. "toilets"). */
fun poiHasName(name: String, osmTag: String): Boolean = name != osmTag.substringAfter("=")

/**
 * true per i POI da non mostrare sulla mappa: i tipi sopra, i parcheggi privati, i cartelli
 * informativi e quelli senza nome, tranne i servizi di namelessOnMapCategories e le fontane.
 */
fun isPoiHiddenOnMap(name: String, category: String, osmTag: String): Boolean {
    val poiCategory = poiCategoryOf(category, osmTag)
    return osmTag in hiddenOnMapTags ||
        poiCategory == PoiCategory.PARCHEGGIO_PRIVATO ||
        (osmTag == "tourism=information" && category != "information_office") ||
        (!poiHasName(name, osmTag) && poiCategory !in namelessOnMapCategories && osmTag != "amenity=fountain")
}

/** Pacchetto in cui la pipeline pubblica un POI (vedi [poiPackageOf]). */
enum class PoiPackage { BASE, EXTRA }

// POI nascosti sulla mappa ma utili a chi li vuole: pacchetto "extra", scaricato solo su richiesta
// (scelta dell'utente, 2026-09-25). Tutti gli altri POI nascosti non si pubblicano.
private val extraTags = setOf(
    "amenity=drinking_water", "leisure=picnic_table", "leisure=playground", "amenity=vending_machine",
    "amenity=post_box",
)
private val extraNamelessCategories = setOf(PoiCategory.CIBO_BEVANDE, PoiCategory.NEGOZI)

/**
 * BASE per i POI che la mappa mostra, EXTRA per fontanelle, tavoli da picnic, parchi giochi,
 * distributori automatici, cassette postali e bar, ristoranti e negozi senza nome (tranne i negozi
 * sfitti), null per tutto il resto: nascosto sulla mappa, quindi non pubblicato.
 */
fun poiPackageOf(name: String, category: String, osmTag: String): PoiPackage? = when {
    !isPoiHiddenOnMap(name, category, osmTag) -> PoiPackage.BASE
    osmTag in extraTags -> PoiPackage.EXTRA
    osmTag != "shop=vacant" && !poiHasName(name, osmTag) && poiCategoryOf(category, osmTag) in extraNamelessCategories ->
        PoiPackage.EXTRA
    else -> null
}
