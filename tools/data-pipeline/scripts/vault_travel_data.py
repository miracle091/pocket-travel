#!/usr/bin/env python3
"""Aeroporti, citta', stazioni e compagnie aeree per il completamento dei campi del biglietto nel modulo feature/vault.

Uso: vault_travel_data.py [--out-dir feature/vault/src/main/assets] [--user-agent UA]

Fonti:
- aeroporti: OurAirports airports.csv (https://davidmegginson.github.io/ourairports-data/), pubblico dominio;
- compagnie: Wikidata via SPARQL (CC0), istanze di compagnia aerea (Q46970, sottoclassi comprese: low cost, charter,
  cargo...) con codice IATA (P229) o ICAO (P230) e senza data di fine (P576); in piu' i codici IATA che ci sono solo
  in OpenTravelData (optd_airlines.csv, righe ancora valide), tolte quelle che in Wikidata sono chiuse (P576) con lo
  stesso nome e lo stesso codice IATA o ICAO, perche' OPTD non segna tutte le chiusure;
- nomi italiani delle citta': Wikidata (CC0), etichetta `it` del luogo servito dall'aeroporto (P931) o, in mancanza,
  del luogo in cui si trova (P131), per codice IATA (P238), scartando regioni e contee (suddivisioni di primo e
  secondo livello che non sono anche citta'); per gli aeroporti senza un nome da Wikidata, il nome italiano della
  citta' IATA dell'aeroporto in OpenTravelData;
- citta' con piu' aeroporti e stazioni d'Europa: OpenTravelData (optd_por_public.csv).
OpenTravelData (https://github.com/opentraveldata/opentraveldata) e' CC BY 4.0, con nomi da GeoNames (CC BY 4.0): si
legge solo la cartella `opentraveldata/` a un commit fisso, con controllo SHA-256, e solo le colonne di COLONNE_OPTD
(mai `page_rank`, `wac`, `unlc_list`, `uic_list`). I dati vanno solo negli asset dell'APK, mai nelle tabelle ODbL
dei pacchetti delle regioni.

Si tengono gli aeroporti con codice IATA (quelli stampati sui biglietti), scartando eliporti, aeroporti
chiusi e duplicati; quelli senza IATA non compaiono mai su un biglietto e si perderebbero fra circa 70 mila
piste e campi privati.

File prodotti (TSV senza intestazione, gzip riproducibile, UTF-8):
- airports.tsv.gz: iata, icao, nome, comune, paese (ISO 3166-1 alpha-2), citta' in italiano (vuota se uguale al
  comune; al massimo due, separate da " / "), aeroporti della citta' (solo per le citta', separati da virgola).
  Prima le citta' con piu' aeroporti (nome e comune sono il nome della citta'), poi gli aeroporti, i maggiori per
  primi (a parita' di ricerca l'app suggerisce i primi), poi stazioni e autostazioni d'Europa;
- airlines.tsv.gz: iata, icao, nome.
Un campo vuoto vuol dire dato assente; tab e a capo dentro i valori diventano spazi.
Esce con codice 1 se una fonte non risponde, un file di OpenTravelData non ha l'impronta attesa o il risultato e'
sospettosamente piccolo.
"""

import argparse
import csv
import gzip
import hashlib
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

# OpenTravelData a un commit fisso: un URL raw con l'hash restituisce sempre gli stessi byte, l'impronta lo controlla.
OPTD_URL = "https://raw.githubusercontent.com/opentraveldata/opentraveldata/6fa8c07bf4c96f3b2d0c779f215eb25b5f895bba/opentraveldata/"
OPTD_POR = ("optd_por_public.csv", "927b15b7eef7fc33dfa6ded8fc4fff34acce30ba580e1160fa898ddb7a8780ad")
OPTD_AIRLINES = ("optd_airlines.csv", "9a54561a9ed499516841162cfe3787f3ecd6a832f77e4fe07b1a48d14544d089")
# Le sole colonne di optd_por_public.csv che si tengono (da OPTD e da GeoNames, CC BY 4.0); envelope_id e date_until
# servono solo a scartare i codici storici.
COLONNE_OPTD = ("iata_code", "icao_code", "name", "location_type", "country_code", "continent_name",
                "city_code_list", "alt_name_section")

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
# Compagnie chiuse: OPTD ne tiene alcune come valide (Jet Airways, Air Namibia). Nome e codice insieme, perche' i codici
# IATA si riassegnano (AD: Azul) e i nomi si ripetono (Sky Express).
DISSOLVED_AIRLINES_QUERY = """SELECT ?iata ?icao ?name WHERE {
  ?a wdt:P31/wdt:P279* wd:Q46970 . ?a wdt:P576 ?fine . OPTIONAL { ?a wdt:P229 ?iata } OPTIONAL { ?a wdt:P230 ?icao }
  FILTER(BOUND(?iata) || BOUND(?icao))
  ?a rdfs:label ?name FILTER(LANG(?name) = "en")
}"""
# Scarta i luoghi che sono regioni o contee (suddivisione di primo o secondo livello, contea) senza essere anche citta':
# esce "Castiglia-La Mancia" (ABC), resta "Berlino" (BER), che e' anche un Land.
NON_REGIONE = """FILTER NOT EXISTS {
    ?c wdt:P31/wdt:P279* ?livello . VALUES ?livello { wd:Q10864048 wd:Q13220204 wd:Q28575 }
    FILTER NOT EXISTS { ?c wdt:P31/wdt:P279* wd:Q515 }
  }"""
