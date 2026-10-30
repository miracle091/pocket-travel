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

    def test_sezione_scelta_dentro_un_altra_esce_solo_come_sezione_sua(self):
        voce = "== Tradizioni ==\nUsanze.\n=== Galateo ===\nInchini.\n=== Gastronomia ===\nSushi.\n=== Feste ===\nMatsuri.\n== Sport ==\nx"
        self.assertEqual(
            "== Tradizioni ==\nUsanze.\n=== Galateo ===\nInchini.\n=== Feste ===\nMatsuri.\n== Gastronomia ==\nSushi.",
            city_wikipedia.sections(voce, ("tradizioni", "gastronomia")))


class WikipediaTitlesTest(unittest.TestCase):
    def test_sitelink_della_voce_dall_elemento_della_pagina_wikivoyage(self):
        with mock.patch("city_wikipedia.city_population.wikibase_items", return_value={"Rimini": "Q18", "Borgo": "Q9"}), \
                mock.patch("city_wikipedia.city_population._get",
                           return_value={"entities": {"Q18": {"sitelinks": {"itwiki": {"title": "Rimini"}}},
                                                      "Q9": {"sitelinks": {}}}}) as get, \
                mock.patch("city_wikipedia.time.sleep"):
            self.assertEqual({"Rimini": "Rimini"}, city_wikipedia.wikipedia_titles(["Rimini", "Borgo"], "it"))
        self.assertIn("sitefilter=itwiki", get.call_args[0][0])

    def test_pagine_wikivoyage_di_un_altra_lingua(self):
        with mock.patch("city_wikipedia.city_population.wikibase_items", return_value={"Japan": "Q17"}) as items, \
                mock.patch("city_wikipedia.city_population._get",
                           return_value={"entities": {"Q17": {"sitelinks": {"itwiki": {"title": "Giappone"}}}}}), \
                mock.patch("city_wikipedia.time.sleep"):
            self.assertEqual({"Japan": "Giappone"}, city_wikipedia.wikipedia_titles(["Japan"], "it", voyage_lang="en"))
        self.assertEqual("en", items.call_args[0][1])


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

    def test_sezioni_scelte_e_voci_senza_sezioni_con_keep_empty(self):
        def page(title, text):
            return {"title": title, "revisions": [{"slots": {"main": {"content": text}}}]}
        response = {"query": {"pages": [page("Giappone", VOCE + "== Gastronomia ==\nSushi."), page("Borgo", "Niente.")]}}
        with mock.patch("city_wikipedia.city_population._get", return_value=response), \
                mock.patch("city_wikipedia.time.sleep"):
            result = city_wikipedia.wikipedia_sections(["Giappone", "Borgo"], "it", names=("gastronomia",), keep_empty=True)
        self.assertEqual({"Giappone": "== Gastronomia ==\nSushi.", "Borgo": ""}, result)

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

    def test_un_lotto_fallito_non_fa_perdere_gli_altri(self):
        titles = [f"Citta {i}" for i in range(city_wikipedia.CONTENT_BATCH + 1)]
        page = {"title": "Citta 0", "revisions": [{"slots": {"main": {"content": "== Storia ==\nx"}}}]}
        with mock.patch("city_wikipedia.city_population._get", side_effect=[{"query": {"pages": [page]}}, OSError("rete")]), \
                mock.patch("city_wikipedia.time.sleep"), \
                redirect_stderr(io.StringIO()) as err:
            result = city_wikipedia.wikipedia_sections(titles, "it")
        self.assertEqual({"Citta 0"}, set(result))
        self.assertIn("lotto di voci saltato", err.getvalue())


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


def _it_table(**extra):
    params = {f"tempmax{m:02d}": f"{10 + m},5" for m in range(1, 13)}
    params.update({f"tempmin{m:02d}": f"{m - 3}.0" for m in range(1, 13)})
    params.update({f"pioggia{m:02d}": "1.234,5" if m == 1 else "50" for m in range(1, 13)})
    params.update({f"giornipioggia{m:02d}": "7" for m in range(1, 13)})
    params.update(extra)
    return "{{ClimaAnnuale\n" + "\n".join(f"|{k} = {v}" for k, v in params.items()) + "\n}}"


EN_C = """Intro.
{{Weather box
|location = Paris
|Jan high C = 7.2
|Jan low C = &minus;1.5
|Jan precipitation mm = 1,234.5 <ref>{{cite web|url=http://x|title=y}}</ref>
|Jan precipitation days = 9<!-- nota -->
|Feb high C = {{convert|8|C|F}}
|Feb low C = {{refn|solo stima}}
|Feb rain days = 8
|Dec low C = −3.0
|Dec precipitation mm = trace
}}
{{Weather box
|Jan high C = 99
}}
"""

EN_F = "{{weather box|Jan high F = 50|Jan low F = 32|Jan precipitation inch = 2|Jan precipitation days = 3}}"


