package com.pockettravel.core.poi

enum class PoiCategory {
    ALLOGGIO,
    CIBO_BEVANDE,
    NEGOZI,
    DISTRIBUTORI,
    // "Da vedere", divisa per tipo (SIGHT_CATEGORIES); ATTRAZIONI raccoglie quello che resta.
    LUOGHI_DI_CULTO,
    MUSEI_ARTE,
    LUOGHI_STORICI,
    NATURA,
    PANORAMI,
    PARCHI_DIVERTIMENTO,
    ZOO,
    PARCHI_ACQUATICI,
    ATTRAZIONI,
    SVAGO,
    SPORT,
    PARCO_GIOCHI,
    TAVOLI_PICNIC,
    RIPARI,
    AMBASCIATA_CONSOLATO,
    BIBLIOTECHE,
    INTERNET_CAFE,
    POLIZIA,
    MUNICIPIO,
    BAGNI_PUBBLICI,
    ACQUA_POTABILE,
    CARBURANTE,
    RICARICA,
    SERVIZI_CAMPER,
    RIPARAZIONE_BICI,
    FARMACIA,
    OSPEDALE,
    AMBULATORI,
    VIGILI_DEL_FUOCO,
    VETERINARIO,
    BANCA,
    BANCOMAT,
    CAMBIO_VALUTA,
    UFFICIO_POSTALE,
    CASSETTA_POSTALE,
    INFORMAZIONI,
    NOLEGGIO,
    PARCHEGGIO,
    PARCHEGGIO_PRIVATO,
    PARCHEGGIO_DISABILI,
    TRENO,
    METRO,
    AUTOBUS,
    TAXI,
    TRAGHETTO,
    PORTI_TURISTICI,
    AEROPORTO,
    // Tag senza una categoria precisa: nascosti e non pubblicati (isPoiHiddenOnMap). Resta per i filtri gia' salvati.
    ALTRO,
}

// Anche i rifugi alpini gestiti (ci si dorme e spesso si mangia) e i posti tenda o camper con un nome, spesso
// aree di sosta libere; quelli senza nome, di solito dentro un campeggio, restano nascosti come ogni alloggio senza nome.
private val accommodationValues = setOf(
    "hotel", "guest_house", "hostel", "motel", "apartment", "camp_site", "caravan_site", "chalet", "alpine_hut", "camp_pitch",
)
private val foodDrinkValues = setOf(
    "restaurant", "cafe", "bar", "pub", "fast_food", "food_court", "ice_cream", "biergarten", "juice_bar",
)
private val rentalTags = setOf(
    "amenity=car_rental", "amenity=bicycle_rental", "amenity=motorcycle_rental", "amenity=scooter_rental",
    "amenity=boat_rental", "amenity=ski_rental", "amenity=car_sharing",
)
private val entertainmentTags = setOf(
    "amenity=cinema", "amenity=theatre", "amenity=nightclub", "amenity=casino", "leisure=bowling_alley",
    "leisure=amusement_arcade", "amenity=arts_centre", "amenity=events_venue", "amenity=conference_centre",
    "leisure=dance", "leisure=escape_game", "amenity=gambling", "amenity=karaoke_box", "leisure=karaoke",
    "amenity=hookah_lounge", "amenity=exhibition_centre", "amenity=music_venue",
)
// Anche impianti sportivi, piscine, saune e terme.
private val sportTags = setOf(
    "leisure=sports_centre", "leisure=fitness_centre", "leisure=ski_resort", "leisure=spa", "leisure=pitch",
    "leisure=sports_hall", "leisure=stadium", "leisure=swimming_pool", "leisure=ice_rink", "leisure=golf_course",
    "leisure=miniature_golf", "leisure=disc_golf_course", "leisure=track", "leisure=horse_riding",
    "leisure=fitness_station", "leisure=trampoline_park", "leisure=sauna", "amenity=dojo", "amenity=dive_centre",
    "amenity=public_bath", "amenity=spa", "amenity=kneipp_water_cure",
)
// Luoghi dove fare il bagno all'aperto e capanni per osservare gli uccelli.
private val natureTags = setOf("leisure=bathing_place", "leisure=swimming_area", "leisure=bird_hide")
// Cimiteri con un nome (Pere-Lachaise, Staglieno): quelli senza nome restano nascosti come gli altri POI.
private val cemeteryTags = setOf("amenity=grave_yard", "amenity=cemetery")
private val worshipValues = setOf("place_of_worship", "monastery")
private val museumArtValues = setOf("museum", "gallery", "artwork")
private val natureValues = setOf("park", "garden", "nature_reserve")
private val amusementValues = setOf("theme_park")
private val attractionValues = setOf("attraction", "fountain")