# Due query: con un OPTIONAL per il ripiego P131 l'endpoint pubblico va in timeout.
CITY_QUERIES = [
    """SELECT ?iata ?city WHERE {
  ?a wdt:P238 ?iata . ?a wdt:P931 ?c . %s ?c rdfs:label ?city FILTER(LANG(?city) = "it")
}""" % NON_REGIONE,
    """SELECT ?iata ?city WHERE {
  ?a wdt:P238 ?iata . FILTER NOT EXISTS { ?a wdt:P931 ?x } ?a wdt:P131 ?c . %s
  ?c rdfs:label ?city FILTER(LANG(?city) = "it")
}""" % NON_REGIONE,
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
    return ordina_compagnie(viste)


def ordina_compagnie(righe):
    return sorted(righe, key=lambda r: (r[2].lower(), r[0], r[1]))


def nome_italiano(alt_name_section):
    """Nome `it` da alt_name_section di OPTD (voci "lingua|nome|flag" separate da "="): quello con flag `p`
    (preferito) o, se manca, il primo."""
    nomi = [(v[1], "p" in v[2]) for v in (voce.split("|") for voce in alt_name_section.split("="))
            if len(v) >= 3 and v[0] == "it" and v[1]]
    return next((n for n, preferito in nomi if preferito), nomi[0][0] if nomi else "")


def parse_optd_por(testo):
    """Righe ancora valide di optd_por_public.csv (envelope_id e date_until vuoti: le altre sono codici storici,
    alcuni riassegnati ad altri luoghi), con le sole COLONNE_OPTD; alt_name_section diventa il nome italiano `it`."""
    righe = []
    for riga in csv.DictReader(io.StringIO(testo), delimiter="^", quoting=csv.QUOTE_NONE):
        if riga.get("envelope_id") or riga.get("date_until"):
            continue
        voce = {c: pulisci(riga.get(c)) for c in COLONNE_OPTD if c != "alt_name_section"}
        voce["it"] = pulisci(nome_italiano(riga.get("alt_name_section") or ""))
        if IATA.fullmatch(voce["iata_code"]):
            righe.append(voce)
    return righe


def _citta_optd(por):
    return {r["iata_code"]: r for r in por if "C" in r["location_type"]}


def _prima_citta(riga, citta):
    return citta.get(riga["city_code_list"].split(",")[0])


def optd_cities_it(por):
    """Dizionario iata dell'aeroporto -> [nome italiano della sua citta' IATA] da OPTD."""
    citta = _citta_optd(por)
    nomi = {}
    for r in por:
        c = _prima_citta(r, citta) if "A" in r["location_type"] else None
        if c and c["it"]:
            nomi[r["iata_code"]] = [c["it"]]
    return nomi


def optd_city_rows(por, aeroporti):
    """Righe delle citta' OPTD con piu' di un aeroporto fra [aeroporti]: (iata, "", nome, nome, paese, nome italiano,
    aeroporti separati da virgola), nell'ordine del loro aeroporto maggiore."""
    posizione = {a[0]: i for i, a in enumerate(aeroporti)}
    per_citta = {}
    for r in por:
        if "A" in r["location_type"] and r["iata_code"] in posizione:
            for c in r["city_code_list"].split(","):
                per_citta.setdefault(c, set()).add(r["iata_code"])
    righe = []
    for codice, c in _citta_optd(por).items():
        suoi = sorted(per_citta.get(codice, ()))
        if len(suoi) > 1 and c["name"]:
            italiano = c["it"] if c["it"].casefold() != c["name"].casefold() else ""
            righe.append((codice, "", c["name"], c["name"], c["country_code"], italiano, ",".join(suoi)))
    return sorted(righe, key=lambda r: (min(posizione[a] for a in r[6].split(",")), r[0]))


def optd_station_rows(por, codici_usati):
    """Stazioni e autostazioni d'Europa di OPTD come righe di aeroporto (iata, "", nome, citta', paese, citta' in
    italiano), per codice. Si scartano le righe R con ICAO (sono aeroporti) e i codici gia' in [codici_usati]."""
    citta = _citta_optd(por)
    righe = []
    for r in por:
        tipo = r["location_type"]
        if (not {"R", "B"} & set(tipo) or r["continent_name"] != "Europe" or ("R" in tipo and r["icao_code"])
                or r["iata_code"] in codici_usati or not r["name"]):
            continue
        c = _prima_citta(r, citta)
        nome_citta, italiano = (c["name"], c["it"]) if c else ("", "")
        righe.append((r["iata_code"], "", r["name"], nome_citta, r["country_code"],
                      italiano if italiano.casefold() != nome_citta.casefold() else ""))
    return sorted(righe)


def parse_dissolved_airlines(risultati):
    """Chiavi (nome in minuscolo, codice) delle compagnie chiuse, una per codice IATA e una per ICAO."""
    chiavi = set()
    for b in risultati:
        nome = pulisci(b.get("name", {}).get("value")).casefold()
        for campo in ("iata", "icao"):
            codice = pulisci(b.get(campo, {}).get("value")).upper()
            if nome and codice:
                chiavi.add((nome, codice))
    return chiavi


def parse_optd_airlines(testo, iata_noti, chiuse=frozenset()):
    """Compagnie ancora valide di optd_airlines.csv (env_id e validity_to vuoti, tipo diverso da G = sistema di
    prenotazione) con un codice IATA che non e' in [iata_noti] e non chiuse secondo [chiuse]
    (parse_dissolved_airlines): (iata, icao, nome)."""
    righe = set()
    for riga in csv.DictReader(io.StringIO(testo), delimiter="^", quoting=csv.QUOTE_NONE):
        iata = pulisci(riga.get("2char_code")).upper()
        icao = pulisci(riga.get("3char_code")).upper()
        nome = pulisci(riga.get("name"))
        if riga.get("env_id") or riga.get("validity_to") or not IATA.fullmatch(iata) or iata in iata_noti or not nome:
            continue
        # type "G": sistemi di prenotazione e fornitori di servizi (Amadeus 1A, Sabre 1S, Travelport 1G, SITA...).
        if riga.get("type") == "G":
            continue
        icao = icao if ICAO.fullmatch(icao) else ""
        if (nome.casefold(), iata) in chiuse or (icao and (nome.casefold(), icao) in chiuse):
            continue
        righe.add((iata, icao, nome))
    return righe


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


def scarica_optd(file, user_agent):
    """Un file di OPTD al commit fisso; esce con 1 se l'impronta SHA-256 non e' quella attesa."""
    nome, impronta = file
    testo = scarica(OPTD_URL + nome, user_agent)
    trovata = hashlib.sha256(testo.encode("utf-8")).hexdigest()
    if trovata != impronta:
        print(f"Impronta di {nome} diversa: {trovata} invece di {impronta}", file=sys.stderr)
        raise SystemExit(1)
    return testo


def sparql(query, user_agent, tentativi=3):
    """Risultati di una query; riprova quando l'endpoint, al limite dei 60 secondi, tronca il JSON a meta'."""
    url = SPARQL_URL + "?" + urllib.parse.urlencode({"query": query, "format": "json"})
    for tentativo in range(1, tentativi + 1):
        try:
            return json.loads(scarica(url, user_agent), strict=False)["results"]["bindings"]
        except json.JSONDecodeError as e:
            print(f"Risposta SPARQL troncata, tentativo {tentativo}/{tentativi}: {e}", file=sys.stderr)
            time.sleep(30 * tentativo)
    raise SystemExit(1)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--user-agent", default=USER_AGENT)
    args = ap.parse_args()

    por = parse_optd_por(scarica_optd(OPTD_POR, args.user_agent))
    # Wikidata resta la base: OPTD solo per gli aeroporti senza un nome da Wikidata.
    cities_it = {**optd_cities_it(por), **parse_cities_it([b for q in CITY_QUERIES for b in sparql(q, args.user_agent)])}
    aeroporti = parse_airports(scarica(AIRPORTS_URL, args.user_agent), cities_it)
    citta = optd_city_rows(por, aeroporti)
    stazioni = optd_station_rows(por, {r[0] for r in aeroporti + citta})
    compagnie = parse_airlines(sparql(AIRLINES_QUERY, args.user_agent))
    compagnie = ordina_compagnie(compagnie + list(parse_optd_airlines(
        scarica_optd(OPTD_AIRLINES, args.user_agent), {c[0] for c in compagnie},
        parse_dissolved_airlines(sparql(DISSOLVED_AIRLINES_QUERY, args.user_agent)))))
    if len(aeroporti) < MIN_AIRPORTS or len(compagnie) < MIN_AIRLINES:
        print(f"Risultato troppo piccolo: {len(aeroporti)} aeroporti, {len(compagnie)} compagnie", file=sys.stderr)
        return 1
    args.out_dir.mkdir(parents=True, exist_ok=True)
    scrivi_tsv_gz(args.out_dir / "airports.tsv.gz", citta + aeroporti + stazioni)
    scrivi_tsv_gz(args.out_dir / "airlines.tsv.gz", compagnie)
    con_italiano = sum(1 for a in aeroporti if a[5])
    print(f"{len(aeroporti)} aeroporti ({con_italiano} con citta' in italiano), {len(citta)} citta', "
          f"{len(stazioni)} stazioni, {len(compagnie)} compagnie in {args.out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
