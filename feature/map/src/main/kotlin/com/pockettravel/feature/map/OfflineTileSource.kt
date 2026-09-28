package com.pockettravel.feature.map

import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.WorldMapStore
import java.io.File

interface OfflineTileSource {
    /** [language]: lingua dell'interfaccia ("it"/"en"), per i nomi di localita' e strade (vedi labelField). */
    fun styleJson(regionId: String, dark: Boolean = false, language: String = "it"): String

    /** Sorgente scelta da [styleJson] per la regione, per decidere se e cosa mostrare nella
     * barra "Scarica la mappa" (RegionHubScreen). */
    fun sourceKind(regionId: String): MapSourceKind
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
            previewUrl = preview?.let(::pmtilesUrl),
            previewMaxZoom = preview?.let { pmtilesHeaderMaxZoom(it) } ?: 0,
            worldMapUrl = worldMapStore.worldMapUrl(),
            worldMapMaxZoom = worldMapStore.worldMapMaxZoom(),
            online = connectivityChecker.isOnline(),
        )
    }

    override fun sourceKind(regionId: String): MapSourceKind = resolve(regionId).kind

    override fun styleJson(regionId: String, dark: Boolean, language: String): String {
        val label = labelField(language)
        val palette = if (dark) MapPalette.Dark else MapPalette.Light
        val resolved = resolve(regionId)
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
        val mapUrl = requireNotNull(resolved.url)
        // Civici (pacchetto facoltativo, stesso nome di RegionStorage.ADDRESSES_FILE): solo punti,
        // tutti a z14, che MapLibre sovrazooma; etichette da zoom 17, dove non coprono le strade.
        // Solo con una sorgente locale (mappa completa o anteprima): il mondo online non ha civici.
        val addresses = if (resolved.kind != MapSourceKind.ONLINE_WORLD) {
            regionStorage.versionedPmtiles(regionId, RegionStorage.ADDRESSES_FILE)
        } else {
            null
        }
        val addressesSource = addresses?.let {
            """,
                "addresses": { "type": "vector", "url": "${pmtilesUrl(it)}", "attribution": "© OpenStreetMap contributors", "minzoom": 14, "maxzoom": 14 }"""
        }.orEmpty()
        val addressesLayer = addresses?.let {
            """,
                { "id": "addresses", "type": "symbol", "source": "addresses", "source-layer": "addresses", "minzoom": 17, "layout": { "text-field": ["get", "number"], "text-font": ["NotoSansRegular"], "text-size": 11 }, "paint": { "text-color": "${palette.addressText}", "text-halo-color": "${palette.building}", "text-halo-width": 1.2 } }"""
        }.orEmpty()

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
        // solo fontstack/range (Klokantech Noto Sans Regular, licenza OFL, solo range 0-255:
        // ASCII + Latin-1 Supplement, sufficiente per i nomi delle regioni oggi in catalogo).
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
        return """
            {
              "version": 8,
              "glyphs": "asset://fonts/NotoSansRegular/{range}.pbf",
              "sources": {
                "region": {
                  "type": "vector",
                  "url": "$mapUrl",
                  "attribution": "© OpenStreetMap contributors",
                  "minzoom": 0,
                  "maxzoom": ${resolved.maxZoom}
                }$addressesSource
              },
              "layers": [
                { "id": "background", "type": "background", "paint": { "background-color": "${palette.background}" } },
                { "id": "water", "type": "fill", "source": "region", "source-layer": "water", "paint": { "fill-color": "${palette.water}" } },
                { "id": "buildings", "type": "fill", "source": "region", "source-layer": "buildings", "paint": { "fill-color": "${palette.building}" } },
                { "id": "buildings_outline", "type": "line", "source": "region", "source-layer": "buildings", "minzoom": 15, "paint": { "line-color": "${palette.buildingOutline}", "line-width": 0.5 } },
                { "id": "roads_path", "type": "line", "source": "region", "source-layer": "roads", "filter": ["==", "kind", "path"], "layout": { "line-cap": "round" }, "paint": { "line-color": "${palette.path}", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.5, 18, 2], "line-dasharray": [2, 2] } },
                { "id": "roads_minor_casing", "type": "line", "source": "region", "source-layer": "roads", "filter": ["in", "kind", "minor_road", "other"], "layout": { "line-cap": "round", "line-join": "round" }, "paint": { "line-color": "${palette.minorCasing}", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.6, 18, 8] } },
                { "id": "roads_minor", "type": "line", "source": "region", "source-layer": "roads", "filter": ["in", "kind", "minor_road", "other"], "layout": { "line-cap": "round", "line-join": "round" }, "paint": { "line-color": "${palette.minorRoad}", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.3, 18, 5] } },
                { "id": "roads_major", "type": "line", "source": "region", "source-layer": "roads", "filter": ["in", "kind", "highway", "major_road"], "layout": { "line-cap": "round", "line-join": "round" }, "paint": { "line-color": "${palette.majorRoad}", "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1, 18, 10] } },
                { "id": "boundaries_region", "type": "line", "source": "region", "source-layer": "boundaries", "filter": ["all", [">=", "kind_detail", 3], ["<=", "kind_detail", 4]], "minzoom": 5, "layout": { "line-join": "round" }, "paint": { "line-color": "${palette.boundaryRegion}", "line-width": ["interpolate", ["linear"], ["zoom"], 5, 0.6, 14, 1.5], "line-dasharray": [3, 2] } },
                { "id": "boundaries_country", "type": "line", "source": "region", "source-layer": "boundaries", "filter": ["<=", "kind_detail", 2], "layout": { "line-join": "round", "line-cap": "round" }, "paint": { "line-color": "${palette.boundaryCountry}", "line-width": ["interpolate", ["linear"], ["zoom"], 2, 0.8, 14, 2.5] } },
                { "id": "places_locality", "type": "symbol", "source": "region", "source-layer": "places", "filter": ["in", "kind", "locality", "macrohood", "neighbourhood"], "minzoom": 10, "layout": { "text-field": $label, "text-font": ["NotoSansRegular"], "text-size": 13 }, "paint": { "text-color": "${palette.placeText}", "text-halo-color": "${palette.placeHalo}", "text-halo-width": 1.2 } },
                { "id": "roads_labels_major", "type": "symbol", "source": "region", "source-layer": "roads", "filter": ["in", "kind", "highway", "major_road"], "minzoom": 11, "layout": { "symbol-placement": "line", "text-field": $label, "text-font": ["NotoSansRegular"], "text-size": 12 }, "paint": { "text-color": "${palette.majorLabel}", "text-halo-color": "${palette.majorLabelHalo}", "text-halo-width": 1 } },
                { "id": "roads_labels_minor", "type": "symbol", "source": "region", "source-layer": "roads", "filter": ["in", "kind", "minor_road", "other"], "minzoom": 15, "layout": { "symbol-placement": "line", "text-field": $label, "text-font": ["NotoSansRegular"], "text-size": 11 }, "paint": { "text-color": "${palette.minorLabel}", "text-halo-color": "${palette.minorLabelHalo}", "text-halo-width": 1.2 } }$addressesLayer
              ]
            }
        """.trimIndent()
    }
}

