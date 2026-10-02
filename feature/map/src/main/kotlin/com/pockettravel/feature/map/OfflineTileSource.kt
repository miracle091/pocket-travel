package com.pockettravel.feature.map

import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.WorldMapStore
import java.io.File

interface OfflineTileSource {
    /** [language]: lingua dell'interfaccia ("it"/"en"), per i nomi di localita' e strade (vedi labelField).
     * [worldFallback]: sotto la regione anche i confini dei paesi inclusi nell'app e, con la rete,
     * il mondo online, per quando la vista esce dal riquadro scaricato (navigazione). */
    fun styleJson(regionId: String, dark: Boolean = false, language: String = "it", worldFallback: Boolean = false): String

    /** Stile della navigazione fra [regionIds] (almeno una): una sorgente per regione con la mappa
     * installata, sopra il ripiego fuori regione. Con una sola regione e' come [styleJson] con
     * worldFallback, ma le sorgenti si chiamano `region-<id>`. */
    fun navigationStyleJson(regionIds: List<String>, dark: Boolean = false, language: String = "it"): String

    /** Sorgente scelta da [styleJson] per la regione, per decidere se e cosa mostrare nella
     * barra "Scarica la mappa" (RegionHubScreen). */
    fun sourceKind(regionId: String): MapSourceKind

    /** Riquadro della mappa della regione installata (completa, altrimenti anteprima); null senza
     * mappa locale. La navigazione lo usa per sapere se la posizione e' fuori dai dati scaricati. */
    fun regionBounds(regionId: String): MapBounds?
}

/** Sorgente della mappa di base, in ordine di preferenza: mappa completa della regione, sua
 * anteprima, mondo online (solo se c'e' rete), nessuna (solo sfondo con reticolo). */
enum class MapSourceKind { FULL, PREVIEW, ONLINE_WORLD, NONE }

// MapLibre Native gestisce il protocollo pmtiles:// nativamente su Android (nessun parser
// o server locale da scrivere): basta un url "pmtiles://file://<percorso-assoluto>" in una
// source vettoriale dello style. Richiede un file reale su storage privato dell'app —
// pmtiles://asset:// (file in assets/) non è supportato perché l'asset manager di Android
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

    override fun regionBounds(regionId: String): MapBounds? {
        val file = regionStorage.versionedPmtiles(regionId, RegionStorage.MAP_FILE)
            ?: regionStorage.versionedPmtiles(regionId, RegionStorage.PREVIEW_FILE)
            ?: return null
        return runCatching { pmtilesHeaderBounds(readPmtilesHeader(file)) }.getOrNull()
    }

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
        // Solo con una sorgente locale (mappa completa o anteprima): il mondo online non ha civici.
        val addresses = if (resolved.kind != MapSourceKind.ONLINE_WORLD) {
            regionStorage.versionedPmtiles(regionId, RegionStorage.ADDRESSES_FILE)
        } else {
            null
        }
        return RegionSource(sourceId, layerSuffix, requireNotNull(resolved.url), resolved.maxZoom, addresses?.let(::pmtilesUrl))
    }
}

