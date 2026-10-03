#!/usr/bin/env python3
"""Test di city_wikipedia.py: estrazione delle sezioni dal wikitext e annotazione dei JSONL, senza rete (le chiamate
alle API sono sostituite con unittest.mock).

Uso: python test_city_wikipedia.py
"""
import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

import city_wikipedia

VOCE = """Rimini e' una citta'.
== Geografia fisica ==
Testo.
=== Clima ===
Clima temperato.
{{Tabella climatica|gen=3}}
== Storia ==
Colonia romana.
=== Medioevo ===
I Malatesta.
== Monumenti ==
=== Tempio ===
==== Storia ====
Storia del tempio.
"""


class SectionsTest(unittest.TestCase):
    def test_storia_con_sottosezioni_e_clima_annidato_come_titoli_di_livello_due(self):
        self.assertEqual(
            "== Storia ==\nColonia romana.\n=== Medioevo ===\nI Malatesta.\n"
            "== Clima ==\nClima temperato.\n{{Tabella climatica|gen=3}}",
            city_wikipedia.sections(VOCE, ("storia", "clima")))

    def test_sezione_assente_o_vuota_omessa(self):
        self.assertEqual("", city_wikipedia.sections("== Storia ==\n\n== Altro ==\nx", ("storia", "clima")))
        self.assertEqual("", city_wikipedia.sections("Solo incipit.", ("storia",)))

    def test_titoli_in_maiuscolo_e_con_spazi(self):
        self.assertEqual("== History ==\nRoman.", city_wikipedia.sections("==  History  ==\nRoman.", ("history",)))


class WikipediaTitlesTest(unittest.TestCase):
    def test_sitelink_della_voce_dall_elemento_della_pagina_wikivoyage(self):
        with mock.patch("city_wikipedia.city_population.wikibase_items", return_value={"Rimini": "Q18", "Borgo": "Q9"}), \
                mock.patch("city_wikipedia.city_population._get",
                           return_value={"entities": {"Q18": {"sitelinks": {"itwiki": {"title": "Rimini"}}},
                                                      "Q9": {"sitelinks": {}}}}) as get, \
                mock.patch("city_wikipedia.time.sleep"):
            self.assertEqual({"Rimini": "Rimini"}, city_wikipedia.wikipedia_titles(["Rimini", "Borgo"], "it"))
        self.assertIn("sitefilter=itwiki", get.call_args[0][0])


class DeadlineTitlesTest(unittest.TestCase):
    def test_la_scadenza_vale_anche_per_i_titoli(self):
        with mock.patch("city_wikipedia.city_population.wikibase_items", return_value={"Rimini": "Q18"}) as items, \
                mock.patch("city_wikipedia.city_population._get") as get, \
                mock.patch("city_wikipedia.time.monotonic", return_value=100.0), \
                redirect_stderr(io.StringIO()) as err:
            self.assertEqual({}, city_wikipedia.wikipedia_titles(["Rimini"], "it", deadline=50.0))
        self.assertEqual(50.0, items.call_args[0][2])
        get.assert_not_called()
        self.assertIn("tempo esaurito, 1 sitelink", err.getvalue())


class WikipediaSectionsTest(unittest.TestCase):
    def test_segue_continue_e_tiene_solo_le_voci_con_sezioni(self):
        def page(title, text):
            return {"title": title, "revisions": [{"slots": {"main": {"content": text}}}]}
        responses = [
            {"continue": {"rvcontinue": "2|x", "continue": "||"},
             "query": {"pages": [page("Rimini", VOCE), {"title": "Roma"}]}},
            {"query": {"pages": [page("Roma", "== Clima ==\nMite."), page("Borgo", "Nessuna sezione.")]}},
        ]
        with mock.patch("city_wikipedia.city_population._get", side_effect=responses) as get, \
                mock.patch("city_wikipedia.time.sleep"):
            result = city_wikipedia.wikipedia_sections(["Rimini", "Roma", "Borgo"], "it")
        self.assertEqual({"Rimini", "Roma"}, set(result))
        self.assertEqual("== Clima ==\nMite.", result["Roma"])
        self.assertIn("rvcontinue=2%7Cx", get.call_args_list[1][0][0])


    def test_dopo_la_scadenza_non_inizia_altri_lotti(self):
        titles = [f"Citta {i}" for i in range(city_wikipedia.CONTENT_BATCH + 1)]
        page = {"title": "Citta 0", "revisions": [{"slots": {"main": {"content": "== Storia ==\nx"}}}]}
        # monotonic: entro la scadenza per il primo lotto, oltre per il secondo
        with mock.patch("city_wikipedia.city_population._get", return_value={"query": {"pages": [page]}}) as get, \
                mock.patch("city_wikipedia.time.sleep"), \
                mock.patch("city_wikipedia.time.monotonic", side_effect=[0.0, 100.0]), \
                redirect_stderr(io.StringIO()) as err:
            result = city_wikipedia.wikipedia_sections(titles, "it", deadline=50.0)
        self.assertEqual(1, get.call_count)
        self.assertEqual({"Citta 0"}, set(result))
        self.assertIn("tempo esaurito, 1 voci", err.getvalue())


class AnnotateTest(unittest.TestCase):
    def test_aggiunge_wikipedia_solo_alle_citta_con_sezioni(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "x.jsonl"
            path.write_text('{"city": "Rimini", "text": "a"}\n{"city": "Borgo", "text": "b"}\n', encoding="utf-8")
            with mock.patch("city_wikipedia.wikipedia_titles", return_value={"Rimini": "Rimini", "Borgo": "Borgo (Italia)"}) as titles, \
                    mock.patch("city_wikipedia.wikipedia_sections", return_value={"Rimini": "== Storia ==\nx"}), \
                    redirect_stdout(io.StringIO()) as out:
                city_wikipedia.annotate([path], "it")
            rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        self.assertEqual({"title": "Rimini", "text": "== Storia ==\nx"}, rows[0]["wikipedia"])
        self.assertNotIn("wikipedia", rows[1])
        self.assertIn("Wikipedia per 1/2", out.getvalue())
        self.assertIsNotNone(titles.call_args[0][2])  # la scadenza arriva anche alla ricerca dei titoli

    def test_errore_di_rete_non_e_fatale(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "x.jsonl"
            path.write_text('{"city": "Rimini", "text": "a"}\n', encoding="utf-8")
            with mock.patch("city_wikipedia.wikipedia_titles", side_effect=OSError("rete")), \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()) as err:
                city_wikipedia.annotate([path], "it")
            rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        self.assertEqual([{"city": "Rimini", "text": "a"}], rows)
        self.assertIn("non disponibile", err.getvalue())


if __name__ == "__main__":
    unittest.main()
