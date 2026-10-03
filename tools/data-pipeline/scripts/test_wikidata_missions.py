#!/usr/bin/env python3
"""Test di wikidata_missions.py con risposte SPARQL fisse: niente rete.

Uso: python test_wikidata_missions.py
"""
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wikidata_missions  # noqa: E402

E = "http://www.wikidata.org/entity/"


def finto_endpoint(query, user_agent):
    """Risposte fisse, scelte dal testo della query (stessa forma dei binding di Wikidata, valori gia' estratti)."""
    if "wdt:P279*" in query:
        return [{"root": E + "Q372690", "c": E + "Q372690"}, {"root": E + "Q7843791", "c": E + "Q7843791"},
                {"root": E + "Q3917681", "c": E + "Q3917681"}, {"root": E + "Q3917681", "c": E + "Q12143816"}]
    if "wdt:P297" in query:
        return [{"m": E + c, "v": v} for c, v in (("Q38", "IT"), ("Q142", "FR"), ("Q183", "DE"), ("Q30", "US"), ("Q9", "XX"))]
    if "wdt:P137" in query and "wdt:P17" in query:
        return [
            {"m": E + "Q2", "c": E + "Q372690", "s": E + "Q38", "h": E + "Q142"},
            {"m": E + "Q3", "c": E + "Q7843791", "s": E + "Q183", "h": E + "Q38"},
            # Stesso elemento con due paesi invianti: vince il primo in ordine (it < us).
            {"m": E + "Q1", "c": E + "Q3917681", "s": E + "Q30", "h": E + "Q38"},
            {"m": E + "Q1", "c": E + "Q3917681", "s": E + "Q38", "h": E + "Q38"},
            {"m": E + "Q4", "c": E + "Q12143816", "s": E + "Q9", "h": E + "Q38"},  # senza etichette: nome da lingua qualsiasi
            # Doppione in due alberi di classi: vince il tipo della prima radice (consolato).
            {"m": E + "Q3", "c": E + "Q3917681", "s": E + "Q142", "h": E + "Q38"},
            # Paese sconosciuto (senza P297) e missione sciolta: scartati.
            {"m": E + "Q5", "c": E + "Q3917681", "s": E + "Q999", "h": E + "Q38"},
            {"m": E + "Q6", "c": E + "Q3917681", "s": E + "Q38", "h": E + "Q142"},
        ]
    if "wdt:P576" in query:
        return [{"m": E + "Q6", "v": "2020-01-01T00:00:00Z"}]
    if "pq:P582" in query or "wdt:P582" in query:
        return []
    if 'lang(?v) = "it"' in query:
        return [{"m": E + "Q2", "v": "Consolato generale d'Italia a Parigi"}, {"m": E + "Q1", "v": "Ambasciata d'Italia"}]
    if 'lang(?v) = "en"' in query:
        return [{"m": E + "Q2", "v": "Consulate General of Italy, Paris"}, {"m": E + "Q3", "v": "Consulate of Germany"},
                {"m": E + "Q1", "v": "Embassy of Italy"}]
    if "wikibase:label" in query:
        return [{"m": E + "Q4", "l": "Ambassade de XX"}]
    if "wdt:P131" in query:
        return [{"m": E + "Q1", "en": "Rome"}, {"m": E + "Q2", "it": "Parigi", "en": "Paris"}]
    if "wdt:P625" in query:
        return [{"m": E + "Q1", "v": "Point(12.49 41.90)"}]
    if "wdt:P968" in query:
        return [{"m": E + "Q2", "v": "mailto:consolato@example.org"}]
    if "wdt:P856" in query:
        return [{"m": E + "Q2", "v": "https://example.org"}, {"m": E + "Q2", "v": "https://altro.example.org"}]
    if "wdt:P1329" in query:
        return [{"m": E + "Q1", "v": "+39 06 4674\t1"}]
    if "wdt:P6375" in query:
        return [{"m": E + "Q1", "v": "Via Veneto 121,\n00187 Roma"}]
    return []


