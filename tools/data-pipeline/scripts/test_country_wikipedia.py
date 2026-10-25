#!/usr/bin/env python3
"""Test di country_wikipedia.py: regioni lette, senza voce e con errore di rete, senza rete (le chiamate alle API sono
sostituite con unittest.mock).

Uso: python test_country_wikipedia.py
"""
import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

import country_wikipedia


class CollectTest(unittest.TestCase):
    def test_voce_dalla_pagina_wikivoyage_en_e_regioni_senza_voce_assenti(self):
        with mock.patch("country_wikipedia.city_wikipedia.wikipedia_titles", return_value={"Japan": "Giappone", "Kenya": "Kenya"}) as titles, \
                mock.patch("country_wikipedia.city_wikipedia.wikipedia_sections",
                           return_value={"Giappone": "== Gastronomia ==\nSushi.", "Kenya": ""}) as sections:
            rows = country_wikipedia.collect({"giappone": "Japan", "kenya": "Kenya", "atlantide": "Atlantis"}, "it")
        self.assertEqual([{"regionId": "giappone", "title": "Giappone", "text": "== Gastronomia ==\nSushi."},
                          {"regionId": "kenya", "title": "Kenya", "text": ""}], rows)
        self.assertEqual("en", titles.call_args.kwargs["voyage_lang"])
        self.assertEqual(country_wikipedia.SECTIONS["it"], sections.call_args.kwargs["names"])
        self.assertTrue(sections.call_args.kwargs["keep_empty"])


class MainTest(unittest.TestCase):
    def run_main(self, d, **patch):
        tsv, out = Path(d) / "r.tsv", Path(d) / "o.jsonl"
        tsv.write_text("giappone\tJapan\nkenya\tKenya\n", encoding="utf-8")
        with mock.patch("country_wikipedia.collect", **patch) as collect, \
                redirect_stdout(io.StringIO()) as stdout, redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(0, country_wikipedia.main(["it", str(tsv), str(out)]))
        return collect, [json.loads(l) for l in out.read_text(encoding="utf-8").splitlines()], stdout.getvalue(), stderr.getvalue()

    def test_scrive_una_riga_per_regione_letta(self):
        with tempfile.TemporaryDirectory() as d:
            collect, rows, out, _ = self.run_main(d, return_value=[{"regionId": "giappone", "title": "Giappone", "text": "x"}])
        self.assertEqual({"giappone": "Japan", "kenya": "Kenya"}, collect.call_args[0][0])
        self.assertEqual("giappone", rows[0]["regionId"])
        self.assertIn("letta per 1/2 regioni, 1 con sezioni", out)

    def test_errore_di_rete_non_e_fatale(self):
        with tempfile.TemporaryDirectory() as d:
            _, rows, _, err = self.run_main(d, side_effect=OSError("rete"))
        self.assertEqual([], rows)
        self.assertIn("non disponibile", err)

    def test_lingua_sconosciuta(self):
        with redirect_stderr(io.StringIO()):
            self.assertEqual(2, country_wikipedia.main(["fr", "a", "b"]))


if __name__ == "__main__":
    unittest.main()