// Sorgenti e strati di una o piu' regioni (navigazione). Gli strati sono in ordine di disegno, uno per
// volta per tutte le regioni (acqua di A, acqua di B, edifici di A, ...), cosi' le strade di una non
// finiscono sotto la terra dell'altra; i civici, sempre in cima, alla fine.
internal fun regionsStyle(regions: List<RegionSource>, dark: Boolean, label: String, fallback: WorldFallbackStyle?): String {
    val palette = if (dark) MapPalette.Dark else MapPalette.Light
    // I nomi dei source-layer ("water", "roads", "buildings", "places") sono quelli dello
    // schema "basemap" ufficiale Protomaps (docs.protomaps.com/basemaps/layers), non piu'
    // quelli del nostro Shortbread profile (tools/data-pipeline/maptiles,
    // ShortbreadProfile.kt) — il map.pmtiles installato oggi e' estratto lato device dalla
    // build whole-planet Protomaps (PmtilesExtractor, core:sync), non generato dalla nostra
    // pipeline Planetiler. La pipeline locale resta solo per i suoi test, con uno schema
    // diverso. Stesso schema per l'anteprima regionale e per il mondo online: entrambi
    // estratti dalla stessa build Protomaps.
    //
    // "attribution" sulla source: l'ODbL 1.0 impone di attribuire i dati
    // OpenStreetMap. Essendo tile locali (pmtiles://), non c'è un TileJSON remoto da cui
    // MapLibre potrebbe altrimenti leggerla: va dichiarata qui. Il controllo attribuzioni
    // di MapLibre Android è attivo di default e la mostra automaticamente (icona "i").
    //
    // "minzoom"/"maxzoom" sulla source: per la mappa completa DEVONO combaciare con
    // MAP_MIN_ZOOM/MAP_MAX_ZOOM di build-region.sh (0/14), lo stesso range con cui
    // PmtilesExtractor scarica le tile sul device. Senza dichiararli qui, MapLibre assume che
    // esistano tile fino a z22 e le richiede davvero quando l'utente zooma oltre 14;
    // PmtilesExtractor non le ha mai scaricate, quindi tornano vuote e la mappa mostra un buco
    // (bug osservato: pezzi di mappa "spariscono" zoomando, pur essendo visibili a livello
    // globale). Dichiarare maxzoom=14 dice a MapLibre di fermare le richieste li' e ri-scalare
    // (overzoom) l'ultima tile disponibile, come fa Google Maps quando non ha piu' dettaglio.
    // Per l'anteprima e per il mondo online il maxzoom e' piu' basso e viene letto dall'header
    // PMTiles (byte 101, vedi pmtilesHeaderMaxZoom) o da WorldMapStore.worldMapMaxZoom().
    //
    // "boundaries": confini dal layer omonimo di Protomaps (kind_detail = admin_level OSM):
    // nazionali (<= 2) continui e piu' marcati, regionali/provinciali (3-4) tratteggiati da z5.
    //
    // "glyphs": i font per le etichette (nomi di strade/localita') vanno serviti in locale,
    // mai da rete (nessun hosting proprio, vedi CLAUDE.md/memoria progetto) — bundle di un
    // solo fontstack (Klokantech Noto Sans Regular di openmaptiles/fonts, licenza OFL) con i range
    // delle scritture che MapLibre disegna senza shaping complesso: latino esteso (lettone, polacco,
    // turco, vietnamita...), greco, cirillico, armeno, ebraico, arabo, thai, georgiano e la
    // punteggiatura tipografica (U+2000-21FF). Esclusi CJK (centinaia di range, decine di MB) e le
    // scritture indiane (MapLibre non ne compone le legature): li' restano name:it/name:en se ci sono.
    // Il template non contiene "{fontstack}" apposta: con un solo font non serve
    // sostituirlo, ed evita ogni dubbio su come MapLibre codifichi gli spazi nel nome del
    // font quando lo inserisce nell'URL "asset://" (asset:// risolve dentro gli assets
    // dell'APK, stesso meccanismo di brouter-profile/, non pmtiles:// che invece richiede
    // letture a range di byte non supportate dall'asset manager).
    //
    // Palette e gerarchia via "kind"/"kind_detail" (schema Protomaps) invece di un singolo
    // stile piatto per ogni layer: piu' vicino a Google Maps (strade principali gialle e
    // piu' spesse, strade minori bianche con leggera "casing" grigia, edifici con contorno),
    // con "interpolate"/"zoom" per ispessire le linee avvicinandosi.
    //
    // "text-field" con coalesce name:it -> name:en -> name (in inglese name:en -> name, vedi
    // labelField) invece del solo "name": i nomi di
    // strade/localita' sono nomi propri, non traducibili ("Via Roma" resta "Via Roma" anche
    // su Google Maps in italiano) — l'unica localizzazione sensata e' preferire la variante
    // gia' mappata su OSM (rara per name:it, molto piu' comune name:en, es. la
    // romanizzazione dei nomi giapponesi) e usare il nome locale solo come ultima risorsa.
    // Per le regioni a caratteri latini (Italia, San Marino, Andorra, Stati Uniti) il
    // risultato e' identico a prima: name:it/name:en quasi mai presenti su strade locali,
    // si ricade sempre su "name". Per il Giappone conta di piu': i caratteri kanji non
    // renderizzerebbero comunque (il font bundlato copre solo il range latino), quindi senza
    // questo fallback quelle etichette sarebbero vuote anche quando OSM ha gia' la
    // romanizzazione pronta in name:en.
    //
    // Con il ripiego (worldFallback) lo sfondo e' il mare e le terre le disegnano, dal basso:
    // i confini Natural Earth, il mondo online e lo strato "earth" della regione, preciso sulle
    // coste; fuori dal riquadro scaricato restano cosi' i paesi invece dello sfondo vuoto.
    val baseLayers = if (fallback != null) {
        fallback.layers
    } else {
        """{ "id": "background", "type": "background", "paint": { "background-color": "${palette.background}" } }"""
    }
    // Lo strato "earth" delle regioni sta dopo il ripiego (con il ripiego: vedi sopra).
    // Strati intercalati per tipo (acqua di tutte, poi edifici...), ma prima tutta la pila delle anteprime
    // e poi quella delle mappe complete: dove i riquadri si sovrappongono la mappa completa copre acqua
    // semplificata e strade generalizzate dell'anteprima, invece di mescolarle.
    val layers = regions.partition { it.maxZoom < FULL_MAP_MAX_ZOOM }.toList().filter { it.isNotEmpty() }.flatMap { group ->
        val perRegion = group.map { regionLayers(it, palette, label, withEarth = fallback != null) }
        perRegion.first().indices.flatMap { index -> perRegion.map { it[index] } }
    }
    val addressesLayers = regions.filter { it.addressesUrl != null }.map { addressesLayer(it, palette) }
    val regionSources = regions.joinToString(",\n                ") { region ->
        val addresses = region.addressesUrl?.let {
            """,
            "${region.addressesSourceId}": { "type": "vector", "url": "$it", "attribution": "© OpenStreetMap contributors", "minzoom": 14, "maxzoom": 14 }"""
        }.orEmpty()
        """"${region.sourceId}": {
              "type": "vector",
              "url": "${region.url}",
              "attribution": "© OpenStreetMap contributors",
              "minzoom": 0,
              "maxzoom": ${region.maxZoom}
            }$addresses"""
    }
    val fallbackSources = fallback?.let { ",\n                ${it.sources}" }.orEmpty()
    return """
        {
          "version": 8,
          "glyphs": "asset://fonts/NotoSansRegular/{range}.pbf",
          "sources": {
            $regionSources$fallbackSources
          },
          "layers": [
            ${(listOf(baseLayers) + layers + addressesLayers).joinToString(",\n                ")}
          ]
        }
    """.trimIndent()
}

