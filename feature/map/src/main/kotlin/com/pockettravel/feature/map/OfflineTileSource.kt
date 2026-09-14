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

        // I nomi dei source-layer ("water", "roads", "buildings") sono quelli dello schema
        // "basemap" ufficiale Protomaps (docs.protomaps.com/basemaps/layers), non piu' quelli
        // del nostro Shortbread profile (tools/data-pipeline/maptiles, ShortbreadProfile.kt) —
        // il map.pmtiles installato oggi e' estratto lato device dalla build whole-planet
        // Protomaps (PmtilesExtractor, core:sync), non generato dalla nostra pipeline
        // Planetiler. La pipeline locale resta solo per i suoi test, con uno schema diverso.
        //
        // "attribution" sulla source: l'ODbL 1.0 impone di attribuire i dati
        // OpenStreetMap. Essendo tile locali (pmtiles://), non c'è un TileJSON remoto da cui
        // MapLibre potrebbe altrimenti leggerla: va dichiarata qui. Il controllo attribuzioni
        // di MapLibre Android è attivo di default e la mostra automaticamente (icona "i").
        return """
            {
              "version": 8,
              "sources": {
                "region": {
                  "type": "vector",
                  "url": "pmtiles://file://$pmtilesPath",
                  "attribution": "© OpenStreetMap contributors"
                }
              },
              "layers": [
                { "id": "background", "type": "background", "paint": { "background-color": "#eef2f0" } },
                { "id": "water", "type": "fill", "source": "region", "source-layer": "water", "paint": { "fill-color": "#a7c7e0" } },
                { "id": "roads", "type": "line", "source": "region", "source-layer": "roads", "paint": { "line-color": "#c8beae", "line-width": 1.0 } },
                { "id": "buildings", "type": "fill", "source": "region", "source-layer": "buildings", "paint": { "fill-color": "#d9d3c8" } }
              ]
            }
        """.trimIndent()
    }
}
