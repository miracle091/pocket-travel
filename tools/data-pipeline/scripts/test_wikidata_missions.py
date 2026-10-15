#!/usr/bin/env python3
"""Test di wikidata_missions.py con risposte SPARQL fisse: niente rete.

Uso: python test_wikidata_missions.py
"""
import os
import sys
import tempfile
import time
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


def open_lines(path):
    with open(path, encoding="utf-8") as f:
        return f.read().splitlines()


class MainTest(unittest.TestCase):
    def test_scrittura_atomica_senza_file_temporaneo(self):
        out = os.path.join(tempfile.mkdtemp(), "missions.tsv")
        righe = [["Q%d" % i] + ["x"] * 12 for i in range(wikidata_missions.MIN_ROWS)]
        with mock.patch.object(wikidata_missions, "raccogli", return_value=righe), \
                mock.patch.object(sys, "argv", ["wikidata_missions.py", "--out", out]):
            self.assertEqual(wikidata_missions.main(), 0)
        self.assertEqual(len(open_lines(out)), wikidata_missions.MIN_ROWS)
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


class ValidatoriTest(unittest.TestCase):
    def test_coordinate_ai_limiti_e_valori_non_finiti(self):
        self.assertEqual(("90", "180"), wikidata_missions.coordinate("90", "180"))
        self.assertEqual(("-90", "-180"), wikidata_missions.coordinate("-90", "-180"))
        for lat, lon in (("90.0001", "0"), ("0", "180.5"), ("nan", "0"), ("0", "inf"), ("", "1"), (None, "1")):
            self.assertEqual(("", ""), wikidata_missions.coordinate(lat, lon), (lat, lon))

    def test_telefono_almeno_cinque_cifre_e_solo_caratteri_ammessi(self):
        self.assertEqual("+39 06 4674", wikidata_missions.telefono_valido("+39 06 4674"))
        self.assertEqual("", wikidata_missions.telefono_valido("+39 06"))
        self.assertEqual("", wikidata_missions.telefono_valido("06 4674 1234 int. 5"))  # lettere non ammesse
        self.assertEqual("+90 (212) 555-01-23 ext 4", wikidata_missions.telefono_valido("+90 (212) 555-01-23 ext 4"))
        self.assertEqual("", wikidata_missions.telefono_valido(""))
        self.assertEqual("", wikidata_missions.telefono_valido(None))

    def test_email_e_sito(self):
        self.assertEqual("a.b@ambasciata.example.it", wikidata_missions.email_valida(" a.b@ambasciata.example.it "))
        for bad in ("a@b", "due@@example.org", "con spazio@example.org", ""):
            self.assertEqual("", wikidata_missions.email_valida(bad), bad)
        self.assertEqual("HTTP://EXAMPLE.ORG/è", wikidata_missions.sito_valido("HTTP://EXAMPLE.ORG/è"))
        self.assertEqual("", wikidata_missions.sito_valido("ftp://example.org"))
        self.assertEqual("", wikidata_missions.sito_valido("https://"))

    def test_identificatori_e_pulizia(self):
        self.assertEqual("Q42", wikidata_missions.qid("http://www.wikidata.org/entity/Q42"))
        self.assertEqual(42, wikidata_missions.qid_key("Q42"))
        self.assertEqual(0, wikidata_missions.qid_key("X"))
        self.assertLess(wikidata_missions.qid_key("Q9"), wikidata_missions.qid_key("Q10"))  # numerico, non alfabetico
        self.assertEqual("Ambasciata d'Italia a Città del Messico", wikidata_missions.pulisci("  Ambasciata d'Italia\ta  Città\ndel Messico "))
        self.assertEqual("", wikidata_missions.pulisci(None))

    def test_primo_valore_alfabetico_per_elemento(self):
        rows = [{"m": E + "Q2", "v": "b"}, {"m": E + "Q2", "v": "a"}, {"m": E + "Q10", "v": "z"}, {"m": E + "Q1"}]
        self.assertEqual({"Q1": "", "Q2": "a", "Q10": "z"}, wikidata_missions.primo(rows))

    def test_query_con_le_classi_in_ordine_numerico(self):
        self.assertIn("VALUES ?c { wd:Q9 wd:Q10 }", wikidata_missions.query_core({"Q10", "Q9"}))
        self.assertIn('lang(?v) = "it"', wikidata_missions.query_label({"Q1"}, "it"))


class FakeHttpResponse:
    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, *args):
        import json
        return json.dumps(self.payload).encode("utf-8")


