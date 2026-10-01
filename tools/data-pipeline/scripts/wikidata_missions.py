#!/usr/bin/env python3
"""Missioni diplomatiche (ambasciate, consolati) di tutto il mondo da Wikidata (CC0), in un TSV per generateGuides.

Uso: wikidata_missions.py --out missions.tsv [--user-agent UA]

Colonne del TSV, senza intestazione, nell'ordine della tabella diplomatic_missions di guides.db:
wikidata, sending, host, kind, name, name_en, city, address, phone, website, email, lat, lon
(campo vuoto = NULL; tab e a capo dentro i valori diventano spazi).

Le query vanno all'endpoint SPARQL di Wikidata, una per tipo di missione (ambasciata, consolato generale,
consolato, comprese le sottoclassi) e una per proprieta': query separate con OPTIONAL su 10 mila elementi
andrebbero in timeout. Si scartano le missioni sciolte (P576, o P582 passata sull'elemento o sugli
enunciati P137/P17) e quelle senza ISO 3166-1 alpha-2 (P297) del paese inviante (P137) o ospitante (P17).

Esce con codice 1 se Wikidata non risponde o il risultato e' sospettosamente piccolo: il chiamante
(build-guides.sh) tiene allora la tabella gia' pubblicata e non fa fallire il job.
"""

import argparse
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ENDPOINT = "https://query.wikidata.org/sparql"
USER_AGENT = "PocketTravel-pipeline/1.0 (https://github.com/miracle091/pocket-travel)"
# Sotto questa soglia il risultato e' un errore (la query vera ne da' circa 10 mila).
MIN_ROWS = 6000
# Tempo massimo complessivo: oltre, si esce con 1 e il chiamante ripiega. Le query sono una decina e
# ognuna ha fino a 5 tentativi da 180 s: senza tetto un endpoint lento bloccherebbe il job per ore.
DEADLINE_SECONDS = 15 * 60
# Istante (time.monotonic) oltre cui sparql() si arrende; lo imposta main().
_deadline = None

# Ordine = precedenza se un elemento sta in piu' alberi di classi.
KINDS = [
    ("consulate_general", "wd:Q372690"),  # consulate general
    ("consulate", "wd:Q7843791"),         # consulate (anche onorari, uffici consolari)
    ("embassy", "wd:Q3917681"),           # embassy (anche alte commissioni, nunziature)
]
# Una query per ciascuna: un'unica query con FILTER NOT EXISTS e OPTIONAL su 11 mila elementi va in timeout
# (60 s) sull'endpoint pubblico. Scartano la missione: P576 (data di fine) su qualunque valore, P582 passata
# sull'elemento o sugli enunciati P137/P17.
CHIUSE = [
    "?m wdt:P576 ?v",
    "?m wdt:P582 ?v FILTER(?v < NOW())",
    "?m p:P137/pq:P582 ?v FILTER(?v < NOW())",
    "?m p:P17/pq:P582 ?v FILTER(?v < NOW())",
]
WKT_POINT = re.compile(r"Point\(\s*(-?[\d.eE+-]+)\s+(-?[\d.eE+-]+)\s*\)")
QID = re.compile(r"Q(\d+)$")
EMAIL = re.compile(r"[^@\s]+@[^@\s]+\.[^@\s]+")
TELEFONO = re.compile(r"[\d\s+().\-/;,#xXextEXT]+")


def sparql(query, user_agent, attempts=5):
    """Esegue una query SELECT e restituisce le righe come lista di dict {variabile: valore}."""
    data = urllib.parse.urlencode({"query": query}).encode()
    headers = {"User-Agent": user_agent, "Accept": "application/sparql-results+json"}
    for attempt in range(1, attempts + 1):
        remaining = None if _deadline is None else _deadline - time.monotonic()
        if remaining is not None and remaining <= 0:
            raise TimeoutError(f"tempo massimo di {DEADLINE_SECONDS} s superato")
        try:
            request = urllib.request.Request(ENDPOINT, data=data, headers=headers)
            with urllib.request.urlopen(request, timeout=180 if remaining is None else min(180, remaining)) as response:
                payload = json.load(response)
            return [{k: v["value"] for k, v in b.items()} for b in payload["results"]["bindings"]]
        except (urllib.error.URLError, OSError, ValueError, KeyError) as e:
            # 429 e 5xx: l'endpoint e' sovraccarico, si riprova con attesa crescente (rispettando Retry-After).
            if attempt == attempts:
                raise
            wait = 20 * attempt
            if isinstance(e, urllib.error.HTTPError):
                if e.code not in (429, 500, 502, 503, 504):
                    raise
                if e.code == 429 and e.headers.get("Retry-After", "").isdigit():
                    wait = max(wait, int(e.headers["Retry-After"]))
            print(f"-- Wikidata: tentativo {attempt} fallito ({e}), riprovo tra {wait}s", file=sys.stderr)
            time.sleep(wait)


