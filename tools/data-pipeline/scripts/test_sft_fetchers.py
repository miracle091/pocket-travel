#!/usr/bin/env python3
"""Test delle funzioni di lettura delle fonti di generate_sft_dataset.py senza rete: pagine di Viaggiare Sicuri, ricerca
dei langlink su Wikivoyage e Wikipedia, elenco dei titoli in sft-sources.tsv, domande fuori tema in cache, citta' dal
Quickbar. get() e le attese sono sostituiti da mock.

Uso: python test_sft_fetchers.py
"""
import json
import tempfile
import unittest
import urllib.error
import urllib.parse
from pathlib import Path
from unittest import mock

import generate_sft_dataset as g


def pages_response(**page):
    return json.dumps({"query": {"pages": {"1": page}}})


class ParseVsTest(unittest.TestCase):
    def test_sezioni_nell_ordine_del_sito_con_html_e_entita(self):
        raw = json.dumps({
            "infoSicurezza": {"nodi": {
                "b": {"ordinamento": 2, "contenuto": "<p>Evitare le zone <b>isolate</b>.</p>"},
                "a": {"ordinamento": 1, "contenuto": "<p>Terrorismo &amp; criminalit&agrave;.</p>"}}},
            "infoMobilita": {"nodi": {"x": {"contenuto": "Poliz<span class=\"c\">ia</span> locale"}}},
            "infoSituazioneSanitaria": {"nodi": {}},
            "infoRequisitiIngresso": None,
        })
        self.assertEqual([("SICUREZZA", "Terrorismo & criminalità. Evitare le zone isolate ."),
                          ("TRASPORTI", "Polizia locale")], g.parse_vs(raw))

    def test_pagina_senza_sezioni(self):
        self.assertEqual([], g.parse_vs("{}"))

    def test_nodo_senza_contenuto_e_spazi_multipli(self):
        raw = json.dumps({"infoSicurezza": {"nodi": {"a": {"contenuto": None}, "b": {"contenuto": "  Due   \n righe "}}}})
        self.assertEqual([("SICUREZZA", "Due righe")], g.parse_vs(raw))


class FetchVsTest(unittest.TestCase):
    def test_pagina_e_url(self):
        with mock.patch.object(g, "get", return_value="{}") as get:
            raw, url = g.fetch_vs("ITA")
        self.assertEqual("{}", raw)
        self.assertTrue(get.call_args.args[0].endswith("/schede_paese/ITA.json"))
        self.assertTrue(url.endswith("/find-country/country/ITA"))

    def test_404_e_pagina_assente_gli_altri_errori_salgono(self):
        def error(code):
            return urllib.error.HTTPError("https://x", code, "x", {}, None)

        with mock.patch.object(g, "get", side_effect=error(404)):
            self.assertIsNone(g.fetch_vs("XXX"))
        with mock.patch.object(g, "get", side_effect=error(503)):
            with self.assertRaises(urllib.error.HTTPError):
                g.fetch_vs("XXX")


class FetchWikivoyageTest(unittest.TestCase):
    def test_langlink_e_titolo_con_spazi_e_accenti(self):
        responses = [pages_response(langlinks=[{"lang": "it", "*": "Città del Messico"}]), "== Sicurezza ==\nTesto."]
        with mock.patch.object(g, "get", side_effect=responses) as get:
            text, url = g.fetch_it("Mexico City")
        self.assertEqual("== Sicurezza ==\nTesto.", text)
        self.assertEqual("https://it.wikivoyage.org/wiki/" + urllib.parse.quote("Città_del_Messico"), url)
        self.assertIn("lllang=it", get.call_args_list[0].args[0])

    def test_lingua_scelta(self):
        responses = [pages_response(langlinks=[{"lang": "de", "*": "Wien"}]), "Text"]
        with mock.patch.object(g, "get", side_effect=responses):
            _, url = g.fetch_it("Vienna", lang="de")
        self.assertTrue(url.startswith("https://de.wikivoyage.org/wiki/"))

    def test_senza_langlink_o_pagina_vuota_nessun_risultato(self):
        with mock.patch.object(g, "get", return_value=pages_response(title="X")):
            self.assertIsNone(g.fetch_it("X"))
        responses = [pages_response(langlinks=[{"lang": "it", "*": "X"}]), "  \n"]
        with mock.patch.object(g, "get", side_effect=responses):
            self.assertIsNone(g.fetch_it("X"))

    def test_pagina_inglese(self):
        with mock.patch.object(g, "get", return_value="Testo") as get:
            self.assertEqual(("Testo", "https://en.wikivoyage.org/wiki/New%20York"), g.fetch_en("New York"))
        self.assertIn("title=New%20York", get.call_args.args[0])
        with mock.patch.object(g, "get", return_value="\n"):
            self.assertIsNone(g.fetch_en("Vuota"))