private fun regionLayers(r: RegionSource, palette: MapPalette, label: String, withEarth: Boolean): List<String> = listOfNotNull(
    if (withEarth) """{ "id": "earth${r.layerSuffix}", "type": "fill", "source": "${r.sourceId}", "source-layer": "earth", "paint": { "fill-color": "${palette.background}" } }""" else null,
    """{ "id": "water${r.layerSuffix}", "type": "fill", "source": "${r.sourceId}", "source-layer": "water", "paint": { "fill-color": "${palette.water}" } }""",
    """{ "id": "buildings${r.layerSuffix}", "type": "fill", "source": "${r.sourceId}", "source-layer": "buildings", "paint": { "fill-color": "${palette.building}" } }""",
    """{ "id": "buildings_outline${r.layerSuffix}", "type": "line", "source": "${r.sourceId}", "source-layer": "buildings", "minzoom": 15, "paint": { "line-color": "${palette.buildingOutline}", "line-width": 0.5 } }""",
    """{ "id": "roads_path${r.layerSuffix}", "type": "line", "source": "${r.sourceId}", "source-layer": "roads", "filter": ["==", "kind", "path"], "layout": { "line-cap": "round" }, "paint": { "line-color": "${palette.path}", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.5, 18, 2], "line-dasharray": [2, 2] } }""",
    """{ "id": "roads_minor_casing${r.layerSuffix}", "type": "line", "source": "${r.sourceId}", "source-layer": "roads", "filter": ["in", "kind", "minor_road", "other"], "layout": { "line-cap": "round", "line-join": "round" }, "paint": { "line-color": "${palette.minorCasing}", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.6, 18, 8] } }""",
    """{ "id": "roads_minor${r.layerSuffix}", "type": "line", "source": "${r.sourceId}", "source-layer": "roads", "filter": ["in", "kind", "minor_road", "other"], "layout": { "line-cap": "round", "line-join": "round" }, "paint": { "line-color": "${palette.minorRoad}", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.3, 18, 5] } }""",
    """{ "id": "roads_major${r.layerSuffix}", "type": "line", "source": "${r.sourceId}", "source-layer": "roads", "filter": ["in", "kind", "highway", "major_road"], "layout": { "line-cap": "round", "line-join": "round" }, "paint": { "line-color": "${palette.majorRoad}", "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1, 18, 10] } }""",
    """{ "id": "boundaries_region${r.layerSuffix}", "type": "line", "source": "${r.sourceId}", "source-layer": "boundaries", "filter": ["all", [">=", "kind_detail", 3], ["<=", "kind_detail", 4]], "minzoom": 5, "layout": { "line-join": "round" }, "paint": { "line-color": "${palette.boundaryRegion}", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 0.6, 14, 1.5], "line-dasharray": [3, 2] } }""",
    """{ "id": "boundaries_country${r.layerSuffix}", "type": "line", "source": "${r.sourceId}", "source-layer": "boundaries", "filter": ["<=", "kind_detail", 2], "layout": { "line-join": "round", "line-cap": "round" }, "paint": { "line-color": "${palette.boundaryCountry}", "line-width": ["interpolate", ["linear"], ["zoom"], 2, 0.8, 14, 2.5] } }""",
    """{ "id": "places_locality${r.layerSuffix}", "type": "symbol", "source": "${r.sourceId}", "source-layer": "places", "filter": ["in", "kind", "locality", "macrohood", "neighbourhood"], "minzoom": 10, "layout": { "text-field": $label, "text-font": ["NotoSansRegular"], "text-size": 13 }, "paint": { "text-color": "${palette.placeText}", "text-halo-color": "${palette.placeHalo}", "text-halo-width": 1.2 } }""",
    """{ "id": "roads_labels_major${r.layerSuffix}", "type": "symbol", "source": "${r.sourceId}", "source-layer": "roads", "filter": ["in", "kind", "highway", "major_road"], "minzoom": 11, "layout": { "symbol-placement": "line", "text-field": $label, "text-font": ["NotoSansRegular"], "text-size": 12 }, "paint": { "text-color": "${palette.majorLabel}", "text-halo-color": "${palette.majorLabelHalo}", "text-halo-width": 1 } }""",
    """{ "id": "roads_labels_minor${r.layerSuffix}", "type": "symbol", "source": "${r.sourceId}", "source-layer": "roads", "filter": ["in", "kind", "minor_road", "other"], "minzoom": 15, "layout": { "symbol-placement": "line", "text-field": $label, "text-font": ["NotoSansRegular"], "text-size": 11 }, "paint": { "text-color": "${palette.minorLabel}", "text-halo-color": "${palette.minorLabelHalo}", "text-halo-width": 1.2 } }""",
)

