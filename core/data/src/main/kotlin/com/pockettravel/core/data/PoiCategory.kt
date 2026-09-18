package com.pockettravel.core.data

enum class PoiCategory {
    ALLOGGIO,
    CIBO_BEVANDE,
    NEGOZI,
    ATTRAZIONI,
    AMBASCIATA_CONSOLATO,
    ALTRO,
}

private val accommodationValues = setOf("hotel", "guest_house", "hostel", "motel", "apartment", "camp_site", "chalet")
private val foodDrinkValues = setOf("restaurant", "cafe", "bar", "pub", "fast_food", "food_court", "ice_cream", "biergarten")
private val attractionValues = setOf("attraction", "museum", "viewpoint", "gallery", "artwork", "zoo", "theme_park", "park", "garden")

/**
 * Raggruppa il tag OSM grezzo di un POI (Poi.category/osmTag, uno dei tanti valori possibili di
 * amenity/shop/tourism/leisure/historic — vedi poiTagKeys in GeneratePoi.kt) in una manciata di
 * macro-categorie per la mappa (icona + filtro), sullo stesso spirito delle 5 GuideCategory
 * pratiche già in uso per le guide testuali.
 */
fun Poi.poiCategory(): PoiCategory = when {
    category == "embassy" -> PoiCategory.AMBASCIATA_CONSOLATO
    osmTag.startsWith("shop=") -> PoiCategory.NEGOZI
    osmTag.startsWith("historic=") -> PoiCategory.ATTRAZIONI
    category in accommodationValues -> PoiCategory.ALLOGGIO
    category in foodDrinkValues -> PoiCategory.CIBO_BEVANDE
    category in attractionValues -> PoiCategory.ATTRAZIONI
    else -> PoiCategory.ALTRO
}