class FetchWpTest(unittest.TestCase):
    def test_salta_titoli_senza_la_parola_del_tema_dopo_il_redirect(self):
        # "Fujian cuisine" rinvia alla voce del paese: la pagina finale non ha "cuisine" nel titolo
        responses = [
            pages_response(title="Cuisine of Fujian", missing=""),
            pages_response(title="Fujian", langlinks=[{"lang": "it", "*": "Fujian"}]),
        ]
        with mock.patch.object(g, "get", side_effect=responses):
            self.assertIsNone(g.fetch_wp_it("Fujian", "CIBO_BEVANDE"))

    def test_primo_titolo_valido(self):
        responses = [pages_response(title="Cuisine of Italy", langlinks=[{"lang": "it", "*": "Cucina italiana"}]),
                     "Testo della cucina."]
        with mock.patch.object(g, "get", side_effect=responses):
            text, url = g.fetch_wp_it("Italy", "CIBO_BEVANDE")
        self.assertEqual("Testo della cucina.", text)
        self.assertEqual("https://it.wikipedia.org/wiki/Cucina_italiana", url)

    def test_pagina_italiana_vuota(self):
        responses = [pages_response(title="Culture of Italy", langlinks=[{"lang": "it", "*": "Cultura"}]), " "]
        with mock.patch.object(g, "get", side_effect=responses):
            self.assertIsNone(g.fetch_wp_it("Italy", "USI_COSTUMI"))

    def test_senza_langlink_prova_il_titolo_successivo(self):
        responses = [pages_response(title="Cuisine of Fiji"),
                     pages_response(title="Fiji cuisine", langlinks=[{"lang": "it", "*": "Cucina figiana"}]), "Testo"]
        with mock.patch.object(g, "get", side_effect=responses):
            _, url = g.fetch_wp_it("Fiji", "CIBO_BEVANDE")
        self.assertTrue(url.endswith("Cucina_figiana"))


