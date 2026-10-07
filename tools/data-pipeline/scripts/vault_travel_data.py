#!/usr/bin/env python3
"""Aeroporti e compagnie aeree per il completamento dei campi del biglietto nel modulo feature/vault.

Uso: vault_travel_data.py [--out-dir feature/vault/src/main/assets] [--user-agent UA]

Fonti, entrambe di pubblico dominio:
- aeroporti: OurAirports airports.csv (https://davidmegginson.github.io/ourairports-data/);
- compagnie: Wikidata via SPARQL (CC0), istanze di compagnia aerea (Q46970, sottoclassi comprese: low cost, charter,
  cargo...) con codice IATA (P229) o ICAO (P230) e senza data di fine (P576);
- nomi italiani delle citta': Wikidata (CC0), etichetta `it` del luogo servito dall'aeroporto (P931) o, in mancanza,
  del luogo in cui si trova (P131), per codice IATA (P238).

Si tengono gli aeroporti con codice IATA (quelli stampati sui biglietti), scartando eliporti, aeroporti
chiusi e duplicati; quelli senza IATA non compaiono mai su un biglietto e si perderebbero fra circa 70 mila
piste e campi privati.

File prodotti (TSV senza intestazione, gzip riproducibile, UTF-8):
- airports.tsv.gz: iata, icao, nome, comune, paese (ISO 3166-1 alpha-2), citta' in italiano (vuota se uguale al
  comune; al massimo due, separate da " / "), con gli aeroporti maggiori per primi (a parita' di ricerca l'app
  suggerisce i primi);
- airlines.tsv.gz: iata, icao, nome.
Un campo vuoto vuol dire dato assente; tab e a capo dentro i valori diventano spazi.
Esce con codice 1 se una fonte non risponde o il risultato e' sospettosamente piccolo.
"""

import argparse
import csv
import gzip
import io
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

AIRPORTS_URL = "https://davidmegginson.github.io/ourairports-data/airports.csv"
SPARQL_URL = "https://query.wikidata.org/sparql"
USER_AGENT = "PocketTravel-pipeline/1.0 (https://github.com/miracle091/pocket-travel)"
DEFAULT_OUT = Path(__file__).resolve().parents[3] / "feature/vault/src/main/assets"
# Sotto queste soglie il risultato e' un errore (reali: circa 9 mila aeroporti e 6 mila compagnie).
MIN_AIRPORTS = 5000
MIN_AIRLINES = 1200

SCARTATI = {"heliport", "closed"}
# Per un codice IATA ripetuto vince il tipo piu' grande.
PESO_TIPO = {"large_airport": 0, "medium_airport": 1, "small_airport": 2, "seaplane_base": 3, "balloonport": 4}
IATA = re.compile(r"[A-Z0-9]{2,3}")
ICAO = re.compile(r"[A-Z0-9]{3,4}")

AIRLINES_QUERY = """SELECT ?iata ?icao ?name WHERE {
  ?a wdt:P31/wdt:P279* wd:Q46970 . OPTIONAL { ?a wdt:P229 ?iata } OPTIONAL { ?a wdt:P230 ?icao }
  FILTER(BOUND(?iata) || BOUND(?icao)) FILTER NOT EXISTS { ?a wdt:P576 ?fine }
  ?a rdfs:label ?name FILTER(LANG(?name) = "en")
}"""
# Due query: con un OPTIONAL per il ripiego P131 l'endpoint pubblico va in timeout.
CITY_QUERIES = [
    """SELECT ?iata ?city WHERE {
  ?a wdt:P238 ?iata . ?a wdt:P931 ?c . ?c rdfs:label ?city FILTER(LANG(?city) = "it")
}""",
    """SELECT ?iata ?city WHERE {
  ?a wdt:P238 ?iata . FILTER NOT EXISTS { ?a wdt:P931 ?x } ?a wdt:P131 ?c . ?c rdfs:label ?city FILTER(LANG(?city) = "it")
}""",
]


def pulisci(valore):
    """Spazi normalizzati e niente tab o a capo, che romperebbero il TSV."""
    return " ".join((valore or "").split())


def parse_cities_it(risultati):
    """Dizionario iata -> nomi italiani della citta' (al massimo due, i piu' corti per primi) dai binding SPARQL."""
    per_iata = {}
    for b in risultati:
        iata = pulisci(b.get("iata", {}).get("value")).upper()
        citta = pulisci(b.get("city", {}).get("value"))
        if iata and citta:
            per_iata.setdefault(iata, set()).add(citta)
    return {i: sorted(c, key=lambda n: (len(n), n))[:2] for i, c in per_iata.items()}