private fun addressesLayer(r: RegionSource, palette: MapPalette): String =
    """{ "id": "${r.addressesSourceId}", "type": "symbol", "source": "${r.addressesSourceId}", "source-layer": "addresses", "minzoom": 17, "layout": { "text-field": ["get", "number"], "text-font": ["NotoSansRegular"], "text-size": 11 }, "paint": { "text-color": "${palette.addressText}", "text-halo-color": "${palette.building}", "text-halo-width": 1.2 } }"""

/** Una regione dello stile: id della sorgente ("region", o "region-<id>" in navigazione), suffisso degli id
 * degli strati (per tenerli distinti fra regioni), url e maxzoom della mappa, url dei civici se ci sono. */
internal class RegionSource(val sourceId: String, val layerSuffix: String, val url: String, val maxZoom: Int, val addressesUrl: String?) {
    val addressesSourceId: String get() = "addresses$layerSuffix"
}

// Sorgente scelta e sua url/maxzoom (null/0 solo per NONE): estratto in una funzione pura
// (nessun file, nessuna rete) per poter testare l'ordine di scelta senza RegionStorage/
// WorldMapStore/ConnectivityChecker veri.
internal data class ResolvedSource(val kind: MapSourceKind, val url: String?, val maxZoom: Int)

// DEVE combaciare con MAP_MAX_ZOOM di build-region.sh (0/14), vedi il commento in styleJson; la mappa leggera
// dichiara 13 nel suo header. Le mappe sotto questo zoom stanno nella pila delle anteprime (regionsStyle): dove si
// sovrappongono a una mappa completa dettagliata, quella le copre.
private const val FULL_MAP_MAX_ZOOM = 14

