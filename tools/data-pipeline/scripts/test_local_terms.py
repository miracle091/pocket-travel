#!/usr/bin/env python3
"""Test di local_terms.py: nomi dei termini, confronto con il testo della guida, taglio dell'introduzione, filtri delle
voci e righe del JSONL, senza rete (le chiamate alle API sono sostituite con unittest.mock).

Uso: python test_local_terms.py
"""
import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

import local_terms


class FoldTest(unittest.TestCase):
    def test_senza_accenti_maiuscole_e_punteggiatura(self):
        self.assertEqual(" pisco sour e aji de gallina ", local_terms.fold("Pisco-sour, e Ají de GALLINA!"))


class NamesTest(unittest.TestCase):
    def test_titolo_e_redirect_senza_parentesi_e_senza_doppioni(self):
        self.assertEqual(["Chupe", "Chupe de camarones"],
                         local_terms.names_of("Chupe (food)", ["Chupe de camarones", "Chupe"], existing=set()))

    def test_nome_senza_parentesi_che_e_un_altra_voce_scartato(self):
        self.assertEqual(["Tiradito peruano"],
                         local_terms.names_of("Mate (bevanda)", ["Tiradito peruano"], existing={"Mate"}))

    def test_nomi_troppo_corti_scartati(self):
        self.assertEqual(["Pisco"], local_terms.names_of("Pisco", ["Pi", "A-b"], existing=set()))


class MatchedTest(unittest.TestCase):
    def test_solo_parola_intera_senza_accenti(self):
        text = local_terms.fold("Da provare l'ají de gallina e il sushibar del porto.")
        self.assertEqual(["Ají de gallina"], local_terms.matched(text, ["Ají de gallina", "Sushi", "gallin"]))


class CutSummaryTest(unittest.TestCase):
    def test_testo_corto_intero(self):
        self.assertEqual("Uno. Due.", local_terms.cut_summary("Uno.\n Due.", limit=50))

    def test_tagliato_all_ultima_frase_entro_il_limite(self):
        self.assertEqual("Prima frase. Seconda.", local_terms.cut_summary("Prima frase. Seconda. Terza lunga.", limit=25))

    def test_prima_frase_intera_anche_oltre_il_limite(self):
        self.assertEqual("Una frase molto lunga.", local_terms.cut_summary("Una frase molto lunga. Altra.", limit=5))

    def test_senza_frasi_tagliato_a_parola_intera(self):
        self.assertEqual("parole senza…", local_terms.cut_summary("parole senza punto finale", limit=14))


class CountriesTest(unittest.TestCase):
    def test_paese_con_codice_iso_e_regione_col_suo_paese(self):
        rows = [{"r": {"value": "http://www.wikidata.org/entity/Q419"}, "iso": {"value": "PE"}},
                {"r": {"value": "http://www.wikidata.org/entity/Q1"}, "c": {"value": "http://www.wikidata.org/entity/Q17"}},
                {"r": {"value": "http://www.wikidata.org/entity/Q9"}}]
        with mock.patch("local_terms._sparql", return_value=rows):
            self.assertEqual({"Q419": "Q419", "Q1": "Q17"}, local_terms.countries(["Q419", "Q1", "Q9"]))


class CategoriesTest(unittest.TestCase):
    def test_categorie_degli_argomenti_e_della_cucina_nazionale_con_la_sua_voce(self):
        topic = [{"page": {"value": "https://it.wikipedia.org/wiki/Categoria:Dolci_peruviani"}}]
        cuisine = [{"page": {"value": "https://it.wikipedia.org/wiki/Categoria:Cucina_peruviana"}, "main": {"value": "Cucina peruviana"}}]
        with mock.patch("local_terms._sparql", side_effect=[topic, cuisine]) as sparql:
            self.assertEqual((["Categoria:Cucina peruviana", "Categoria:Dolci peruviani"], {"Cucina peruviana"}),
                             local_terms.categories("Q419", "it"))
        self.assertEqual(2, sparql.call_count)


class CountryTermsTest(unittest.TestCase):
    def test_scarta_elenchi_voce_principale_disambigue_tipi_esclusi_e_nomi_di_altre_voci(self):
        info = {"Ceviche": {"redirects": ["Cebiche"], "item": "Q1", "revision": 7, "disambiguation": False},
                "Pachamanca": {"redirects": [], "item": "Q2", "revision": 8, "disambiguation": False},
                "Chicha": {"redirects": [], "item": "Q3", "revision": 9, "disambiguation": True},
                "Gastón Acurio": {"redirects": [], "item": "Q4", "revision": 10, "disambiguation": False},
                "Ceviche (canzone)": {"redirects": [], "item": "Q5", "revision": 11, "disambiguation": False}}
        with mock.patch("local_terms.categories", return_value=(["Categoria:Cucina peruviana"], {"Cucina peruviana"})), \
                mock.patch("local_terms.members", return_value=set(info) | {"Cucina peruviana", "Lista di piatti peruviani"}), \
                mock.patch("local_terms.page_info", return_value=info) as page_info, \
                mock.patch("local_terms.excluded_items", return_value={"Q4"}), \
                mock.patch("local_terms.existing_articles", return_value=set()):
            terms = local_terms.country_terms("Q419", "it", deadline=float("inf"))
        self.assertEqual(set(info), page_info.call_args.args[0])
        self.assertEqual([("Ceviche", ["Ceviche", "Cebiche"], 7), ("Ceviche (canzone)", [], 11),
                          ("Pachamanca", ["Pachamanca"], 8)], terms)