class RaccogliTest(unittest.TestCase):
    def setUp(self):
        self.rows = {r[0]: r for r in wikidata_missions.raccogli("test", run=finto_endpoint)}

    def test_un_elemento_per_qid_con_il_tipo_della_prima_classe(self):
        self.assertEqual(sorted(self.rows), ["Q1", "Q2", "Q3", "Q4"])
        self.assertEqual(self.rows["Q2"][3], "consulate_general")
        self.assertEqual(self.rows["Q3"][3], "consulate")
        self.assertEqual(self.rows["Q1"][3], "embassy")

    def test_paesi_minuscoli_e_primo_valore_in_caso_di_piu_paesi(self):
        self.assertEqual(self.rows["Q1"][1:3], ["it", "it"])

    def test_nome_italiano_poi_inglese_poi_qualsiasi(self):
        self.assertEqual(self.rows["Q2"][4:6], ["Consolato generale d'Italia a Parigi", "Consulate General of Italy, Paris"])
        self.assertEqual(self.rows["Q3"][4], "Consulate of Germany")
        self.assertEqual(self.rows["Q4"][4], "Ambassade de XX")
        self.assertEqual(self.rows["Q4"][5], "")

    def test_citta_coordinate_email_e_campi_puliti(self):
        self.assertEqual(self.rows["Q1"][6], "Rome")
        self.assertEqual(self.rows["Q2"][6], "Parigi")
        self.assertEqual(self.rows["Q1"][11:13], ["41.90", "12.49"])
        self.assertEqual(self.rows["Q2"][10], "consolato@example.org")
        self.assertEqual(self.rows["Q2"][9], "https://altro.example.org")
        self.assertEqual(self.rows["Q1"][8], "+39 06 4674 1")
        self.assertEqual(self.rows["Q1"][7], "Via Veneto 121, 00187 Roma")
        self.assertEqual(self.rows["Q3"][11:13], ["", ""])
        for row in self.rows.values():
            self.assertEqual(len(row), 13)


def endpoint_con_valori_sporchi(query, user_agent):
    """Come finto_endpoint, ma con coordinate, sito, telefono ed email non validi su Q1 e Q3."""
    if "wdt:P625" in query:
        return [{"m": E + "Q1", "v": "Point(1e 41.90)"}, {"m": E + "Q3", "v": "Point(12.49 91.5)"},
                {"m": E + "Q2", "v": "Point(--1 2)"}]
    if "wdt:P856" in query:
        return [{"m": E + "Q1", "v": "javascript:alert(1)"}, {"m": E + "Q3", "v": "www.example.org"},
                {"m": E + "Q2", "v": "http://example.org/a b"}]
    if "wdt:P1329" in query:
        return [{"m": E + "Q1", "v": "chiamare in ambasciata"}, {"m": E + "Q3", "v": "+39 06 4674"}]
    if "wdt:P968" in query:
        return [{"m": E + "Q1", "v": "mailto:non-una-email"}, {"m": E + "Q3", "v": "info@example.org"}]
    return finto_endpoint(query, user_agent)


class ValoriNonValidiTest(unittest.TestCase):
    def setUp(self):
        self.rows = {r[0]: r for r in wikidata_missions.raccogli("test", run=endpoint_con_valori_sporchi)}

    def test_coordinate_non_valide_o_fuori_limiti_sono_scartate_insieme(self):
        for q in ("Q1", "Q2", "Q3"):
            self.assertEqual(self.rows[q][11:13], ["", ""], q)

    def test_sito_solo_se_http_o_https_senza_spazi(self):
        for q in ("Q1", "Q2", "Q3"):
            self.assertEqual(self.rows[q][9], "", q)

    def test_telefono_ed_email_solo_se_plausibili(self):
        self.assertEqual(self.rows["Q1"][8], "")
        self.assertEqual(self.rows["Q1"][10], "")
        self.assertEqual(self.rows["Q3"][8], "+39 06 4674")
        self.assertEqual(self.rows["Q3"][10], "info@example.org")


class MainTest(unittest.TestCase):
    def test_scrittura_atomica_senza_file_temporaneo(self):
        out = os.path.join(tempfile.mkdtemp(), "missions.tsv")
        righe = [["Q%d" % i] + ["x"] * 12 for i in range(wikidata_missions.MIN_ROWS)]
        with mock.patch.object(wikidata_missions, "raccogli", return_value=righe), \
                mock.patch.object(sys, "argv", ["wikidata_missions.py", "--out", out]):
            self.assertEqual(wikidata_missions.main(), 0)
        self.assertEqual(len(open(out, encoding="utf-8").read().splitlines()), wikidata_missions.MIN_ROWS)
        self.assertFalse(os.path.exists(out + ".tmp"))

    def test_troppe_poche_righe_esce_con_1_senza_scrivere(self):
        out = os.path.join(tempfile.mkdtemp(), "missions.tsv")
        with mock.patch.object(wikidata_missions, "raccogli", return_value=[["x"] * 13] * (wikidata_missions.MIN_ROWS - 1)),                 mock.patch.object(sys, "argv", ["wikidata_missions.py", "--out", out]):
            self.assertEqual(wikidata_missions.main(), 1)
        self.assertFalse(os.path.exists(out))

    def test_scadenza_globale_esce_con_1(self):
        out = os.path.join(tempfile.mkdtemp(), "missions.tsv")
        with mock.patch.object(wikidata_missions, "DEADLINE_SECONDS", -1),                 mock.patch.object(wikidata_missions.urllib.request, "urlopen", side_effect=AssertionError("rete usata")),                 mock.patch.object(sys, "argv", ["wikidata_missions.py", "--out", out]):
            self.assertEqual(wikidata_missions.main(), 1)
        self.assertFalse(os.path.exists(out))


if __name__ == "__main__":
    unittest.main()