// Nome da mostrare per localita' e strade: in italiano name:it -> name:en -> name, in inglese name:en -> name.
internal fun labelField(language: String): String =
    if (language == "en") """["coalesce", ["get", "name:en"], ["get", "name"]]""" else """["coalesce", ["get", "name:it"], ["get", "name:en"], ["get", "name"]]"""

internal fun selectSource(
    fullMapUrl: String?,
    // Dall'header del file: 14, o 13 per la mappa leggera (MapDetail.LIGHT di PmtilesExtractor).
    fullMapMaxZoom: Int = FULL_MAP_MAX_ZOOM,
    previewUrl: String?,
    previewMaxZoom: Int,
    worldMapUrl: String?,
    worldMapMaxZoom: Int,
    online: Boolean,
): ResolvedSource = when {
    fullMapUrl != null -> ResolvedSource(MapSourceKind.FULL, fullMapUrl, fullMapMaxZoom)
    previewUrl != null -> ResolvedSource(MapSourceKind.PREVIEW, previewUrl, previewMaxZoom)
    online && worldMapUrl != null -> ResolvedSource(MapSourceKind.ONLINE_WORLD, "pmtiles://$worldMapUrl", worldMapMaxZoom)
    else -> ResolvedSource(MapSourceKind.NONE, null, 0)
}

/** Sorgenti e strati del ripiego fuori regione, gia' in JSON (senza virgole esterne). */
internal data class WorldFallbackStyle(val sources: String, val layers: String)

// Mondo online sotto la regione solo se la regione ha una mappa sua (completa o anteprima): con
// ONLINE_WORLD e' gia' la sorgente principale, con NONE non c'e' rete o non c'e' il mondo.
internal fun worldFallbackUrl(kind: MapSourceKind, worldMapUrl: String?, online: Boolean): String? =
    if ((kind == MapSourceKind.FULL || kind == MapSourceKind.PREVIEW) && online && worldMapUrl != null) {
        "pmtiles://$worldMapUrl"
    } else {
        null
    }

private fun worldFallbackStyle(palette: MapPalette, worldUrl: String?, worldMaxZoom: Int): WorldFallbackStyle =
    worldFallbackStyle(palette.water, palette.background, palette.boundaryCountry, palette.majorRoad, worldUrl, worldMaxZoom)

// Confini Natural Earth 1:50m inclusi nell'app (gli stessi di WorldMap), sempre; il mondo online
// (stessa build Protomaps, zoom bassi) solo con [worldUrl]: terre, acqua, strade principali e confini.
internal fun worldFallbackStyle(
    water: String,
    land: String,
    border: String,
    majorRoad: String,
    worldUrl: String?,
    worldMaxZoom: Int,
): WorldFallbackStyle {
    val countriesSource = """"countries": { "type": "geojson", "data": "asset://world/countries.geojson", "attribution": "Natural Earth" }"""
    val countriesLayers = """{ "id": "fallback_sea", "type": "background", "paint": { "background-color": "$water" } },
                { "id": "fallback_countries", "type": "fill", "source": "countries", "paint": { "fill-color": "$land", "fill-outline-color": "$border" } }"""
    if (worldUrl == null) return WorldFallbackStyle(countriesSource, countriesLayers)
    return WorldFallbackStyle(
        sources = """$countriesSource,
                "world": { "type": "vector", "url": "$worldUrl", "attribution": "© OpenStreetMap contributors", "minzoom": 0, "maxzoom": $worldMaxZoom }""",
        layers = """$countriesLayers,
                { "id": "fallback_world_earth", "type": "fill", "source": "world", "source-layer": "earth", "paint": { "fill-color": "$land" } },
                { "id": "fallback_world_water", "type": "fill", "source": "world", "source-layer": "water", "paint": { "fill-color": "$water" } },
                { "id": "fallback_world_roads", "type": "line", "source": "world", "source-layer": "roads", "filter": ["in", "kind", "highway", "major_road"], "paint": { "line-color": "$majorRoad", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 0.5, 14, 4] } },
                { "id": "fallback_world_boundaries", "type": "line", "source": "world", "source-layer": "boundaries", "filter": ["<=", "kind_detail", 2], "paint": { "line-color": "$border", "line-width": 1 } }""",
    )
}