class CollectTest(unittest.TestCase):
    def test_righe_col_nome_usato_dalla_guida_regione_senza_paese_saltata(self):
        regions = {"peru": ("Peru", "Assaggiate il cebiche al mercato."), "atlantide": ("Atlantis", "Ceviche ovunque.")}
        candidates = [("Ceviche", ["Ceviche", "Cebiche"], 7), ("Pachamanca", ["Pachamanca"], 8)]
        with mock.patch("local_terms.city_population.wikibase_items", return_value={"Peru": "Q419"}), \
                mock.patch("local_terms.countries", return_value={"Q419": "Q419"}), \
                mock.patch("local_terms.country_terms", return_value=candidates) as country_terms, \
                mock.patch("local_terms.summaries", return_value={"Ceviche": "Piatto di pesce crudo."}) as summaries:
            rows = local_terms.collect(regions, "it")
        country_terms.assert_called_once()
        self.assertEqual({"Ceviche"}, summaries.call_args.args[0])
        self.assertEqual([{"regionId": "peru", "terms": [
            {"term": "Cebiche", "aliases": ["Ceviche"], "title": "Ceviche", "summary": "Piatto di pesce crudo.",
             "url": "https://it.wikipedia.org/wiki/Ceviche", "revision": 7}]}], rows)

    def test_un_paese_in_errore_lascia_fuori_solo_le_sue_regioni_le_altre_anche_senza_termini(self):
        regions = {"peru": ("Peru", "Pachamanca."), "malta": ("Malta", "Pastizzi."), "gozo": ("Gozo", "Niente.")}

        def terms(country, lang, deadline):
            if country == "Q419":
                raise OSError("rete")
            return [("Pastizz", ["Pastizzi"], 3)]
        with mock.patch("local_terms.city_population.wikibase_items", return_value={"Peru": "Q419", "Malta": "Q233", "Gozo": "Q9"}), \
                mock.patch("local_terms.countries", return_value={"Q419": "Q419", "Q233": "Q233", "Q9": "Q233"}), \
                mock.patch("local_terms.country_terms", side_effect=terms), \
                mock.patch("local_terms.summaries", return_value={"Pastizz": "Sfoglia ripiena."}), \
                redirect_stderr(io.StringIO()) as err:
            rows = local_terms.collect(regions, "it")
        self.assertEqual([("gozo", 0), ("malta", 1)], [(r["regionId"], len(r["terms"])) for r in rows])
        self.assertIn("Q419 saltato", err.getvalue())


class MainTest(unittest.TestCase):
    def test_errore_mai_fatale_scrive_un_file_vuoto(self):
        with tempfile.TemporaryDirectory() as tmp:
            guide = Path(tmp, "peru.txt")
            guide.write_text("Ceviche.", encoding="utf-8")
            tsv = Path(tmp, "regioni.tsv")
            tsv.write_text(f"peru\tPeru\t{guide}\nvuota\tNiente\t\n", encoding="utf-8")
            out = Path(tmp, "terms.jsonl")
            with mock.patch("local_terms.collect", side_effect=OSError("rete")) as collect, \
                    redirect_stderr(io.StringIO()), redirect_stdout(io.StringIO()) as stdout:
                self.assertEqual(0, local_terms.main(["it", str(tsv), str(out)]))
            self.assertEqual({"peru": ("Peru", "Ceviche.")}, collect.call_args.args[0])
            self.assertEqual("", out.read_text(encoding="utf-8"))
            self.assertIn("0 termini locali it, 0/1 regioni lette", stdout.getvalue())

    def test_righe_jsonl(self):
        row = {"regionId": "peru", "terms": [{"term": "Ají", "aliases": [], "title": "Ají", "summary": "Peperoncino.", "url": "u", "revision": 1}]}
        with tempfile.TemporaryDirectory() as tmp:
            tsv = Path(tmp, "regioni.tsv")
            tsv.write_text("", encoding="utf-8")
            out = Path(tmp, "terms.jsonl")
            with mock.patch("local_terms.collect", return_value=[row]), redirect_stdout(io.StringIO()):
                local_terms.main(["en", str(tsv), str(out)])
            self.assertEqual([row], [json.loads(line) for line in out.read_text(encoding="utf-8").splitlines()])

    def test_lingua_sconosciuta_mostra_l_uso(self):
        with redirect_stderr(io.StringIO()) as err:
            self.assertEqual(2, local_terms.main(["fr", "a", "b"]))
        self.assertIn("Uso:", err.getvalue())


if __name__ == "__main__":
    unittest.main()
