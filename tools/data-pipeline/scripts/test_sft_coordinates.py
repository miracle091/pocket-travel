#!/usr/bin/env python3
"""Test di cached_coordinates (generate_sft.py): le coordinate di Wikidata si scaricano una volta e si rileggono dalla
cache, anche per i titoli che Wikidata non ha, cosi' due generazioni con lo stesso seme danno lo stesso file.

Uso: python test_sft_coordinates.py
"""
import tempfile
import unittest
from pathlib import Path

from generate_sft import cached_coordinates


class CachedCoordinatesTest(unittest.TestCase):
    def test_scarica_solo_i_titoli_nuovi(self):
        calls = []

        def fetch(titles, lang):
            calls.append(sorted(titles))
            return {t: (45.0, 7.6) for t in titles if t == "Torino"}

        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / "coordinates.it.json"
            self.assertEqual(cached_coordinates({"Torino", "Paesino"}, "it", cache, fetch), {"Torino": (45.0, 7.6)})
            self.assertEqual(cached_coordinates({"Torino", "Paesino", "Genova"}, "it", cache, fetch), {"Torino": (45.0, 7.6)})
            self.assertEqual(calls, [["Paesino", "Torino"], ["Genova"]])

    def test_errore_di_rete_non_scrive_la_cache(self):
        def fetch(titles, lang):
            raise OSError("rete assente")

        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp) / "coordinates.it.json"
            with self.assertRaises(OSError):
                cached_coordinates({"Torino"}, "it", cache, fetch)
            self.assertFalse(cache.exists())


if __name__ == "__main__":
    unittest.main()
