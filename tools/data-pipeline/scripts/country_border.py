#!/usr/bin/env python3
"""Confine OpenStreetMap di una nazione o di una sua suddivisione, per tagliare i punti di interesse di una regione.

Uso: country_border.py --iso XX [--name NOME] [--subdivision --bbox minLon,minLat,maxLon,maxLat] --out confine.geojson
     --cache DIR [--user-agent UA]

Chiede a Nominatim (geocoder di OpenStreetMap) il poligono della relazione della nazione con quel codice ISO 3166-1
(semplificato a circa 10 m) e lo scrive in --out come Feature GeoJSON, che `osmium extract -p` legge. Stampa l'id
della relazione: l'area Overpass della nazione e' 3600000000 + id. La risposta resta in --cache (le regioni di una
stessa nazione nello stesso job non la richiedono di nuovo; Nominatim chiede al massimo una richiesta al secondo).
I territori d'oltremare (Riunione, Porto Rico, Svalbard) non sono "country" per Nominatim: si cercano per --name (il
titolo Wikivoyage della regione). Vale solo una relazione con lo stesso codice ISO 3166-1 (ISO3166-1 o
ISO3166-1:alpha2 tra i tag): "country" e "q" sono testo libero, e per "PR" Nominatim restituisce il Peru'. Esce con
codice 1 senza un poligono: le regioni senza codice ISO (Canarie) restano sul solo riquadro.

Con --subdivision (regioni che sono parte di una nazione: stati USA, province) cerca prima la suddivisione per --name,
anche senza la parte tra parentesi ("Georgia (U.S. state)"). Vale una relazione con un codice ISO 3166-2 della nazione
(nell'indirizzo di Nominatim, non tra i tag) il cui riquadro copre quasi tutto quello della regione (--bbox, almeno
MIN_COVERAGE): il nome da solo sbaglia (per "Hubei" Nominatim restituisce anche Hebei, per "Volga Region" il
Territorio di Perm). Senza una suddivisione valida (le regioni economiche russe non sono confini in OSM) vale il
confine della nazione.
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

NOMINATIM = "https://nominatim.openstreetmap.org/search"
# Quota minima del riquadro della regione (regions.sh, da Natural Earth) coperta da quello della suddivisione trovata.
# Non l'intersezione su unione: in OSM il riquadro di Hawaii, Alaska (oltre l'antimeridiano), Shanghai e Tianjin
# comprende isole lontane o mare e supera di 10 volte quello della regione. Hebei per la regione dello Hubei copre 0.
MIN_COVERAGE = 0.7


def cerca(params, user_agent):
    """Risultati di Nominatim per [params] (country/featureType o q), con poligono, tag e indirizzo."""
    query = urllib.parse.urlencode({**params, "polygon_geojson": 1, "polygon_threshold": 0.0001, "format": "jsonv2",
                                    "limit": 5, "extratags": 1, "addressdetails": 1})
    request = urllib.request.Request(f"{NOMINATIM}?{query}", headers={"User-Agent": user_agent})
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.load(response)
        except OSError as error:
            if attempt == 3:
                raise
            print(f"-- confine: {error}, riprovo ({attempt}/3)", file=sys.stderr, flush=True)
            # 429: Nominatim vuole una richiesta al secondo e blocca per qualche minuto chi ne fa di piu'.
            time.sleep((60 if getattr(error, "code", None) == 429 else 5) * attempt)


def copertura(a, b):
    """Quota del riquadro [b] coperta dal riquadro [a] (minLon, minLat, maxLon, maxLat)."""
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    if w <= 0 or h <= 0:
        return 0.0
    return w * h / ((b[2] - b[0]) * (b[3] - b[1]))


def nazione(result, iso):
    tags = result.get("extratags") or {}
    return (tags.get("ISO3166-1:alpha2") or tags.get("ISO3166-1") or "").lower() == iso.lower()


def suddivisione(result, iso, bbox):
    address = result.get("address") or {}
    code = address.get("ISO3166-2-lvl4") or address.get("ISO3166-2-lvl3") or ""
    if not code.upper().startswith(f"{iso.upper()}-") or len(result.get("boundingbox") or []) != 4:
        return False
    south, north, west, east = (float(v) for v in result["boundingbox"])
    return copertura((west, south, east, north), bbox) >= MIN_COVERAGE


def scegli(results, valida):
    """(id, geometria) della prima relazione di [results] con un poligono per cui [valida] e' vero, o None."""
    for result in results or []:
        geometry = result.get("geojson") or {}
        if (result.get("osm_type") == "relation" and geometry.get("type") in ("Polygon", "MultiPolygon")
                and valida(result)):
            return int(result["osm_id"]), geometry
    return None


def in_cache(path, fetch):
    """Risultati salvati in [path], o quelli di [fetch]() salvati li'."""
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    results = fetch()
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(results, f)
    return results


def confine(iso, cache, user_agent, name=None):
    """(id della relazione, geometria GeoJSON) del confine di [iso], dalla cache o da Nominatim; None se non c'e'."""
    def valida(result):
        return nazione(result, iso)

    def fetch():
        results = cerca({"country": iso, "featureType": "country"}, user_agent)
        if not scegli(results, valida) and name:
            time.sleep(1)
            results = cerca({"q": name}, user_agent)
        return results

    return scegli(in_cache(os.path.join(cache, f"confine-{iso.lower()}.json"), fetch), valida)


def confine_suddivisione(iso, name, bbox, cache, user_agent):
    """(id, geometria) della suddivisione di [iso] chiamata [name] che copre [bbox], o None."""
    def valida(result):
        return suddivisione(result, iso, bbox)

    names = [name] + ([re.sub(r"\s*\(.*\)$", "", name)] if "(" in name else [])

    def fetch():
        results = []
        for query in names:
            for params in ({"q": query, "countrycodes": iso.lower(), "featureType": "state"},
                           {"q": query, "countrycodes": iso.lower()}):
                results += cerca(params, user_agent) or []
                if scegli(results, valida):
                    return results
                time.sleep(1)
        return results

    slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return scegli(in_cache(os.path.join(cache, f"confine-{iso.lower()}-{slug}.json"), fetch), valida)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--iso", required=True)
    parser.add_argument("--name", help="nome per la ricerca dei territori e delle suddivisioni")
    parser.add_argument("--subdivision", action="store_true", help="cerca prima la suddivisione --name dentro --bbox")
    parser.add_argument("--bbox", help="riquadro della regione: minLon,minLat,maxLon,maxLat")
    parser.add_argument("--out", required=True)
    parser.add_argument("--cache", required=True)
    parser.add_argument("--user-agent", default="PocketTravel-pipeline")
    args = parser.parse_args()
    if len(args.iso) != 2:
        sys.exit(f"nessun confine OSM per {args.iso}")
    found = None
    if args.subdivision and args.name and args.bbox:
        bbox = tuple(float(v) for v in args.bbox.split(","))
        found = confine_suddivisione(args.iso, args.name, bbox, args.cache, args.user_agent)
        if not found:
            print(f"-- confine: nessuna suddivisione \"{args.name}\" di {args.iso.upper()}, uso il confine della nazione",
                  file=sys.stderr, flush=True)
    found = found or confine(args.iso, args.cache, args.user_agent, None if args.subdivision else args.name)
    if not found:
        sys.exit(f"nessun confine OSM per {args.iso}")
    relation, geometry = found
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"type": "Feature", "properties": {}, "geometry": geometry}, f)
    print(relation)


if __name__ == "__main__":
    main()
