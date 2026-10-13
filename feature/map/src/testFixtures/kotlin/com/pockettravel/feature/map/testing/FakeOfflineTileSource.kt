package com.pockettravel.feature.map.testing

import com.pockettravel.feature.map.MapBounds
import com.pockettravel.feature.map.MapSourceKind
import com.pockettravel.feature.map.OfflineTileSource

/** [OfflineTileSource] senza file: i rettangoli e le sorgenti si impostano per regione. */
class FakeOfflineTileSource : OfflineTileSource {
    val bounds = mutableMapOf<String, MapBounds>()
    val kinds = mutableMapOf<String, MapSourceKind>()

    override fun styleJson(regionId: String, dark: Boolean, language: String, worldFallback: Boolean): String = "{}"
    override fun navigationStyleJson(regionIds: List<String>, dark: Boolean, language: String): String = "{}"
    override fun sourceKind(regionId: String): MapSourceKind = kinds[regionId] ?: MapSourceKind.NONE
    override fun regionBounds(regionId: String): MapBounds? = bounds[regionId]
}
