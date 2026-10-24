#!/usr/bin/env python3
"""Confine OpenStreetMap di una nazione o di una sua suddivisione, per tagliare i punti di interesse di una regione.

Uso: country_border.py --iso XX [--name NOME] [--subdivision --bbox minLon,minLat,maxLon,maxLat] --out confine.geojson
     --cache DIR [--user-agent UA]

Chiede a Nominatim (geocoder di OpenStreetMap) la relazione della nazione con quel codice ISO 3166-1 e ne scrive il
poligono completo (lookup, non semplificato) in --out come Feature GeoJSON, che `osmium extract -p` legge: i poligoni
semplificati di due regioni vicine si sovrappongono o si staccano di qualche metro, e un punto sul confine finirebbe
in tutte e due o in nessuna. Stampa l'id
della relazione: l'area Overpass della nazione e' 3600000000 + id. La risposta resta in --cache (le regioni di una
stessa nazione nello stesso job non la richiedono di nuovo; Nominatim chiede al massimo una richiesta al secondo).
I territori d'oltremare (Riunione, Porto Rico, Svalbard) non sono "country" per Nominatim: si cercano per --name (il
titolo Wikivoyage della regione). Vale solo una relazione con lo stesso codice ISO 3166-1 (ISO3166-1 o
ISO3166-1:alpha2 tra i tag): "country" e "q" sono testo libero, e per "PR" Nominatim restituisce il Peru'. Esce con
codice 1 senza un poligono: le regioni senza codice ISO (Canarie) restano sul solo rettangolo.

Con --subdivision (regioni che sono parte di una nazione: stati USA, province) cerca prima la suddivisione per --name,
anche senza la parte tra parentesi ("Georgia (U.S. state)"). Vale una relazione con un codice ISO 3166-2 della nazione
(nell'indirizzo di Nominatim, non tra i tag) il cui rettangolo copre quasi tutto quello della regione (--bbox, almeno
MIN_COVERAGE): il nome da solo sbaglia (per "Hubei" Nominatim restituisce anche Hebei, per "Volga Region" il
Territorio di Perm). Senza una suddivisione valida (le regioni economiche russe non sono confini in OSM) vale il
confine della nazione.
"""

import argparse
import http.client
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

NOMINATIM = "https://nominatim.openstreetmap.org"
# Quota minima del rettangolo della regione (regions.sh, da Natural Earth) coperta da quello della suddivisione trovata.
# Non l'intersezione su unione: in OSM il rettangolo di Hawaii, Alaska (oltre l'antimeridiano), Shanghai e Tianjin
# comprende isole lontane o mare e supera di 10 volte quello della regione. Hebei per la regione dello Hubei copre 0.
MIN_COVERAGE = 0.7


def cerca(params, user_agent, endpoint="search"):
    """Risultati di Nominatim per [params] (country/featureType o q; osm_ids per "lookup"), con poligono, tag e
    indirizzo. La ricerca ha i poligoni semplificati a circa 10 m (fino a cinque risultati), il lookup quello completo."""
    extra = {"polygon_threshold": 0.0001, "limit": 5} if endpoint == "search" else {}
    query = urllib.parse.urlencode({**params, "polygon_geojson": 1, **extra, "format": "jsonv2", "extratags": 1,
                                    "addressdetails": 1})
    request = urllib.request.Request(f"{NOMINATIM}/{endpoint}?{query}", headers={"User-Agent": user_agent})
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.load(response)
        except (OSError, http.client.HTTPException) as error:
            # IncompleteRead (corpo troncato) non e' ne' OSError ne' ValueError.
            if attempt == 3:
                raise
            print(f"-- confine: {error}, riprovo ({attempt}/3)", file=sys.stderr, flush=True)
            # 429: Nominatim vuole una richiesta al secondo e blocca per qualche minuto chi ne fa di piu'.
            time.sleep((60 if getattr(error, "code", None) == 429 else 5) * attempt)


def copertura(a, b):
    """Quota del rettangolo [b] coperta dal rettangolo [a] (minLon, minLat, maxLon, maxLat)."""
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    if w <= 0 or h <= 0:
        return 0.0
    return w * h / ((b[2] - b[0]) * (b[3] - b[1]))


def nazione(result, iso):
    """Relazione con il codice ISO 3166-1 [iso]. Non un confine marittimo: per "New Caledonia" Nominatim restituisce prima
    la zona economica esclusiva, con lo stesso codice, da cui `osmium extract` non estrae nessun punto di interesse."""
    tags = result.get("extratags") or {}
    return (result.get("type") != "maritime"
            and (tags.get("ISO3166-1:alpha2") or tags.get("ISO3166-1") or "").lower() == iso.lower())


def suddivisione(result, iso, bbox):
    # Non il contorno di un'isola o di un arcipelago (segue la costa: fuori porti, moli e siti d'immersione, come i 90
    # POI delle Canarie) ne' un confine marittimo: per "Galapagos Islands" Nominatim da' prima l'arcipelago, per
    # "Easter Island" la zona marittima. Allora vale la provincia, o il confine della nazione.
    if result.get("type") in ("island", "islet", "archipelago", "maritime"):
        return False
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


def poligono_completo(relation, cache, user_agent):
    """Poligono non semplificato della relazione [relation], dalla cache o dal lookup di Nominatim; None senza."""
    def fetch():
        time.sleep(1)
        return cerca({"osm_ids": f"R{relation}"}, user_agent, "lookup")

    path = os.path.join(cache, f"confine-r{relation}.json")
    try:
        found = scegli(in_cache(path, fetch), lambda r: True)
    except (OSError, ValueError, http.client.HTTPException) as error:
        # La ricerca ha gia' trovato la relazione: senza il lookup (rete, risposta troncata) vale quello semplificato.
        print(f"-- confine: lookup della relazione {relation}: {error}", file=sys.stderr, flush=True)
        return None
    if found and found[0] == relation:
        return found[1]
    # Una risposta senza il poligono non resta in cache: le regioni dopo, nello stesso job, riprovano il lookup.
    os.remove(path)
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--iso", required=True)
    parser.add_argument("--name", help="nome per la ricerca dei territori e delle suddivisioni")
    parser.add_argument("--subdivision", action="store_true", help="cerca prima la suddivisione --name dentro --bbox")
    parser.add_argument("--bbox", help="rettangolo della regione: minLon,minLat,maxLon,maxLat")
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
    complete = poligono_completo(relation, args.cache, args.user_agent)
    if not complete:
        print(f"-- confine: nessun poligono completo per la relazione {relation}, uso quello semplificato",
              file=sys.stderr, flush=True)
    # completo: build-region.sh registra la regola del taglio (poi.borderRule) solo con il poligono completo, cosi'
    # un pacchetto tagliato su quello semplificato si rigenera alla run dopo.
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"type": "Feature", "properties": {"completo": bool(complete)}, "geometry": complete or geometry}, f)
    print(relation)


if __name__ == "__main__":
    main()