/** Le categorie di "Da vedere": per le modalita' d'uso e per i filtri salvati quando era una sola. */
val SIGHT_CATEGORIES: Set<PoiCategory> = setOf(
    PoiCategory.LUOGHI_DI_CULTO, PoiCategory.MUSEI_ARTE, PoiCategory.LUOGHI_STORICI, PoiCategory.NATURA,
    PoiCategory.PANORAMI, PoiCategory.PARCHI_DIVERTIMENTO, PoiCategory.ZOO, PoiCategory.PARCHI_ACQUATICI, PoiCategory.ATTRAZIONI,
)

/**
 * Raggruppa il tag OSM grezzo di un POI (Poi.category/osmTag, uno dei tanti valori possibili di
 * amenity/shop/tourism/leisure/historic/railway/aeroway — vedi poiTagKeys in GeneratePoi.kt) in una manciata di
 * macro-categorie per la mappa (icona + filtro), con lo stesso criterio delle GuideCategory
 * delle guide testuali.
 */
fun poiCategoryOf(category: String, osmTag: String): PoiCategory = when {
    category == "embassy" -> PoiCategory.AMBASCIATA_CONSOLATO
    osmTag == "amenity=police" -> PoiCategory.POLIZIA
    osmTag == "amenity=toilets" -> PoiCategory.BAGNI_PUBBLICI
    osmTag == "amenity=fuel" -> PoiCategory.CARBURANTE
    osmTag == "amenity=charging_station" -> PoiCategory.RICARICA
    // Dispensari: farmacie di base, frequenti in Africa e Asia.
    osmTag == "amenity=pharmacy" || osmTag == "amenity=dispensary" -> PoiCategory.FARMACIA
    osmTag == "amenity=hospital" -> PoiCategory.OSPEDALE
    osmTag == "amenity=clinic" || osmTag == "amenity=doctors" || osmTag == "amenity=dentist" ||
        osmTag == "amenity=health_post" -> PoiCategory.AMBULATORI
    osmTag == "amenity=townhall" -> PoiCategory.MUNICIPIO
    osmTag in sportTags -> PoiCategory.SPORT
    osmTag == "amenity=library" -> PoiCategory.BIBLIOTECHE
    // Postazioni PC per stampare la carta d'imbarco o chiamare: frequenti in Asia, Africa e Sud America.
    osmTag == "amenity=internet_cafe" -> PoiCategory.INTERNET_CAFE
    // Lavatoi pubblici storici, spesso senza tag historic.
    osmTag == "amenity=lavoir" || osmTag in cemeteryTags -> PoiCategory.LUOGHI_STORICI
    osmTag in natureTags -> PoiCategory.NATURA
    osmTag == "tourism=aquarium" -> PoiCategory.ZOO
    osmTag == "amenity=planetarium" -> PoiCategory.MUSEI_ARTE
    // Moschee e chiese taggate col valore sbagliato (amenity=mosque invece di place_of_worship).
    osmTag == "amenity=mosque" || osmTag == "amenity=church" -> PoiCategory.LUOGHI_DI_CULTO
    osmTag == "amenity=shower" -> PoiCategory.BAGNI_PUBBLICI
    osmTag == "amenity=ranger_station" -> PoiCategory.INFORMAZIONI
    osmTag == "leisure=indoor_play" -> PoiCategory.PARCO_GIOCHI
    osmTag == "leisure=slipway" -> PoiCategory.PORTI_TURISTICI
    osmTag == "amenity=bicycle_repair_station" -> PoiCategory.RIPARAZIONE_BICI
    osmTag == "amenity=fire_station" -> PoiCategory.VIGILI_DEL_FUOCO
    osmTag == "amenity=veterinary" -> PoiCategory.VETERINARIO
    // Anche i money transfer (Western Union...), gli agenti di mobile money (contanti in Africa) e gli sportelli dove
    // pagare le bollette.
    osmTag == "amenity=bank" || osmTag == "amenity=money_transfer" || osmTag == "amenity=mobile_money_agent" ||
        osmTag == "amenity=payment_centre" -> PoiCategory.BANCA
    osmTag == "amenity=atm" -> PoiCategory.BANCOMAT
    osmTag == "amenity=bureau_de_change" -> PoiCategory.CAMBIO_VALUTA
    osmTag == "amenity=post_office" -> PoiCategory.UFFICIO_POSTALE
    // Tipi del pacchetto POI extra (vedi poiPackageOf); i parchi giochi con nome sono nel base.
    osmTag == "amenity=drinking_water" -> PoiCategory.ACQUA_POTABILE
    osmTag == "leisure=picnic_table" || osmTag == "tourism=picnic_site" || osmTag == "amenity=bbq" ||
        osmTag == "leisure=firepit" -> PoiCategory.TAVOLI_PICNIC
    // Bivacchi, capanne e tettoie (le pensiline delle fermate le scarta la pipeline, vedi GeneratePoi.kt).
    osmTag == "amenity=shelter" || osmTag == "tourism=wilderness_hut" || osmTag == "tourism=lean_to" -> PoiCategory.RIPARI
    // Scarico dei serbatoi e rifornimento d'acqua per camper e caravan.
    osmTag == "amenity=sanitary_dump_station" || osmTag == "amenity=water_point" || osmTag == "amenity=car_wash" ->
        PoiCategory.SERVIZI_CAMPER
    osmTag == "leisure=playground" -> PoiCategory.PARCO_GIOCHI
    osmTag == "amenity=vending_machine" -> PoiCategory.DISTRIBUTORI
    osmTag == "amenity=post_box" -> PoiCategory.CASSETTA_POSTALE
    // "information_office": tourism=information con information=office, deciso dalla pipeline (GeneratePoi.kt).
    category == "information_office" -> PoiCategory.INFORMAZIONI
    osmTag in rentalTags -> PoiCategory.NOLEGGIO
    // "parking_private": access privato, deciso dalla pipeline (GeneratePoi.kt).
    osmTag == "amenity=parking" && category == "parking_private" -> PoiCategory.PARCHEGGIO_PRIVATO
    // "parking_disabled": stallo riservato ai disabili (parking_space=disabled), deciso dalla pipeline.
    osmTag == "amenity=parking_space" && category == "parking_disabled" -> PoiCategory.PARCHEGGIO_DISABILI
    osmTag == "amenity=parking" || osmTag == "amenity=parking_entrance" -> PoiCategory.PARCHEGGIO
    // Ogni altro parcheggio (bici, moto...), tranne quelli delle barche.
    osmTag.startsWith("amenity=") && osmTag.endsWith("_parking") && osmTag != "amenity=boat_parking" ->
        PoiCategory.PARCHEGGIO
    // "subway_station": railway=station della metropolitana, deciso dalla pipeline (GeneratePoi.kt).
    osmTag == "railway=station" && category == "subway_station" -> PoiCategory.METRO
    osmTag == "railway=station" || osmTag == "railway=halt" -> PoiCategory.TRENO
    osmTag == "amenity=bus_station" -> PoiCategory.AUTOBUS
    osmTag == "amenity=taxi" || osmTag == "amenity=shared_taxi" -> PoiCategory.TAXI
    osmTag == "amenity=ferry_terminal" -> PoiCategory.TRAGHETTO
    osmTag == "leisure=marina" -> PoiCategory.PORTI_TURISTICI
    osmTag == "aeroway=aerodrome" -> PoiCategory.AEROPORTO
    osmTag in entertainmentTags -> PoiCategory.SVAGO
    osmTag.startsWith("shop=") -> PoiCategory.NEGOZI
    // Mercati all'aperto ("Mercato del Sabato"): si fanno acquisti, non sono "Altro".
    osmTag == "amenity=marketplace" -> PoiCategory.NEGOZI
    osmTag == "historic=church" || osmTag == "historic=monastery" -> PoiCategory.LUOGHI_DI_CULTO
    osmTag.startsWith("historic=") -> PoiCategory.LUOGHI_STORICI
    // Villaggi turistici e residence (leisure=resort), capanne e love hotel: ci si dorme, non sono "Altro".
    category in accommodationValues || osmTag == "leisure=resort" || osmTag == "tourism=cabin" ||
        osmTag == "amenity=love_hotel" -> PoiCategory.ALLOGGIO
    category in foodDrinkValues -> PoiCategory.CIBO_BEVANDE
    category in worshipValues -> PoiCategory.LUOGHI_DI_CULTO
    category in museumArtValues -> PoiCategory.MUSEI_ARTE
    category == "viewpoint" -> PoiCategory.PANORAMI
    category in natureValues -> PoiCategory.NATURA
    category in amusementValues -> PoiCategory.PARCHI_DIVERTIMENTO
    category == "zoo" -> PoiCategory.ZOO
    category == "water_park" -> PoiCategory.PARCHI_ACQUATICI
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
    "amenity=compressed_air", "amenity=fish_spa", "amenity=music_school", "amenity=surf_school",
    "amenity=social_facility", "amenity=language_school", "amenity=animal_shelter", "amenity=mini_storage",
    "amenity=parcel_locker", "amenity=watering_place", "amenity=public_bookcase",
)

