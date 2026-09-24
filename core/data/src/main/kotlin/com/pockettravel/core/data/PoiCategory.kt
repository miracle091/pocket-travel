package com.pockettravel.core.data

enum class PoiCategory {
    ALLOGGIO,
    CIBO_BEVANDE,
    NEGOZI,
    ATTRAZIONI,
    AMBASCIATA_CONSOLATO,
    POLIZIA,
    BAGNI_PUBBLICI,
    CARBURANTE,
    PARCHEGGIO,
    PARCHEGGIO_PRIVATO,
    TRENO,
    METRO,
    AUTOBUS,
    AEROPORTO,
    ALTRO,
}

private val accommodationValues = setOf("hotel", "guest_house", "hostel", "motel", "apartment", "camp_site", "chalet")
private val foodDrinkValues = setOf("restaurant", "cafe", "bar", "pub", "fast_food", "food_court", "ice_cream", "biergarten")
private val attractionValues = setOf("attraction", "museum", "viewpoint", "gallery", "artwork", "zoo", "theme_park", "park", "garden")

/**
 * Raggruppa il tag OSM grezzo di un POI (Poi.category/osmTag, uno dei tanti valori possibili di
 * amenity/shop/tourism/leisure/historic/railway/aeroway — vedi poiTagKeys in GeneratePoi.kt) in una manciata di
 * macro-categorie per la mappa (icona + filtro), sullo stesso spirito delle 5 GuideCategory
 * pratiche già in uso per le guide testuali.
 */
fun Poi.poiCategory(): PoiCategory = when {
    category == "embassy" -> PoiCategory.AMBASCIATA_CONSOLATO
    osmTag == "amenity=police" -> PoiCategory.POLIZIA
    osmTag == "amenity=toilets" -> PoiCategory.BAGNI_PUBBLICI
    osmTag == "amenity=fuel" -> PoiCategory.CARBURANTE
    // "parking_private": access privato, deciso dalla pipeline (GeneratePoi.kt).
    osmTag == "amenity=parking" && category == "parking_private" -> PoiCategory.PARCHEGGIO_PRIVATO
    osmTag == "amenity=parking" || osmTag == "amenity=parking_entrance" -> PoiCategory.PARCHEGGIO
    // "subway_station": railway=station della metropolitana, deciso dalla pipeline (GeneratePoi.kt).
    osmTag == "railway=station" && category == "subway_station" -> PoiCategory.METRO
    osmTag == "railway=station" || osmTag == "railway=halt" -> PoiCategory.TRENO
    osmTag == "amenity=bus_station" -> PoiCategory.AUTOBUS
    osmTag == "aeroway=aerodrome" -> PoiCategory.AEROPORTO
    osmTag.startsWith("shop=") -> PoiCategory.NEGOZI
    osmTag.startsWith("historic=") -> PoiCategory.ATTRAZIONI
    category in accommodationValues -> PoiCategory.ALLOGGIO
    category in foodDrinkValues -> PoiCategory.CIBO_BEVANDE
    category in attractionValues -> PoiCategory.ATTRAZIONI
    else -> PoiCategory.ALTRO
}

// Arredo urbano fitto e inutile per chi viaggia (cestini, panchine, tavoli da picnic, singoli stalli):
// coprirebbe i segnalini utili. ("waste_box" non e' un valore OSM standard ma compare nei dati.)
private val hiddenOnMapTags = setOf(
    "amenity=waste_basket", "amenity=waste_disposal", "amenity=waste_box", "amenity=bench", "amenity=parking_space",
    "leisure=picnic_table",
)

/** true per i POI da non mostrare sulla mappa. */
fun Poi.isHiddenOnMap(): Boolean = osmTag in hiddenOnMapTags