class SourcesFileTest(unittest.TestCase):
    def test_salvataggio_ordinato_e_rilettura(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "sft-sources.tsv"
            with mock.patch.object(g, "SOURCES_TSV", path):
                self.assertEqual({}, g.load_sources())      # file assente
                sources = {("b", "it"): "Città di B", ("a", "wp_cibo"): "-", ("a", "en"): "A"}
                g.save_sources(sources)
                self.assertEqual(sources, g.load_sources())
            lines = path.read_text(encoding="utf-8").splitlines()
        self.assertEqual("regionId\tsource\ttitle", lines[0])
        self.assertEqual(["a\ten\tA", "a\twp_cibo\t-", "b\tit\tCittà di B"], lines[1:])

    def test_righe_vuote_ignorate_alla_lettura(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "s.tsv"
            path.write_text("regionId\tsource\ttitle\n\na\tit\tTitolo\n   \n", encoding="utf-8")
            with mock.patch.object(g, "SOURCES_TSV", path):
                self.assertEqual({("a", "it"): "Titolo"}, g.load_sources())


class CityParentsTest(unittest.TestCase):
    def test_luoghi_che_contengono_la_citta(self):
        text = "{{QuickbarCity\n|Stato=[[Stati Uniti d'America]]\n|Stato federato = [[Texas|TX]]\n|Regione=[[Hill Country#Nord]]\n|Altro=[[X]]\n}}"
        self.assertEqual({"Stati Uniti d'America", "Texas", "Hill Country"}, g.city_parents(text))

    def test_pagina_che_non_e_una_citta(self):
        self.assertIsNone(g.city_parents("{{Quickbar|Stato=[[Italia]]}}"))
        self.assertIsNone(g.city_parents("Testo normale"))

    def test_citta_senza_campi_geografici(self):
        self.assertEqual(set(), g.city_parents("  {{quickbarcity\n|Nome=Roma\n}}"))


class OffTopicTest(unittest.TestCase):
    SOURCES = {"prova": ("ds/prova", "default", "train", "q", "MIT", 3)}

    def run_fetch(self, rows_by_offset, keep=3):
        sources = {"prova": ("ds/prova", "default", "train", "q", "MIT", keep)}
        calls = []

        def get(url):
            offset = int(urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["offset"][0])
            calls.append(offset)
            return json.dumps({"rows": [{"row": r} for r in rows_by_offset.get(offset, [])]})

        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "raw").mkdir()
            with mock.patch.object(g, "OUT", Path(tmp)), mock.patch.object(g, "get", side_effect=get), \
                    mock.patch.object(g, "require_revision") as revision, mock.patch("time.sleep"), \
                    mock.patch.dict(g.OFF_TOPIC_REVISIONS, {"ds/prova": "a" * 40}):
                result = g.fetch_off_topic(sources)
                again = g.fetch_off_topic(sources)
                cached = (Path(tmp) / "raw" / "offtopic_prova.txt").read_text(encoding="utf-8")
        return result, again, cached, calls, revision

    def test_filtri_lunghezza_a_capo_temi_di_viaggio_e_input_alpaca(self):
        rows = [{"q": "Chi ha dipinto la Gioconda?"},
                {"q": "Corta"},                                   # meno di 10 caratteri
                {"q": "x" * 161},                                 # troppo lunga
                {"q": "Prima riga\nseconda riga lunga abbastanza"},
                {"q": "Quali sono le migliori mete per le vacanze?"},   # radice di viaggio
                {"q": "Dove posso trovare un ospedale vicino?"},        # parola chiave di una categoria
                {"q": "Qual è la capitale dell'Australia?"},
                {"q": "  Perché il cielo è azzurro?  "},
                {"q": "Quanto pesa un elefante adulto?"}]
        result, again, cached, calls, revision = self.run_fetch({0: rows})
        self.assertEqual(["Chi ha dipinto la Gioconda?", "Qual è la capitale dell'Australia?",
                          "Perché il cielo è azzurro?"], result["prova"])
        self.assertEqual(result, again)
        self.assertEqual([0], calls)                     # la seconda chiamata legge la cache
        revision.assert_called_once()
        self.assertEqual(3, len(cached.splitlines()))

    def test_continua_alla_pagina_successiva_fino_alla_quota_e_si_ferma_su_pagina_vuota(self):
        rows0 = [{"q": f"Domanda numero {i} di prova?"} for i in range(2)]
        rows1 = [{"q": "Domanda numero 2 di prova?"}, {"q": "Domanda numero 3 di prova?"}]
        result, _, _, calls, _ = self.run_fetch({0: rows0, 100: rows1})
        self.assertEqual(3, len(result["prova"]))
        self.assertEqual([0, 100], calls)
        result, _, _, calls, _ = self.run_fetch({0: rows0}, keep=10)
        self.assertEqual(2, len(result["prova"]))
        self.assertEqual([0, 100], calls)                 # pagina vuota: fine

    def test_colonna_instruction_scarta_le_righe_con_input(self):
        sources = {"alpaca": ("ds/alpaca", "it", "train", "instruction", "CC", 5)}
        rows = [{"instruction": "Spiega la fotosintesi clorofilliana.", "input": ""},
                {"instruction": "Riassumi questo testo seguente.", "input": "Testo da riassumere"}]
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "raw").mkdir()
            with mock.patch.object(g, "OUT", Path(tmp)), mock.patch.object(g, "require_revision"), mock.patch("time.sleep"), \
                    mock.patch.dict(g.OFF_TOPIC_REVISIONS, {"ds/alpaca": "b" * 40}), \
                    mock.patch.object(g, "get", side_effect=[json.dumps({"rows": [{"row": r} for r in rows]}),
                                                             json.dumps({"rows": []})]):
                result = g.fetch_off_topic(sources)
        self.assertEqual(["Spiega la fotosintesi clorofilliana."], result["alpaca"])

    def test_errore_429_si_riprova_altri_4xx_salgono(self):
        sources = {"prova": ("ds/prova", "default", "train", "q", "MIT", 1)}
        too_many = urllib.error.HTTPError("https://x", 429, "x", {}, None)
        not_found = urllib.error.HTTPError("https://x", 404, "x", {}, None)
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "raw").mkdir()
            with mock.patch.object(g, "OUT", Path(tmp)), mock.patch.object(g, "require_revision"), \
                    mock.patch("time.sleep") as sleep, mock.patch.dict(g.OFF_TOPIC_REVISIONS, {"ds/prova": "c" * 40}):
                with mock.patch.object(g, "get", side_effect=[too_many, json.dumps({"rows": [{"row": {"q": "Domanda valida di prova?"}}]})]):
                    self.assertEqual(["Domanda valida di prova?"], g.fetch_off_topic(sources)["prova"])
                self.assertIn(mock.call(15), sleep.call_args_list)
                (Path(tmp) / "raw" / "offtopic_prova.txt").unlink()
                with mock.patch.object(g, "get", side_effect=not_found):
                    with self.assertRaises(urllib.error.HTTPError):
                        g.fetch_off_topic(sources)


class WpHelpersTest(unittest.TestCase):
    def test_titoli_candidati_per_ogni_tema(self):
        self.assertEqual(["Telecommunications in the Gambia", "Communications in the Gambia"],
                         g.wp_candidate_titles("The_Gambia", "CONNETTIVITA"))
        self.assertEqual(["Mass media in Italy", "Media of Italy"], g.wp_candidate_titles("Italy", "VITA_QUOTIDIANA"))
        self.assertEqual(["Cuisine of the Bahamas", "The Bahamas cuisine"], g.wp_candidate_titles("The_Bahamas", "CIBO_BEVANDE"))

    def test_ogni_categoria_ha_un_suffisso_di_cache(self):
        self.assertEqual(set(g.WP_TITLE_PATTERNS), set(g.WP_LANG_SUFFIX))


if __name__ == "__main__":
    unittest.main()
