"""Fama e patrimonio dei punti di interesse con un elemento Wikidata (colonna wikidata di poi.db, dal tag OSM "wikidata"):
scrive nelle colonne sitelinks e heritage del poi.db di generatePoi. Servono alla passeggiata (prima i luoghi piu' noti)
e al racconto del luogo.

- sitelinks: quante voci di Wikipedia e degli altri progetti Wikimedia sono collegate all'elemento, la misura di fama
  piu' semplice e uguale per tutti i paesi (la Torre Eiffel ne ha quasi 200, una chiesa di paese 1 o 2).
- heritage: 2 se l'elemento e' un patrimonio dell'umanita' UNESCO o ne fa parte (P757, P1435 = Q9259, o P361 verso un
  elemento con P757), 1 se ha un'altra tutela (P1435: monumento nazionale, bene vincolato, listed building...), NULL
  altrimenti. Solo il fatto, da Wikidata (CC0): nessun dato o testo dai siti dell'UNESCO. L'appartenenza a un sito
  UNESCO manca spesso su Wikidata (il Colosseo non e' legato al centro storico di Roma): heritage 2 e' sicuro, la sua
  assenza no.

Un tag wikidata che non indica il luogo si toglie, con fama e patrimonio: l'elemento non esiste piu', non ha coordinate
(P625: e' una persona o un concetto, come la tomba di Napoleone con l'elemento di Napoleone) o le ha a piu' di MAX_KM
dal POI (un altro luogo).

Uso: poi_wikidata.py <poi.db> [poi-extra.db ...]

Cache: con la variabile d'ambiente POI_WIKIDATA_CACHE (un file JSON, conservato dal workflow tra un run e l'altro) i
dati di ogni elemento, anche "nessun dato", valgono CACHE_MAX_AGE_DAYS giorni.

Mai fatale: un errore di rete o il tempo finito (POI_WIKIDATA_MAX_SECONDS, 10 minuti di default) lascia gli elementi
non chiesti come sono, senza fama e patrimonio; la cache li completa nelle run successive.
"""
import json
import math
import os
import re
import sqlite3
import sys
import time
import urllib.parse
import urllib.request
from contextlib import closing
from datetime import date
from pathlib import Path

from city_population import RETRY_ATTEMPTS, _retry_wait, _save_cache

ENDPOINT = "https://query.wikidata.org/sparql"
HEADERS = {
    "User-Agent": "pocket-travel-poi-wikidata/1.0 (https://github.com/miracle091/pocket-travel)",
    "Accept": "application/sparql-results+json",
}
BATCH = 250  # elementi per query
CACHE_ENV = "POI_WIKIDATA_CACHE"
CACHE_MAX_AGE_DAYS = 60  # fama e tutele cambiano di rado
MAX_SECONDS_ENV = "POI_WIKIDATA_MAX_SECONDS"
# Ile-de-France, 9.800 elementi: 8 minuti con un 429. Le regioni piu' grandi si completano in piu' run, con la cache.
MAX_SECONDS = 10 * 60
# Oltre questa distanza tra il POI e le coordinate dell'elemento il tag indica un altro luogo; abbastanza larga per i
# parchi nazionali e le catene montuose, il cui punto OSM e quello di Wikidata possono essere lontani.
MAX_KM = 20
PROTECTED = 1
UNESCO = 2
POINT = re.compile(r"Point\(([-0-9.eE]+) ([-0-9.eE]+)\)")

QUERY = """SELECT ?item ?links (SAMPLE(?c) AS ?coord) (MAX(?p) AS ?protected) (MAX(?u) AS ?unesco) WHERE {
  VALUES ?item { %s }
  ?item wikibase:sitelinks ?links .
  OPTIONAL { ?item wdt:P625 ?c }
  OPTIONAL { ?item wdt:P1435 [] . BIND(1 AS ?p) }
  OPTIONAL {
    { ?item wdt:P757 [] } UNION { ?item wdt:P1435 wd:Q9259 } UNION { ?item wdt:P361 ?whole . ?whole wdt:P757 [] }
    BIND(1 AS ?u)
  }
} GROUP BY ?item ?links"""


def _post(query):
    data = urllib.parse.urlencode({"query": query}).encode()
    for attempt in range(RETRY_ATTEMPTS):
        try:
            with urllib.request.urlopen(urllib.request.Request(ENDPOINT, data=data, headers=HEADERS), timeout=120) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # 429 (limite di Wikimedia) o rete: si riprova con attesa crescente
            if attempt == RETRY_ATTEMPTS - 1:
                raise
            wait = _retry_wait(e, attempt)
            print(f"-- Wikidata: {e}, riprovo tra {wait:.0f}s", file=sys.stderr)
            time.sleep(wait)