// L'header PMTiles v3 e' fisso a 127 byte; il byte 101 (0-based) e' il max_zoom dell'archivio
// (specs.protomaps.dev/pmtiles/spec-v3). Letto qui invece che tenuto in un campo del manifest
// perche' l'anteprima puo' essere rigenerata con uno zoom diverso da build-region.sh (tetto di
// peso) e l'header e' l'unica fonte sempre corretta.
private const val PMTILES_HEADER_SIZE = 127
private const val PMTILES_MAX_ZOOM_OFFSET = 101

internal fun pmtilesHeaderMaxZoom(file: File): Int = pmtilesHeaderMaxZoom(readPmtilesHeader(file))

private fun readPmtilesHeader(file: File): ByteArray = file.inputStream().use { stream ->
    // Non InputStream.readNBytes: richiede API 33, minSdk del modulo e' 26.
    val header = ByteArray(PMTILES_HEADER_SIZE)
    var read = 0
    while (read < header.size) {
        val count = stream.read(header, read, header.size - read)
        if (count == -1) break
        read += count
    }
    header
}

internal fun pmtilesHeaderMaxZoom(header: ByteArray): Int {
    require(header.size >= PMTILES_HEADER_SIZE) { "Header PMTiles troncato: ${header.size} byte" }
    return header[PMTILES_MAX_ZOOM_OFFSET].toInt() and 0xFF
}

/** Riquadro dei dati della mappa installata, in gradi. */
data class MapBounds(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    fun contains(latitude: Double, longitude: Double): Boolean =
        longitude in minLon..maxLon && latitude in minLat..maxLat
}

// Byte 102-117 dell'header: min_lon, min_lat, max_lon, max_lat in gradi x 10^7, int32
// little-endian (spec v3; PmtilesWriter li scrive dal riquadro della regione).
private const val PMTILES_BOUNDS_OFFSET = 102

internal fun pmtilesHeaderBounds(header: ByteArray): MapBounds {
    require(header.size >= PMTILES_HEADER_SIZE) { "Header PMTiles troncato: ${header.size} byte" }
    val buffer = java.nio.ByteBuffer.wrap(header, PMTILES_BOUNDS_OFFSET, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val e7 = 10_000_000.0
    return MapBounds(buffer.int / e7, buffer.int / e7, buffer.int / e7, buffer.int / e7)
}

// Colori dello stile. Light: la palette storica in stile Google Maps. Dark: stessa gerarchia
// (strade principali ambrate, minori piu' chiare dello sfondo, acqua blu scuro) su fondo scuro,
// per non abbagliare quando l'app e' in tema scuro; etichette chiare con alone dello sfondo.
private data class MapPalette(
    val background: String,
    val water: String,
    val building: String,
    val buildingOutline: String,
    val path: String,
    val minorCasing: String,
    val minorRoad: String,
    val majorRoad: String,
    val placeText: String,
    val placeHalo: String,
    val majorLabel: String,
    val majorLabelHalo: String,
    val minorLabel: String,
    val minorLabelHalo: String,
    val boundaryCountry: String,
    val boundaryRegion: String,
    val addressText: String,
) {
    companion object {
        val Light = MapPalette(
            background = "#f2efe9", water = "#a7cfe8", building = "#ddd6c9", buildingOutline = "#c7bfae",
            path = "#b7ac9a", minorCasing = "#d6d2c8", minorRoad = "#ffffff", majorRoad = "#f7c164",
            placeText = "#3f3b33", placeHalo = "#ffffff", majorLabel = "#7a5c1e", majorLabelHalo = "#f7c164",
            minorLabel = "#5a5346", minorLabelHalo = "#ffffff",
            boundaryCountry = "#8f8a9e", boundaryRegion = "#b9b4c4",
            addressText = "#4a443a",
        )
        val Dark = MapPalette(
            background = "#1d2226", water = "#17344a", building = "#2a3036", buildingOutline = "#363d44",
            path = "#5b646c", minorCasing = "#262c31", minorRoad = "#3b434b", majorRoad = "#8a6a34",
            placeText = "#e2e4e6", placeHalo = "#1d2226", majorLabel = "#f3d49a", majorLabelHalo = "#1d2226",
            minorLabel = "#c3c8cc", minorLabelHalo = "#1d2226",
            boundaryCountry = "#8d96a3", boundaryRegion = "#5b646f",
            addressText = "#c3c8cc",
        )
    }
}
