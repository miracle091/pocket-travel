package com.pockettravel.feature.map

import java.io.File

interface OfflineTileSource {
    fun styleJson(regionId: String): String
}

// MapLibre Native gestisce il protocollo pmtiles:// nativamente su Android (nessun parser
// o server locale da scrivere): basta un url "pmtiles://file://<percorso-assoluto>" in una
// source vettoriale dello style. Richiede un file reale su storage privato dell'app —
// pmtiles://asset:// (file in assets/) non è supportato perché l'asset manager di Android
// non offre letture a range di byte, che il formato PMTiles richiede.
class PmtilesTileSource(private val regionsDir: File) : OfflineTileSource {

    override fun styleJson(regionId: String): String {
        val pmtilesPath = File(regionsDir, "$regionId/map.pmtiles").absolutePath

        // I nomi dei source-layer ("water", "roads", "buildings", "places") sono quelli dello
        // schema "basemap" ufficiale Protomaps (docs.protomaps.com/basemaps/layers), non piu'
        // quelli del nostro Shortbread profile (tools/data-pipeline/maptiles,
        // ShortbreadProfile.kt) — il map.pmtiles installato oggi e' estratto lato device dalla
        // build whole-planet Protomaps (PmtilesExtractor, core:sync), non generato dalla nostra
        // pipeline Planetiler. La pipeline locale resta solo per i suoi test, con uno schema
        // diverso.
        //
        // "attribution" sulla source: l'ODbL 1.0 impone di attribuire i dati
        // OpenStreetMap. Essendo tile locali (pmtiles://), non c'è un TileJSON remoto da cui
        // MapLibre potrebbe altrimenti leggerla: va dichiarata qui. Il controllo attribuzioni
        // di MapLibre Android è attivo di default e la mostra automaticamente (icona "i").
        //
        // "minzoom"/"maxzoom" sulla source: DEVONO combaciare con MAP_MIN_ZOOM/MAP_MAX_ZOOM di
        // build-region.sh (0/14), lo stesso range con cui PmtilesExtractor scarica le tile sul
        // device. Senza dichiararli qui, MapLibre assume che esistano tile fino a z22 e le
        // richiede davvero quando l'utente zooma oltre 14; PmtilesExtractor non le ha mai
        // scaricate, quindi tornano vuote e la mappa mostra un buco (bug osservato: pezzi di
        // mappa "spariscono" zoomando, pur essendo visibili a livello globale). Dichiarare
        // maxzoom=14 dice a MapLibre di fermare le richieste li' e ri-scalare (overzoom) l'ultima
        // tile disponibile, come fa Google Maps quando non ha piu' dettaglio.
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
        // "text-field" con coalesce name:it -> name:en -> name (non il solo "name"): i nomi di
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
                  "url": "pmtiles://file://$pmtilesPath",
                  "attribution": "© OpenStreetMap contributors",
                  "minzoom": 0,
                  "maxzoom": 14
                }
              },
              "layers": [
                { "id": "background", "type": "background", "paint": { "background-color": "#f2efe9" } },
                { "id": "water", "type": "fill", "source": "region", "source-layer": "water", "paint": { "fill-color": "#a7cfe8" } },
                { "id": "buildings", "type": "fill", "source": "region", "source-layer": "buildings", "paint": { "fill-color": "#ddd6c9" } },
                { "id": "buildings_outline", "type": "line", "source": "region", "source-layer": "buildings", "minzoom": 15, "paint": { "line-color": "#c7bfae", "line-width": 0.5 } },
                { "id": "roads_path", "type": "line", "source": "region", "source-layer": "roads", "filter": ["==", "kind", "path"], "layout": { "line-cap": "round" }, "paint": { "line-color": "#b7ac9a", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.5, 18, 2], "line-dasharray": [2, 2] } },
                { "id": "roads_minor_casing", "type": "line", "source": "region", "source-layer": "roads", "filter": ["in", "kind", "minor_road", "other"], "layout": { "line-cap": "round", "line-join": "round" }, "paint": { "line-color": "#d6d2c8", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.6, 18, 8] } },
                { "id": "roads_minor", "type": "line", "source": "region", "source-layer": "roads", "filter": ["in", "kind", "minor_road", "other"], "layout": { "line-cap": "round", "line-join": "round" }, "paint": { "line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.3, 18, 5] } },
                { "id": "roads_major", "type": "line", "source": "region", "source-layer": "roads", "filter": ["in", "kind", "highway", "major_road"], "layout": { "line-cap": "round", "line-join": "round" }, "paint": { "line-color": "#f7c164", "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1, 18, 10] } },
                { "id": "places_locality", "type": "symbol", "source": "region", "source-layer": "places", "filter": ["in", "kind", "locality", "macrohood", "neighbourhood"], "minzoom": 10, "layout": { "text-field": ["coalesce", ["get", "name:it"], ["get", "name:en"], ["get", "name"]], "text-font": ["NotoSansRegular"], "text-size": 13 }, "paint": { "text-color": "#3f3b33", "text-halo-color": "#ffffff", "text-halo-width": 1.2 } },
                { "id": "roads_labels_major", "type": "symbol", "source": "region", "source-layer": "roads", "filter": ["in", "kind", "highway", "major_road"], "minzoom": 11, "layout": { "symbol-placement": "line", "text-field": ["coalesce", ["get", "name:it"], ["get", "name:en"], ["get", "name"]], "text-font": ["NotoSansRegular"], "text-size": 12 }, "paint": { "text-color": "#7a5c1e", "text-halo-color": "#f7c164", "text-halo-width": 1 } },
                { "id": "roads_labels_minor", "type": "symbol", "source": "region", "source-layer": "roads", "filter": ["in", "kind", "minor_road", "other"], "minzoom": 15, "layout": { "symbol-placement": "line", "text-field": ["coalesce", ["get", "name:it"], ["get", "name:en"], ["get", "name"]], "text-font": ["NotoSansRegular"], "text-size": 11 }, "paint": { "text-color": "#5a5346", "text-halo-color": "#ffffff", "text-halo-width": 1.2 } }
              ]
            }
        """.trimIndent()
    }
}