// Servizi che in OSM di solito non hanno un nome (bagni, bancomat, parcheggi...): restano sulla mappa
// anche senza, a differenza di tutti gli altri POI.
private val namelessOnMapCategories = setOf(
    PoiCategory.BAGNI_PUBBLICI, PoiCategory.BANCOMAT, PoiCategory.PARCHEGGIO, PoiCategory.CARBURANTE,
    PoiCategory.RICARICA, PoiCategory.FARMACIA, PoiCategory.OSPEDALE, PoiCategory.TAXI, PoiCategory.UFFICIO_POSTALE,
    PoiCategory.SERVIZI_CAMPER, PoiCategory.RIPARI, PoiCategory.RIPARAZIONE_BICI, PoiCategory.PARCHEGGIO_DISABILI,
)

// Come sopra, per tipi di categorie che hanno anche POI da nascondere senza nome (tavoli da picnic
// e noleggi d'auto senza nome restano nascosti). Servono alle modalita' d'uso (escursionismo, bici).
private val namelessOnMapTags = setOf("amenity=fountain", "tourism=viewpoint", "amenity=bicycle_rental", "tourism=picnic_site")

private val detailCategories = setOf(
    PoiCategory.CIBO_BEVANDE, PoiCategory.ALLOGGIO, PoiCategory.AMBASCIATA_CONSOLATO, PoiCategory.FARMACIA,
    PoiCategory.OSPEDALE, PoiCategory.AMBULATORI, PoiCategory.NEGOZI,
)
private val contactCategories = setOf(PoiCategory.ALLOGGIO, PoiCategory.AMBASCIATA_CONSOLATO)