def parse_airports(testo, cities_it=None):
    """Righe (iata, icao, nome, comune, paese, citta' in italiano) dal CSV di OurAirports, dai tipi piu' grandi
    e poi per codice IATA. La citta' in italiano e' vuota se uguale al comune."""
    cities_it = cities_it or {}
    per_iata = {}
    for riga in csv.DictReader(io.StringIO(testo)):
        iata = pulisci(riga.get("iata_code")).upper()
        tipo = riga.get("type", "")
        if not IATA.fullmatch(iata) or tipo in SCARTATI:
            continue
        icao = pulisci(riga.get("icao_code") or riga.get("ident")).upper()
        comune = pulisci(riga.get("municipality"))
        italiano = [c for c in cities_it.get(iata, []) if c.casefold() != comune.casefold()]
        voce = (iata, icao if ICAO.fullmatch(icao) else "", pulisci(riga.get("name")),
                comune, pulisci(riga.get("iso_country")).upper(), " / ".join(italiano))
        if not voce[2]:
            continue
        vecchia = per_iata.get(iata)
        if vecchia is None or PESO_TIPO.get(tipo, 9) < vecchia[0]:
            per_iata[iata] = (PESO_TIPO.get(tipo, 9), voce)
    return [voce for _, voce in sorted(per_iata.values(), key=lambda v: (v[0], v[1][0]))]


def parse_airlines(risultati):
    """Righe (iata, icao, nome) uniche e ordinate dai binding SPARQL; richiedono almeno un codice valido."""
    viste = set()
    for b in risultati:
        iata = pulisci(b.get("iata", {}).get("value")).upper()
        icao = pulisci(b.get("icao", {}).get("value")).upper()
        nome = pulisci(b.get("name", {}).get("value"))
        iata = iata if IATA.fullmatch(iata) else ""
        icao = icao if ICAO.fullmatch(icao) else ""
        if nome and (iata or icao):
            viste.add((iata, icao, nome))
    return sorted(viste, key=lambda r: (r[2].lower(), r[0], r[1]))


def scrivi_tsv_gz(percorso, righe):
    """TSV compresso con mtime fisso: lo stesso input da' lo stesso file."""
    testo = "".join("\t".join(r) + "\n" for r in righe)
    with open(percorso, "wb") as f, gzip.GzipFile(filename="", fileobj=f, mode="wb", mtime=0, compresslevel=9) as gz:
        gz.write(testo.encode("utf-8"))


def scarica(url, user_agent, tentativi=4):
    richiesta = urllib.request.Request(url, headers={"User-Agent": user_agent, "Accept": "*/*"})
    for tentativo in range(1, tentativi + 1):
        try:
            with urllib.request.urlopen(richiesta, timeout=180) as r:
                return r.read().decode("utf-8")
        except (urllib.error.URLError, TimeoutError) as e:
            print(f"Tentativo {tentativo}/{tentativi} fallito per {url[:60]}: {e}", file=sys.stderr)
            time.sleep(10 * tentativo)
    raise SystemExit(1)


def sparql(query, user_agent):
    url = SPARQL_URL + "?" + urllib.parse.urlencode({"query": query, "format": "json"})
    return json.loads(scarica(url, user_agent), strict=False)["results"]["bindings"]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--user-agent", default=USER_AGENT)
    args = ap.parse_args()

    cities_it = parse_cities_it([b for q in CITY_QUERIES for b in sparql(q, args.user_agent)])
    aeroporti = parse_airports(scarica(AIRPORTS_URL, args.user_agent), cities_it)
    compagnie = parse_airlines(sparql(AIRLINES_QUERY, args.user_agent))
    if len(aeroporti) < MIN_AIRPORTS or len(compagnie) < MIN_AIRLINES:
        print(f"Risultato troppo piccolo: {len(aeroporti)} aeroporti, {len(compagnie)} compagnie", file=sys.stderr)
        return 1
    args.out_dir.mkdir(parents=True, exist_ok=True)
    scrivi_tsv_gz(args.out_dir / "airports.tsv.gz", aeroporti)
    scrivi_tsv_gz(args.out_dir / "airlines.tsv.gz", compagnie)
    con_italiano = sum(1 for a in aeroporti if a[5])
    print(f"{len(aeroporti)} aeroporti ({con_italiano} con citta' in italiano), {len(compagnie)} compagnie in {args.out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
