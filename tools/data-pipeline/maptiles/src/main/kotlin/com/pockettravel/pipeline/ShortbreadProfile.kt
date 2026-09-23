package com.pockettravel.pipeline

import com.onthegomap.planetiler.FeatureCollector
import com.onthegomap.planetiler.Profile
import com.onthegomap.planetiler.reader.SourceFeature

/**
 * Profilo Planetiler minimale che emette solo i 3 layer Shortbread che
 * OfflineTileSource.kt (feature/map) sa disegnare: "water", "transportation",
 * "buildings". Non e' un'implementazione completa dello schema Shortbread (che ne
 * definisce molti di piu') — solo il sottoinsieme richiesto dallo style JSON attuale
 * dell'app, per confermare i nomi reali dei source-layer.
 */
class ShortbreadProfile : Profile {

    override fun processFeature(sourceFeature: SourceFeature, features: FeatureCollector) {
        if (sourceFeature.canBePolygon() && sourceFeature.hasTag("natural", "water")) {
            features.polygon("water").setZoomRange(0, 14).setMinPixelSize(1.0)
        }
        if (sourceFeature.canBePolygon() && sourceFeature.hasTag("building")) {
            features.polygon("buildings").setZoomRange(0, 14).setMinPixelSize(1.0)
        }
        if (sourceFeature.canBeLine() && sourceFeature.hasTag("highway")) {
            features.line("transportation").setZoomRange(0, 14)
        }
    }

    override fun name() = "pocket-travel-shortbread-minimal"
}
