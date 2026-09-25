package com.pockettravel.core.data

import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.poi.isPoiHiddenOnMap
import com.pockettravel.core.poi.poiCategoryOf
import com.pockettravel.core.poi.poiHasName

// Le regole stanno in core:poi, condiviso con la pipeline (generatePoi decide con le stesse regole
// quali POI pubblicare): qui solo le scorciatoie sul modello dell'app.

/** Macro-categoria della mappa (icona + filtro), vedi [poiCategoryOf]. */
fun Poi.poiCategory(): PoiCategory = poiCategoryOf(category, osmTag)

/** false se OSM non ha un nome: la pipeline mette allora il valore del tag (es. "toilets"). */
fun Poi.hasName(): Boolean = poiHasName(name, osmTag)

/** true per i POI da non mostrare sulla mappa, vedi [isPoiHiddenOnMap]. */
fun Poi.isHiddenOnMap(): Boolean = isPoiHiddenOnMap(name, category, osmTag)
