#!/usr/bin/env python3
"""Confine OpenStreetMap di una nazione, per tagliare i punti di interesse di una regione sul confine vero.

Uso: country_border.py --iso XX --out confine.geojson --cache DIR [--user-agent UA]

Chiede a Nominatim (geocoder di OpenStreetMap) il poligono della relazione della nazione con quel codice ISO 3166-1
(semplificato a circa 10 m) e lo scrive in --out come Feature GeoJSON, che `osmium extract -p` legge. Stampa l'id
della relazione: l'area Overpass della nazione e' 3600000000 + id. La risposta resta in --cache (le regioni di una
stessa nazione nello stesso job non la richiedono di nuovo; Nominatim chiede al massimo una richiesta al secondo).
Esce con codice 1 senza un poligono: i territori d'oltremare (Riunione, Guadalupa, Svalbard) non sono "country" per
Nominatim, e le regioni senza codice ISO (Canarie) restano sul solo riquadro.
"""

import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

NOMINATIM = "https://nominatim.openstreetmap.org/search"


def cerca(iso, user_agent):
    """Risultato di Nominatim per la nazione [iso], o None."""
    query = urllib.parse.urlencode({"country": iso, "featureType": "country", "polygon_geojson": 1,
                                    "polygon_threshold": 0.0001, "format": "jsonv2", "limit": 1})
    request = urllib.request.Request(f"{NOMINATIM}?{query}", headers={"User-Agent": user_agent})
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                results = json.load(response)
            return results[0] if results else None
        except OSError as error:
            if attempt == 3:
                raise
            print(f"-- confine: {error}, riprovo ({attempt}/3)", file=sys.stderr, flush=True)
            time.sleep(5 * attempt)


def confine(iso, cache, user_agent):
    """(id della relazione, geometria GeoJSON) del confine di [iso], dalla cache o da Nominatim; None se non c'e'."""
    path = os.path.join(cache, f"confine-{iso.lower()}.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            result = json.load(f)
    else:
        result = cerca(iso, user_agent)
        os.makedirs(cache, exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(result, f)
    if not result or result.get("osm_type") != "relation":
        return None
    geometry = result.get("geojson") or {}
    if geometry.get("type") not in ("Polygon", "MultiPolygon"):
        return None
    return int(result["osm_id"]), geometry


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--iso", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--cache", required=True)
    parser.add_argument("--user-agent", default="PocketTravel-pipeline")
    args = parser.parse_args()
    found = confine(args.iso, args.cache, args.user_agent) if len(args.iso) == 2 else None
    if not found:
        sys.exit(f"nessun confine OSM per {args.iso}")
    relation, geometry = found
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"type": "Feature", "properties": {}, "geometry": geometry}, f)
    print(relation)


if __name__ == "__main__":
    main()