def qid(uri):
    return uri.rsplit("/", 1)[-1]


def qid_key(q):
    m = QID.search(q)
    return int(m.group(1)) if m else 0


def pulisci(value):
    return re.sub(r"\s+", " ", value).strip() if value else ""


def coordinate(lat, lon):
    """(lat, lon) come stringhe se sono numeri finiti nei limiti, altrimenti ("", ""): Wikidata a volte da' "1e" o "--"."""
    try:
        la, lo = float(lat), float(lon)
    except (TypeError, ValueError):
        return "", ""
    if math.isfinite(la) and math.isfinite(lo) and -90 <= la <= 90 and -180 <= lo <= 180:
        return lat, lon
    return "", ""


def sito_valido(value):
    value = pulisci(value)
    return value if re.match(r"https?://\S+$", value, re.I) else ""


def telefono_valido(value):
    value = pulisci(value)
    return value if TELEFONO.fullmatch(value) and sum(c.isdigit() for c in value) >= 5 else ""


def email_valida(value):
    value = pulisci(value)
    return value if EMAIL.fullmatch(value) else ""


def query_classes():
    roots = " ".join(root for _, root in KINDS)
    return f"SELECT ?root ?c WHERE {{ VALUES ?root {{ {roots} }} ?c wdt:P279* ?root . }}"


def values(classes):
    return "VALUES ?c { " + " ".join(f"wd:{c}" for c in sorted(classes, key=qid_key)) + " }"


def query_core(classes):
    return f"SELECT ?m ?c ?s ?h WHERE {{ {values(classes)} ?m wdt:P31 ?c ; wdt:P137 ?s ; wdt:P17 ?h . }}"


def query_countries():
    return "SELECT ?m ?v WHERE { ?m wdt:P297 ?v . }"


def query_closed(classes, pattern):
    return f"SELECT ?m ?v WHERE {{ {values(classes)} ?m wdt:P31 ?c . {pattern} }}"


def query_label(classes, lang):
    return f"SELECT ?m ?v WHERE {{ {values(classes)} ?m wdt:P31 ?c ; rdfs:label ?v FILTER(lang(?v) = \"{lang}\") }}"


def query_property(classes, prop):
    return f"SELECT ?m ?v WHERE {{ {values(classes)} ?m wdt:P31 ?c ; wdt:{prop} ?v . }}"


def query_city(classes):
    return f"""
SELECT ?m ?it ?en WHERE {{
  {values(classes)} ?m wdt:P31 ?c ; wdt:P131 ?loc .
  OPTIONAL {{ ?loc rdfs:label ?it FILTER(lang(?it) = "it") }}
  OPTIONAL {{ ?loc rdfs:label ?en FILTER(lang(?en) = "en") }}
}}"""


def query_any_label(ids):
    values = " ".join(f"wd:{i}" for i in ids)
    return f"""
SELECT ?m ?l WHERE {{
  VALUES ?m {{ {values} }}
  SERVICE wikibase:label {{ bd:serviceParam wikibase:language "mul,fr,es,de,pt,ru,ar,zh,ja,[AUTO_LANGUAGE]" . ?m rdfs:label ?l . }}
}}"""


def primo(rows, key="v"):
    """Per ogni elemento il primo valore in ordine alfabetico (le query danno gia' solo i valori preferiti)."""
    out = {}
    for row in sorted(rows, key=lambda r: (qid_key(qid(r["m"])), r.get(key, ""))):
        out.setdefault(qid(row["m"]), row.get(key, ""))
    return out


