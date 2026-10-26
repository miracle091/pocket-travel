package com.pockettravel.feature.map

import com.pockettravel.core.data.MapBounds
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.WorldMapStore
import com.pockettravel.core.data.pmtilesHeaderMaxZoom
import java.io.File

// MapLibre Native gestisce il protocollo pmtiles:// nativamente su Android (nessun parser
// o server locale da scrivere): basta un url "pmtiles://file://<percorso-assoluto>" in una
// source vettoriale dello style. Richiede un file reale su storage privato dell'app —
// pmtiles://asset:// (file in assets/) non e' supportato perche' l'asset manager di Android
// non offre letture a range di byte, che il formato PMTiles richiede.
class PmtilesTileSource(
    private val regionStorage: RegionStorage,
    private val worldMapStore: WorldMapStore,
    private val connectivityChecker: ConnectivityChecker,
) : OfflineTileSource {

    // Percorso diverso per ogni versione del file invece di map.pmtiles: vedi RegionStorage.versionedPmtiles.
    private fun pmtilesUrl(file: File) = "pmtiles://file://${file.absolutePath}"

    private fun resolve(regionId: String): ResolvedSource {
        val fullMap = regionStorage.versionedPmtiles(regionId, RegionStorage.MAP_FILE)
        val preview = regionStorage.versionedPmtiles(regionId, RegionStorage.PREVIEW_FILE)
        return selectSource(
            fullMapUrl = fullMap?.let(::pmtilesUrl),
            fullMapMaxZoom = fullMap?.let { pmtilesHeaderMaxZoom(it) } ?: FULL_MAP_MAX_ZOOM,
            previewUrl = preview?.let(::pmtilesUrl),
            previewMaxZoom = preview?.let { pmtilesHeaderMaxZoom(it) } ?: 0,
            worldMapUrl = worldMapStore.worldMapUrl(),
            worldMapMaxZoom = worldMapStore.worldMapMaxZoom(),
            online = connectivityChecker.isOnline(),
        )
    }

    override fun sourceKind(regionId: String): MapSourceKind = resolve(regionId).kind

    override fun regionBounds(regionId: String): MapBounds? = regionStorage.mapBounds(regionId)

    override fun styleJson(regionId: String, dark: Boolean, language: String, worldFallback: Boolean): String {
        val label = labelField(language)
        val palette = if (dark) MapPalette.Dark else MapPalette.Light
        val resolved = resolve(regionId)
        val fallback = if (worldFallback) worldFallbackFor(resolved.kind, palette) else null
        if (resolved.kind == MapSourceKind.NONE && fallback != null) {
            // Nessuna mappa della regione e niente rete: restano i confini dei paesi, meglio del reticolo.
            return """
                {
                  "version": 8,
                  "sources": {
                    ${fallback.sources}
                  },
                  "layers": [
                    ${fallback.layers}
                  ]
                }
            """.trimIndent()
        }
        if (resolved.kind == MapSourceKind.NONE) {
            // Nessuna sorgente disponibile (ne' locale ne' online): solo sfondo, con un reticolo
            // discreto (pattern "background-pattern", vedi MissingMapHint.kt) che faccia capire
            // che manca la mappa di base — i segnalini dei POI restano visibili sopra, disegnati
            // da MapScreen indipendentemente dallo stile.
            return """
                {
                  "version": 8,
                  "layers": [
                    { "id": "background", "type": "background", "paint": { "background-color": "${palette.background}" } },
                    { "id": "missing_map_hint", "type": "background", "paint": { "background-pattern": "$MISSING_MAP_HATCH_IMAGE" } }
                  ]
                }
            """.trimIndent()
        }
        return regionsStyle(listOf(regionSource("region", "", regionId, resolved)), dark, label, fallback)
    }

    /** Stile della navigazione: una sorgente per regione ([regionIds], di cui solo quelle con una mappa
     * locale; `region-<id>`) con gli stessi strati ripetuti, sopra il ripiego fuori regione come in
     * styleJson(worldFallback = true). Senza nessuna mappa locale e' lo stile della prima regione. */
    override fun navigationStyleJson(regionIds: List<String>, dark: Boolean, language: String): String {
        val local = regionIds.map { it to resolve(it) }.filter { it.second.kind == MapSourceKind.FULL || it.second.kind == MapSourceKind.PREVIEW }
        if (local.isEmpty()) return styleJson(regionIds.first(), dark, language, worldFallback = true)
        val palette = if (dark) MapPalette.Dark else MapPalette.Light
        val sources = local.map { (id, resolved) -> regionSource("region-$id", "-$id", id, resolved) }
        return regionsStyle(sources, dark, labelField(language), worldFallbackFor(local.first().second.kind, palette))
    }

    private fun worldFallbackFor(kind: MapSourceKind, palette: MapPalette): WorldFallbackStyle = worldFallbackStyle(
        palette,
        worldFallbackUrl(kind, worldMapStore.worldMapUrl(), connectivityChecker.isOnline()),
        worldMapStore.worldMapMaxZoom(),
    )

    private fun regionSource(sourceId: String, layerSuffix: String, regionId: String, resolved: ResolvedSource): RegionSource {
        // Civici (pacchetto facoltativo, stesso nome di RegionStorage.ADDRESSES_FILE): solo punti,
        // tutti a z14, che MapLibre sovrazooma; etichette da zoom 17, dove non coprono le strade.
        // Solo con una sorgente locale (mappa completa o mappa d'insieme): la mappa del mondo non ha civici.
        val addresses = if (resolved.kind != MapSourceKind.ONLINE_WORLD) {
            regionStorage.versionedPmtiles(regionId, RegionStorage.ADDRESSES_FILE)
        } else {
            null
        }
        return RegionSource(sourceId, layerSuffix, requireNotNull(resolved.url), resolved.maxZoom, addresses?.let(::pmtilesUrl))
    }
}