def http_error(code, retry_after=None):
    import urllib.error
    headers = {"Retry-After": retry_after} if retry_after else {}
    return urllib.error.HTTPError("https://query.wikidata.org/sparql", code, "x", headers, None)


class SparqlTest(unittest.TestCase):
    ok = {"results": {"bindings": [{"m": {"type": "uri", "value": E + "Q1"}, "v": {"type": "literal", "value": "Città"}}]}}

    def setUp(self):
        patcher = mock.patch.object(wikidata_missions, "_deadline", None)
        patcher.start()
        self.addCleanup(patcher.stop)
        sleep = mock.patch("time.sleep")
        self.sleep = sleep.start()
        self.addCleanup(sleep.stop)
        err = mock.patch.object(sys, "stderr", __import__("io").StringIO())
        err.start()
        self.addCleanup(err.stop)

    def test_righe_come_dizionari_e_user_agent_inviato(self):
        with mock.patch("urllib.request.urlopen", return_value=FakeHttpResponse(self.ok)) as urlopen:
            rows = wikidata_missions.sparql("SELECT 1", "UA-di-prova")
        self.assertEqual([{"m": E + "Q1", "v": "Città"}], rows)
        request = urlopen.call_args.args[0]
        self.assertEqual("UA-di-prova", request.get_header("User-agent"))
        self.assertIn(b"query=SELECT+1", request.data)

    def test_riprova_su_503_con_attesa_crescente(self):
        with mock.patch("urllib.request.urlopen", side_effect=[http_error(503), http_error(502), FakeHttpResponse(self.ok)]):
            rows = wikidata_missions.sparql("q", "ua")
        self.assertEqual(1, len(rows))
        self.assertEqual([mock.call(20), mock.call(40)], self.sleep.call_args_list)

    def test_429_rispetta_retry_after_se_piu_lungo(self):
        with mock.patch("urllib.request.urlopen", side_effect=[http_error(429, "90"), FakeHttpResponse(self.ok)]):
            wikidata_missions.sparql("q", "ua")
        self.sleep.assert_called_once_with(90)

    def test_429_retry_after_non_numerico_usa_l_attesa_base(self):
        with mock.patch("urllib.request.urlopen", side_effect=[http_error(429, "domani"), FakeHttpResponse(self.ok)]):
            wikidata_missions.sparql("q", "ua")
        self.sleep.assert_called_once_with(20)

    def test_4xx_diverso_da_429_non_si_riprova(self):
        with mock.patch("urllib.request.urlopen", side_effect=[http_error(400)]) as urlopen:
            with self.assertRaises(__import__("urllib.error").error.HTTPError):
                wikidata_missions.sparql("q", "ua")
        self.assertEqual(1, urlopen.call_count)
        self.sleep.assert_not_called()

    def test_dopo_l_ultimo_tentativo_rilancia(self):
        with mock.patch("urllib.request.urlopen", side_effect=[http_error(500)] * 3) as urlopen:
            with self.assertRaises(__import__("urllib.error").error.HTTPError):
                wikidata_missions.sparql("q", "ua", attempts=3)
        self.assertEqual(3, urlopen.call_count)

    def test_risposta_senza_risultati_e_un_errore_dopo_i_tentativi(self):
        with mock.patch("urllib.request.urlopen", return_value=FakeHttpResponse({"boolean": True})):
            with self.assertRaises(KeyError):
                wikidata_missions.sparql("q", "ua", attempts=2)

    def test_scadenza_superata_prima_della_richiesta(self):
        with mock.patch.object(wikidata_missions, "_deadline", time.monotonic() - 1), \
                mock.patch("urllib.request.urlopen") as urlopen:
            with self.assertRaises(TimeoutError):
                wikidata_missions.sparql("q", "ua")
        urlopen.assert_not_called()


class RaccogliEdgeTest(unittest.TestCase):
    def test_senza_missioni_nessuna_riga(self):
        self.assertEqual([], wikidata_missions.raccogli("ua", run=lambda q, ua: []))

    def test_missione_senza_alcun_nome_e_scartata(self):
        def run(query, ua):
            if "wdt:P279*" in query:
                return [{"root": E + "Q3917681", "c": E + "Q3917681"}]
            if "wdt:P297" in query:
                return [{"m": E + "Q38", "v": "IT"}, {"m": E + "Q142", "v": "FR"}]
            if "wdt:P137" in query and "wdt:P17" in query:
                return [{"m": E + "Q7", "c": E + "Q3917681", "s": E + "Q38", "h": E + "Q142"}]
            return []
        self.assertEqual([], wikidata_missions.raccogli("ua", run=run))


if __name__ == "__main__":
    unittest.main()
