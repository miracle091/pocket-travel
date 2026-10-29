#!/usr/bin/env python3
"""Test di cities_db_stats.py (statistiche di un cities.db e confronto con quelle pubblicate) e della scelta di
build-cities.sh di non sostituire un pacchetto con uno piu' povero (popolazione, coordinate, sezioni Wikipedia
perse per un errore di rete). Gradle e' finto: scrive un cities.db costruito dal test. Richiede bash, jq e xz.

Uso: python test_cities_db_stats.py
"""
import json
import shutil
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

import cities_db_stats
import shell_fixtures as fx

WIKIPEDIA = "https://it.wikipedia.org/wiki/Roma"


def make_db(path, cities):
    """cities: [(nome, popolazione o None, latitudine o None, [sezioni di Wikipedia])]."""
    with closing(sqlite3.connect(path)) as db:
        db.execute("CREATE TABLE city_sections (city TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT, population INTEGER, "
                   "capital INTEGER, latitude REAL, longitude REAL, translated INTEGER)")
        for name, population, lat, wikipedia in cities:
            rows = [("GUIDA", "Intro", "testo", "https://it.wikivoyage.org/wiki/" + name)] + [(c, c, "testo", WIKIPEDIA) for c in wikipedia]
            db.executemany("INSERT INTO city_sections VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, 0)",
                           [(name, *r[:3], r[3], population, lat, None if lat is None else 12.0) for r in rows])
        db.commit()


def hundred(population=True, coordinates=True, wikipedia=True):
    return [(f"c{i}", 1000 if population else None, 41.9 if coordinates else None, ["STORIA", "CLIMA"] if wikipedia else []) for i in range(100)]


class StatsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.db = Path(self.dir.name) / "cities.db"

    def test_conta_citta_e_sezioni_di_wikipedia(self):
        make_db(self.db, [("Roma", 2800000, 41.9, ["STORIA", "CLIMA"]), ("Ostia", None, 41.7, []), ("Tivoli", 50000, None, ["STORIA"])])
        self.assertEqual({"withPopulation": 2, "withCoordinates": 2, "wikipediaSections": 3}, cities_db_stats.stats(self.db))

    def test_piu_povero_oltre_il_dieci_per_cento(self):
        published = {"withPopulation": 100, "withCoordinates": 100, "wikipediaSections": 200}
        self.assertEqual(["withPopulation"], cities_db_stats.poorer({**published, "withPopulation": 89}, published))
        self.assertEqual([], cities_db_stats.poorer({**published, "withPopulation": 90}, published))
        self.assertEqual(["withPopulation", "withCoordinates", "wikipediaSections"],
                         cities_db_stats.poorer({"withPopulation": 0, "withCoordinates": 0, "wikipediaSections": 0}, published))

    def test_piu_ricco_o_senza_statistiche_pubblicate_non_e_piu_povero(self):
        self.assertEqual([], cities_db_stats.poorer({"withPopulation": 500, "withCoordinates": 500, "wikipediaSections": 900},
                                                    {"withPopulation": 100, "withCoordinates": 100, "wikipediaSections": 200}))
        self.assertEqual([], cities_db_stats.poorer({"withPopulation": 0}, {}))
        self.assertEqual([], cities_db_stats.poorer({"withPopulation": 0}, {"withPopulation": 0}))


FAKE_GRADLE = r'''#!/usr/bin/env bash
# Scrive il cities.db preparato dal test (CITIES_FIXTURE) nel percorso passato come ultimo argomento.
dest="$(printf '%s' "$*" | sed 's/.*"\([^"]*\)"$/\1/')"
cp "$CITIES_FIXTURE" "$dest"
echo "citta': 100 sezioni di 100 citta' scritte in $dest"
'''


@unittest.skipUnless(fx.BASH and shutil.which("jq") and shutil.which("xz"), "servono bash, jq e xz")
class BuildCitiesTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.tree = Path(self.dir.name)
        for f in ("build-cities.sh", "lib.sh", "portable.sh", "cities_db_stats.py"):
            fx.copy(f"tools/data-pipeline/scripts/{f}", self.tree)
        fx.write(self.tree / "gradlew", FAKE_GRADLE, executable=True)
        self.out = self.tree / "out"
        self.out.mkdir()
        fx.write(self.out / "manifest-fragment.json", '{"manifestVersion":2,"regions":[{"regionId":"r1"}]}')
        jsonl = self.tree / "jsonl"
        fx.write(jsonl / "r1.cities.jsonl", "{}\n")

    def build(self, new_cities, published_entry=None):
        make_db(self.tree / "new.db", new_cities)
        manifest = self.tree / "published.json"
        region = {"regionId": "r1", **({"cities": published_entry} if published_entry else {})}
        manifest.write_text(json.dumps({"regions": [region]}), encoding="utf-8")
        env = fx.environment(self.tree, CITIES_FIXTURE=(self.tree / "new.db").as_posix())
        proc = fx.run_bash([self.tree / "tools/data-pipeline/scripts/build-cities.sh", "r1", "2026.10.30", "https://h/rel", self.out,
                            self.tree / "jsonl", manifest], self.tree, env)
        self.assertEqual(0, proc.returncode, proc.stderr)
        fragment = json.loads((self.out / "manifest-fragment.json").read_text(encoding="utf-8"))
        return proc, fragment["regions"][0].get("cities")

    PUBLISHED = {"version": "2026.10.01", "file": {"name": "cities.db", "url": "https://h/old", "sizeBytes": 1, "sha256": "00"},
                 "stats": {"withPopulation": 100, "withCoordinates": 100, "wikipediaSections": 200}}

    def test_prima_pubblicazione_scrive_le_statistiche(self):
        proc, entry = self.build(hundred())
        self.assertEqual({"withPopulation": 100, "withCoordinates": 100, "wikipediaSections": 200}, entry["stats"])
        self.assertEqual("2026.10.30", entry["version"])
        self.assertTrue((self.out / "cities.db.xz").exists())

    def test_pacchetto_senza_popolazione_non_sostituisce_il_pubblicato(self):
        proc, entry = self.build(hundred(population=False), self.PUBLISHED)
        self.assertEqual(self.PUBLISHED, entry)
        self.assertIn("::warning::r1: cities.db piu' povero del pubblicato (withPopulation)", proc.stdout)
        self.assertFalse((self.out / "cities.db.xz").exists())

    def test_pacchetto_senza_wikipedia_non_sostituisce_il_pubblicato(self):
        _, entry = self.build(hundred(wikipedia=False), self.PUBLISHED)
        self.assertEqual(self.PUBLISHED, entry)

    def test_pacchetto_completo_sostituisce_il_pubblicato(self):
        _, entry = self.build(hundred(), self.PUBLISHED)
        self.assertEqual("2026.10.30", entry["version"])
        self.assertTrue((self.out / "cities.db.xz").exists())

    def test_voce_pubblicata_senza_statistiche_non_si_confronta(self):
        old = {k: v for k, v in self.PUBLISHED.items() if k != "stats"}
        _, entry = self.build(hundred(population=False), old)
        self.assertEqual("2026.10.30", entry["version"])


if __name__ == "__main__":
    unittest.main()