def _point(row):
    """(latitudine, longitudine) del letterale WKT "Point(lon lat)" di P625, o (None, None)."""
    m = POINT.fullmatch(row.get("coord", {}).get("value", ""))
    return (round(float(m.group(2)), 5), round(float(m.group(1)), 5)) if m else (None, None)


def fetch(qids, deadline):
    """{numero dell'elemento: [sitelinks, heritage, latitudine, longitudine]} per i numeri in [qids]; un elemento che
    Wikidata non ha (cancellato o unito a un altro) ha tutti i campi None. Mancano gli elementi non chiesti per un
    errore o perche' e' passato [deadline] (time.monotonic())."""
    qids = sorted(qids)
    facts = {}
    for i in range(0, len(qids), BATCH):
        if time.monotonic() > deadline:
            print(f"-- Wikidata: tempo esaurito, {len(qids) - i} elementi non chiesti", file=sys.stderr)
            break
        chunk = qids[i:i + BATCH]
        try:
            rows = _post(QUERY % " ".join(f"wd:Q{q}" for q in chunk))["results"]["bindings"]
        except Exception as e:
            print(f"::warning::Wikidata non risponde ({e}): {len(qids) - i} elementi senza fama e patrimonio",
                  file=sys.stderr)
            break
        found = {}
        for row in rows:
            q = int(row["item"]["value"].rsplit("/Q", 1)[1])
            heritage = UNESCO if "unesco" in row else PROTECTED if "protected" in row else None
            found[q] = [int(row["links"]["value"]), heritage, *_point(row)]
        facts.update({q: found.get(q, [None, None, None, None]) for q in chunk})
        time.sleep(0.2)
    return facts


def _load_cache(path, today):
    """Voci della cache ("19675" -> [sitelinks, heritage, latitudine, longitudine, giorno ISO]) con meno di
    CACHE_MAX_AGE_DAYS giorni; vuota se il file manca o non si legge."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    fresh = {}
    for key, entry in (data.items() if isinstance(data, dict) else []):
        try:
            if len(entry) == 5 and 0 <= (today - date.fromisoformat(entry[4])).days < CACHE_MAX_AGE_DAYS:
                fresh[key] = entry
        except (TypeError, ValueError):
            continue
    return fresh


def _km(lat1, lon1, lat2, lon2):
    """Distanza in linea d'aria (haversine, raggio medio della Terra)."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(a))


def annotate(paths, cache_path=None, today=None, max_seconds=MAX_SECONDS):
    """Scrive sitelinks e heritage nei poi.db [paths] e toglie i tag wikidata che non indicano il luogo; ritorna i POI
    con un elemento valido, quelli patrimonio UNESCO e i tag tolti."""
    today = today or date.today()
    cache = _load_cache(cache_path, today) if cache_path else {}
    qids = set()
    for path in paths:
        with closing(sqlite3.connect(path)) as db:
            qids.update(q for (q,) in db.execute("SELECT DISTINCT wikidata FROM poi WHERE wikidata IS NOT NULL"))
    fetched = fetch({q for q in qids if str(q) not in cache}, time.monotonic() + max_seconds)
    cache.update({str(q): [*facts, today.isoformat()] for q, facts in fetched.items()})
    valid = unesco = dropped = 0
    for path in paths:
        with closing(sqlite3.connect(path)) as db:
            updates = []
            for rowid, q, lat_e6, lon_e6 in db.execute(
                    "SELECT rowid, wikidata, latE6, lonE6 FROM poi WHERE wikidata IS NOT NULL").fetchall():
                if str(q) not in cache:
                    continue
                links, heritage, lat, lon, _ = cache[str(q)]
                if lat is None or _km(lat, lon, lat_e6 / 1e6, lon_e6 / 1e6) > MAX_KM:
                    updates.append((None, None, None, rowid))
                    dropped += 1
                else:
                    updates.append((q, links, heritage, rowid))
                    valid += 1
                    unesco += heritage == UNESCO
            db.executemany("UPDATE poi SET wikidata = ?, sitelinks = ?, heritage = ? WHERE rowid = ?", updates)
            db.commit()
    if cache_path:
        _save_cache(cache_path, cache)
    return valid, unesco, dropped


def main(argv):
    paths = [p for p in argv[1:] if Path(p).is_file()]
    if not paths:
        print("Uso: poi_wikidata.py <poi.db> [poi-extra.db ...]", file=sys.stderr)
        return 2
    cache = os.environ.get(CACHE_ENV)
    max_seconds = int(os.environ.get(MAX_SECONDS_ENV) or MAX_SECONDS)
    valid, unesco, dropped = annotate(paths, Path(cache) if cache else None, max_seconds=max_seconds)
    print(f"-- Wikidata: {valid} POI con fama e patrimonio, {unesco} patrimonio UNESCO, {dropped} tag wikidata tolti "
          f"(elemento senza coordinate o lontano dal POI)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
