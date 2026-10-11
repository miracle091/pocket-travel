#!/usr/bin/env python3
"""Confine OpenStreetMap di una nazione, per tagliare i punti di interesse di una regione sul confine vero.

Uso: country_border.py --iso XX [--name NOME] --out confine.geojson --cache DIR [--user-agent UA]

Chiede a Nominatim (geocoder di OpenStreetMap) il poligono della relazione della nazione con quel codice ISO 3166-1
(semplificato a circa 10 m) e lo scrive in --out come Feature GeoJSON, che `osmium extract -p` legge. Stampa l'id
della relazione: l'area Overpass della nazione e' 3600000000 + id. La risposta resta in --cache (le regioni di una
stessa nazione nello stesso job non la richiedono di nuovo; Nominatim chiede al massimo una richiesta al secondo).
I territori d'oltremare (Riunione, Porto Rico, Svalbard) non sono "country" per Nominatim: si cercano per --name (il
titolo Wikivoyage della regione). Vale solo una relazione con lo stesso codice ISO 3166-1 (ISO3166-1 o
ISO3166-1:alpha2 tra i tag): "country" e "q" sono testo libero, e per "PR" Nominatim restituisce il Peru'. Esce con
codice 1 senza un poligono: le regioni senza codice ISO (Canarie) restano sul solo riquadro.
"""

import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

NOMINATIM = "https://nominatim.openstreetmap.org/search"


def cerca(params, user_agent):
    """Risultati di Nominatim per [params] (country/featureType o q), con poligono e tag."""
    query = urllib.parse.urlencode({**params, "polygon_geojson": 1, "polygon_threshold": 0.0001, "format": "jsonv2",
                                    "limit": 5, "extratags": 1})
    request = urllib.request.Request(f"{NOMINATIM}?{query}", headers={"User-Agent": user_agent})
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.load(response)
        except OSError as error:
            if attempt == 3:
                raise
            print(f"-- confine: {error}, riprovo ({attempt}/3)", file=sys.stderr, flush=True)
            time.sleep(5 * attempt)


def scegli(results, iso):
    """(id, geometria) della prima relazione di [results] con codice ISO [iso] e un poligono, o None."""
    for result in results or []:
        tags = result.get("extratags") or {}
        geometry = result.get("geojson") or {}
        if (result.get("osm_type") == "relation"
                and (tags.get("ISO3166-1:alpha2") or tags.get("ISO3166-1") or "").lower() == iso.lower()
                and geometry.get("type") in ("Polygon", "MultiPolygon")):
            return int(result["osm_id"]), geometry
    return None


def confine(iso, cache, user_agent, name=None):
    """(id della relazione, geometria GeoJSON) del confine di [iso], dalla cache o da Nominatim; None se non c'e'."""
    path = os.path.join(cache, f"confine-{iso.lower()}.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return scegli(json.load(f), iso)
    results = cerca({"country": iso, "featureType": "country"}, user_agent)
    if not scegli(results, iso) and name:
        time.sleep(1)
        results = cerca({"q": name}, user_agent)
    os.makedirs(cache, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(results, f)
    return scegli(results, iso)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--iso", required=True)
    parser.add_argument("--name", help="nome per la ricerca dei territori che non sono \"country\" in Nominatim")
    parser.add_argument("--out", required=True)
    parser.add_argument("--cache", required=True)
    parser.add_argument("--user-agent", default="PocketTravel-pipeline")
    args = parser.parse_args()
    found = confine(args.iso, args.cache, args.user_agent, args.name) if len(args.iso) == 2 else None
    if not found:
        sys.exit(f"nessun confine OSM per {args.iso}")
    relation, geometry = found
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"type": "Feature", "properties": {}, "geometry": geometry}, f)
    print(relation)


if __name__ == "__main__":
    main()