def raccogli(user_agent, run=sparql):
    """Restituisce le righe (liste di 13 campi) ordinate per Q-id."""
    # Sottoclassi di ogni radice (poche decine), poi query con quell'elenco: "P31/P279*" sugli elementi
    # farebbe andare in timeout l'endpoint. Un elemento in piu' classi prende il tipo della prima in KINDS.
    classes = {}
    class_rows = run(query_classes(), user_agent)
    for kind, root in KINDS:
        for row in class_rows:
            if f"wd:{qid(row['root'])}" == root:
                classes.setdefault(qid(row["c"]), kind)
    precedenza = {kind: i for i, (kind, _) in enumerate(KINDS)}
    iso = {q: v.lower() for q, v in ((qid(r["m"]), r["v"]) for r in run(query_countries(), user_agent))}
    chiuse = set()
    for pattern in CHIUSE:
        chiuse.update(qid(r["m"]) for r in run(query_closed(classes, pattern), user_agent))
    core = sorted(
        (r for r in run(query_core(classes), user_agent) if qid(r["s"]) in iso and qid(r["h"]) in iso),
        key=lambda r: (qid_key(qid(r["m"])), precedenza[classes[qid(r["c"])]], iso[qid(r["s"])], iso[qid(r["h"])]))
    it = primo(run(query_label(classes, "it"), user_agent))
    en = primo(run(query_label(classes, "en"), user_agent))
    missions = {}
    for row in core:
        q = qid(row["m"])
        if q not in missions and q not in chiuse:
            missions[q] = {"kind": classes[qid(row["c"])], "sending": iso[qid(row["s"])], "host": iso[qid(row["h"])],
                           "it": pulisci(it.get(q)), "en": pulisci(en.get(q))}
    print(f"-- missioni attive con paesi noti: {len(missions)} (scartate {len(chiuse)} sciolte)", file=sys.stderr)

    # Nome: it, poi en, poi una lingua qualsiasi (solo per i pochi elementi senza ne' it ne' en).
    senza_nome = sorted((q for q, m in missions.items() if not (m["it"] or m["en"])), key=qid_key)
    altro = {}
    for i in range(0, len(senza_nome), 150):
        altro.update(primo(run(query_any_label(senza_nome[i:i + 150]), user_agent), "l"))

    props = {p: primo(run(query_property(classes, p), user_agent)) for p in ("P6375", "P1329", "P856", "P968", "P625")}
    city = {}
    for row in sorted(run(query_city(classes), user_agent), key=lambda r: (qid_key(qid(r["m"])), r.get("en", ""))):
        city.setdefault(qid(row["m"]), pulisci(row.get("it")) or pulisci(row.get("en")))

    rows = []
    for q in sorted(missions, key=qid_key):
        m = missions[q]
        name = m["it"] or m["en"] or pulisci(altro.get(q))
        if not name:
            continue
        lat = lon = ""
        point = WKT_POINT.match(props["P625"].get(q, ""))
        if point:
            lat, lon = coordinate(point.group(2), point.group(1))
        email = email_valida(re.sub(r"^mailto:", "", props["P968"].get(q, ""), flags=re.I))
        rows.append([q, m["sending"], m["host"], m["kind"], name, m["en"], city.get(q, ""),
                     props["P6375"].get(q, ""), telefono_valido(props["P1329"].get(q, "")),
                     sito_valido(props["P856"].get(q, "")),
                     email, lat, lon])
    return [[pulisci(f) for f in row] for row in rows]


def main():
    global _deadline
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--user-agent", default=USER_AGENT)
    args = parser.parse_args()
    _deadline = time.monotonic() + DEADLINE_SECONDS
    try:
        rows = raccogli(args.user_agent)
    except Exception as e:  # noqa: BLE001 - qualunque errore di rete/parsing: il chiamante ripiega
        print(f"ERRORE: Wikidata non interrogabile: {e}", file=sys.stderr)
        return 1
    if len(rows) < MIN_ROWS:
        print(f"ERRORE: solo {len(rows)} missioni (attese oltre {MIN_ROWS}): risultato scartato", file=sys.stderr)
        return 1
    # Scrittura atomica: un lettore (la run EN, o un job interrotto) non vede mai un TSV a meta'.
    tmp = args.out + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            f.write("\t".join(row) + "\n")
    os.replace(tmp, args.out)
    con_coord = sum(1 for r in rows if r[11])
    print(f"-- missioni Wikidata: {len(rows)} ({con_coord} con coordinate) in {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