// Sorgente scelta e sua url/maxzoom (null/0 solo per NONE): estratto in una funzione pura
// (nessun file, nessuna rete) per poter testare l'ordine di scelta senza RegionStorage/
// WorldMapStore/ConnectivityChecker veri.
internal data class ResolvedSource(val kind: MapSourceKind, val url: String?, val maxZoom: Int)

// DEVE combaciare con MAP_MAX_ZOOM di build-region.sh (0/14), vedi il commento in styleJson.
private const val FULL_MAP_MAX_ZOOM = 14

// Nome da mostrare per localita' e strade: in italiano name:it -> name:en -> name, in inglese name:en -> name.
internal fun labelField(language: String): String =
    if (language == "en") """["coalesce", ["get", "name:en"], ["get", "name"]]""" else """["coalesce", ["get", "name:it"], ["get", "name:en"], ["get", "name"]]"""

internal fun selectSource(
    fullMapUrl: String?,
    previewUrl: String?,
    previewMaxZoom: Int,
    worldMapUrl: String?,
    worldMapMaxZoom: Int,
    online: Boolean,
): ResolvedSource = when {
    fullMapUrl != null -> ResolvedSource(MapSourceKind.FULL, fullMapUrl, FULL_MAP_MAX_ZOOM)
    previewUrl != null -> ResolvedSource(MapSourceKind.PREVIEW, previewUrl, previewMaxZoom)
    online && worldMapUrl != null -> ResolvedSource(MapSourceKind.ONLINE_WORLD, "pmtiles://$worldMapUrl", worldMapMaxZoom)
    else -> ResolvedSource(MapSourceKind.NONE, null, 0)
}

// L'header PMTiles v3 e' fisso a 127 byte; il byte 101 (0-based) e' il max_zoom dell'archivio
// (specs.protomaps.dev/pmtiles/spec-v3). Letto qui invece che tenuto in un campo del manifest
// perche' l'anteprima puo' essere rigenerata con uno zoom diverso da build-region.sh (tetto di
// peso) e l'header e' l'unica fonte sempre corretta.
private const val PMTILES_HEADER_SIZE = 127
private const val PMTILES_MAX_ZOOM_OFFSET = 101

internal fun pmtilesHeaderMaxZoom(file: File): Int = file.inputStream().use { stream ->
    // Non InputStream.readNBytes: richiede API 33, minSdk del modulo e' 26.
    val header = ByteArray(PMTILES_HEADER_SIZE)
    var read = 0
    while (read < header.size) {
        val count = stream.read(header, read, header.size - read)
        if (count == -1) break
        read += count
    }
    pmtilesHeaderMaxZoom(header)
}

internal fun pmtilesHeaderMaxZoom(header: ByteArray): Int {
    require(header.size >= PMTILES_HEADER_SIZE) { "Header PMTiles troncato: ${header.size} byte" }
    return header[PMTILES_MAX_ZOOM_OFFSET].toInt() and 0xFF
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
