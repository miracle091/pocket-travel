#!/usr/bin/env python3
"""Statistiche di un cities.db per build-cities.sh: citta' con popolazione, citta' con coordinate e sezioni da Wikipedia
(Storia e Clima). La voce "cities" del catalogo le conserva in "stats"; build-cities.sh le confronta con quelle del
pacchetto pubblicato e non lo sostituisce con uno piu' povero (errori di rete di Wikidata o Wikipedia, tetto di tempo).

Uso: python cities_db_stats.py <cities.db>   (stampa un oggetto JSON su una riga)
       python cities_db_stats.py --poorer <nuove> <pubblicate>   (JSON delle statistiche: stampa le voci calate ed esce 0 se le nuove sono piu' povere, 1 altrimenti)
"""
import json
import sqlite3
import sys
from contextlib import closing

# Un calo piccolo e' normale (citta' tolte da Wikivoyage); oltre questa quota e' quasi sempre un errore di rete.
MAX_DROP = 0.10
KEYS = ("withPopulation", "withCoordinates", "wikipediaSections")


def stats(path):
    with closing(sqlite3.connect(path)) as db:
        one = lambda sql: db.execute(sql).fetchone()[0]
        return {
            "withPopulation": one("SELECT COUNT(DISTINCT city) FROM city_sections WHERE population IS NOT NULL"),
            "withCoordinates": one("SELECT COUNT(DISTINCT city) FROM city_sections WHERE latitude IS NOT NULL"),
            "wikipediaSections": one("SELECT COUNT(*) FROM city_sections WHERE sourceUrl LIKE '%.wikipedia.org/%'"),
        }


def poorer(new, published):
    """Le chiavi di [published] in cui [new] cala oltre MAX_DROP; senza statistiche pubblicate non si confronta."""
    return [k for k in KEYS if published.get(k) and new.get(k, 0) < published[k] * (1 - MAX_DROP)]


def main(argv):
    if len(argv) == 4 and argv[1] == "--poorer":
        lost = poorer(json.loads(argv[2]), json.loads(argv[3]))
        if lost:
            print(", ".join(lost))
        return 0 if lost else 1
    if len(argv) == 2:
        print(json.dumps(stats(argv[1]), separators=(",", ":")))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