/**
 * true per i POI con orari e indirizzo, quando OSM li indica: cibo e bevande, alloggi, ambasciate e consolati,
 * farmacie, ospedali, ambulatori e negozi (supermercati compresi). Senza nome, per questi la pipeline usa il marchio.
 */
fun poiHasDetails(category: String, osmTag: String): Boolean = poiCategoryOf(category, osmTag) in detailCategories

/** true per i POI con sito ed email, quando OSM li indica: alloggi (per prenotare), ambasciate e consolati. */
fun poiHasContacts(category: String, osmTag: String): Boolean = poiCategoryOf(category, osmTag) in contactCategories

/** false se OSM non ha un nome: la pipeline mette allora il valore del tag (es. "toilets"). */
fun poiHasName(name: String, osmTag: String): Boolean = name != osmTag.substringAfter("=")

/**
 * true per i POI da non mostrare sulla mappa: i tipi sopra, quelli senza una categoria precisa (ALTRO), i parcheggi
 * privati, i cartelli informativi e quelli senza nome, tranne i servizi di namelessOnMapCategories e i tipi di
 * namelessOnMapTags.
 */
fun isPoiHiddenOnMap(name: String, category: String, osmTag: String): Boolean {
    val poiCategory = poiCategoryOf(category, osmTag)
    // Gli stalli per disabili si vedono (con "In sedia a rotelle", deciso dall'app) anche se amenity=parking_space
    // e' nascosto: le app vecchie, che non conoscono "parking_disabled", li nascondono ancora.
    return (osmTag in hiddenOnMapTags && category != "parking_disabled") ||
        // Lista bianca: OSM ha centinaia di tipi rari (centri civici, tribunali, college...), ogni regione ne porta di
        // nuovi; si mostra solo quello che ha una categoria, il resto non si pubblica.
        poiCategory == PoiCategory.ALTRO ||
        poiCategory == PoiCategory.PARCHEGGIO_PRIVATO ||
        (osmTag == "tourism=information" && category != "information_office") ||
        (!poiHasName(name, osmTag) && poiCategory !in namelessOnMapCategories && osmTag !in namelessOnMapTags)
}

/** Pacchetto in cui la pipeline pubblica un POI (vedi [poiPackageOf]). */
enum class PoiPackage { BASE, EXTRA }

// POI nascosti sulla mappa ma utili a chi li vuole: pacchetto "extra", scaricato solo su richiesta.
// Tutti gli altri POI nascosti non si pubblicano.
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