class ClimateTest(unittest.TestCase):
    def test_italiano_con_virgola_decimale_e_migliaia(self):
        months = city_wikipedia.climate("== Clima ==\n" + _it_table(giornipioggia03=""), "it")
        self.assertEqual(12, len(months))
        self.assertEqual({"tempMax": 11.5, "tempMin": -2.0, "precipMm": 1234.5, "rainDays": 7.0}, months[0])
        self.assertIsNone(months[2]["rainDays"])

    def test_italiano_nome_con_maiuscole_e_spazi_diversi(self):
        for name in ("{{Clima annuale|", "{{ clima  Annuale |"):
            self.assertIsNotNone(city_wikipedia.climate(name + "tempmax01=5}}", "it"), name)

    def test_inglese_celsius_meno_unicode_template_annidati_e_prima_tabella(self):
        months = city_wikipedia.climate(EN_C, "en")
        self.assertEqual({"tempMax": 7.2, "tempMin": -1.5, "precipMm": 1234.5, "rainDays": 9.0}, months[0])
        self.assertEqual({"tempMax": 8.0, "tempMin": None, "precipMm": None, "rainDays": 8.0}, months[1])
        self.assertEqual(-3.0, months[11]["tempMin"])
        self.assertIsNone(months[11]["precipMm"])
        self.assertIsNone(months[2]["tempMax"])  # la seconda tabella (99) non conta

    def test_inglese_solo_fahrenheit_e_pollici(self):
        self.assertEqual({"tempMax": 10.0, "tempMin": 0.0, "precipMm": 50.8, "rainDays": 3.0},
                         city_wikipedia.climate(EN_F, "en")[0])

    def test_italiano_commento_con_graffe_e_valori_in_elenco(self):
        # Come Torino: "}}" dentro un commento non chiude il template.
        torino = "{{ClimaAnnuale\n<!-- misurato in|u=°C}}) -->\n| tempmax01 = 3.7\n| tempmin01 = -1.9\n}}"
        self.assertEqual({"tempMax": 3.7, "tempMin": -1.9, "precipMm": None, "rainDays": None},
                         city_wikipedia.climate(torino, "it")[0])
        # Come Napoli e Brescia: i 12 mesi in un solo parametro, separati da virgole.
        napoli = "{{ClimaAnnuale\n| tempmax = 12, 12, 15, 21, 26, 30, 33, 33, 28, 24, 16, 13.5\n| pioggia = 1,2\n}}"
        months = city_wikipedia.climate(napoli, "it")
        self.assertEqual((12.0, 33.0, 13.5), (months[0]["tempMax"], months[6]["tempMax"], months[11]["tempMax"]))
        # Un elenco che non ha 12 valori non vale come elenco.
        self.assertIsNone(months[0]["precipMm"])

    def test_senza_tabella_o_senza_valori_nessun_clima(self):
        self.assertIsNone(city_wikipedia.climate("== Climate ==\nMite {{convert|5|km}}.", "en"))
        self.assertIsNone(city_wikipedia.climate("{{Weather box|location=x|Jan high C = n/a}}", "en"))
        self.assertIsNone(city_wikipedia.climate("{{ClimaAnnuale|tempmax01=", "it"))

    def test_template_a_parte(self):
        self.assertEqual("Paris weatherbox", city_wikipedia.weatherbox_template("x {{Paris weatherbox}} y"))
        self.assertIsNone(city_wikipedia.weatherbox_template("{{Weather box|Jan high C=1}}"))
        self.assertIsNone(city_wikipedia.weatherbox_template("nulla"))


def _page(title, text):
    return {"title": title, "revisions": [{"slots": {"main": {"content": text}}}]}


class WeatherboxTablesTest(unittest.TestCase):
    def test_clima_dalle_voci_e_dai_template_a_parte_senza_riscaricare_le_voci(self):
        responses = [
            {"query": {"pages": [_page("Rimini", "== Climate ==\n" + EN_F), _page("Paris", "x {{Paris weatherbox}}"),
                                 _page("Borgo", "== History ==\nx")]}},
            {"query": {"pages": [_page("Template:Paris weatherbox", EN_F)]}},
        ]
        climates = {}
        with mock.patch("city_wikipedia.city_population._get", side_effect=responses) as get, \
                mock.patch("city_wikipedia.time.sleep"):
            result = city_wikipedia.wikipedia_sections(["Rimini", "Paris", "Borgo"], "en", climates=climates)
            self.assertEqual({"Rimini", "Borgo"}, set(result))
            self.assertEqual("Paris weatherbox", climates["Paris"])
            city_wikipedia.weatherbox_tables(climates, "en")
        self.assertEqual({"Rimini", "Paris"}, set(climates))
        self.assertEqual(10.0, climates["Paris"][0]["tempMax"])
        self.assertIn("Template%3AParis+weatherbox", get.call_args_list[1][0][0])

    def test_template_illeggibile_o_lotto_fallito_tolgono_la_voce(self):
        climates = {"Paris": "Paris weatherbox"}
        with mock.patch("city_wikipedia.city_population._get", side_effect=OSError("rete")), \
                mock.patch("city_wikipedia.time.sleep"), redirect_stderr(io.StringIO()) as err:
            city_wikipedia.weatherbox_tables(climates, "en")
        self.assertEqual({}, climates)
        self.assertIn("lotto di template saltato", err.getvalue())


class AnnotateClimateTest(unittest.TestCase):
    def test_climate_nelle_righe_anche_senza_sezioni(self):
        months = city_wikipedia.climate(EN_F, "en")

        def sections_of(titles, lang, deadline=None, climates=None):
            climates["Rimini"] = months
            return {"Borgo": "== History ==\nx"}
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "x.jsonl"
            path.write_text('{"city": "Rimini"}\n{"city": "Borgo"}\n', encoding="utf-8")
            with mock.patch("city_wikipedia.wikipedia_titles", return_value={"Rimini": "Rimini", "Borgo": "Borgo"}), \
                    mock.patch("city_wikipedia.wikipedia_sections", side_effect=sections_of), \
                    redirect_stdout(io.StringIO()) as out:
                city_wikipedia.annotate([path], "en")
            rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        self.assertEqual({"title": "Rimini", "months": months}, rows[0]["climate"])
        self.assertNotIn("wikipedia", rows[0])
        self.assertNotIn("climate", rows[1])
        self.assertIn("wikipedia", rows[1])
        self.assertIn("tabella climatica da Wikipedia per 1/2", out.getvalue())


if __name__ == "__main__":
    unittest.main()
