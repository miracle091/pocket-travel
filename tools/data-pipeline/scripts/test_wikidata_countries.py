#!/usr/bin/env python3
"""Test di wikidata_countries.py con risposte SPARQL fisse: niente rete.

Uso: python test_wikidata_countries.py
"""
import os
import sys
import tempfile
import unittest
from unittest import mock
from datetime import timedelta, tzinfo
from zoneinfo import ZoneInfoNotFoundError

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wikidata_countries  # noqa: E402

E = "http://www.wikidata.org/entity/"
N = "http://wikiba.se/ontology#NormalRank"
P = "http://wikiba.se/ontology#PreferredRank"

REGIONS_SH = '''ALL_REGIONS=(
  "italia|Italia|6.60|35.29|18.60|47.10|Italy|it|||Europa"
  "isole-canarie|Isole Canarie (Spagna)|-18.20|27.60|-13.30|29.45|Canary_Islands|ic|||Europa"
  "stati-uniti-california|Stati Uniti - California|-124.48|32.53|-114.13|42.01|California|us|Stati Uniti d'America|California|Nord America"
  "stati-uniti-texas|Stati Uniti - Texas|-106.65|25.83|-93.50|36.51|Texas|us|Stati Uniti d'America|Texas|Nord America"
  "atlantide|Atlantide|0|0|1|1|Atlantis|zz|||Europa"
)
REPLACED_REGIONS=(
  "stati-uniti|Stati Uniti d'America"
)'''


def riga(c, v, rank=N, it=None, en=None, mul=None, x=None):
    r = {"c": E + c, "v": E + v if v.startswith("Q") else v, "rank": rank}
    for k, val in (("it", it), ("en", en), ("mul", mul), ("x", x)):
        if val is not None:
            r[k] = val
    return r


RISPOSTE = {
    "P36": [riga("Q38", "Q220", it="Roma", en="Rome"),
            # Capitale con rango preferito: quella di rango normale (vecchia) si scarta.
            riga("Q30", "Q61", P, it="Washington", en="Washington, D.C."), riga("Q30", "Q1345", en="Philadelphia"),
            riga("Q29", "Q2807", it="Madrid", en="Madrid")],
    "P38": [riga("Q38", "Q4916", it="euro", en="euro", x="EUR"), riga("Q29", "Q4916", it="euro", en="euro", x="EUR"),
            riga("Q30", "Q4917", it="dollaro statunitense", en="United States dollar", x="USD")],
    "P1622": [riga("Q38", "Q14565199"), riga("Q30", "Q14565199"), riga("Q29", "Q14565199")],
    "P474": [riga("Q38", "+39"), riga("Q30", "+1"), riga("Q29", "0034")],
    # Lingua senza etichetta italiana ne' inglese: ripiego sulla multilingue.
    "P37": [riga("Q38", "Q652", it="italiano", en="Italian"), riga("Q29", "Q1321", mul="Español")],
    "P421": [riga("Q38", "Q6655", en="UTC+01:00", x="1"),
             riga("Q38", "Q207020", en="Central European Summer Time", x="2"),
             riga("Q29", "Q6574", en="UTC±00:00", x="0"),
             riga("Q29", "Q25989", en="Central European Time", x="1"),
             # Greenwich Mean Time con lo scarto sbagliato su Wikidata.
             riga("Q29", "Q30192", en="Greenwich Mean Time", x="12"),
             riga("Q30", "Q5390", mul="UTC−05:00"),
             # Fuso con nome e due scarti (solare e legale): vale il minore.
             riga("Q30", "Q60738798", en="Newfoundland Time Zone", x="-3.5"),
             riga("Q30", "Q60738798", en="Newfoundland Time Zone", x="-2.5"),
             riga("Q30", "Q999", en="Fuso misterioso")],
    "P2852": [riga("Q38", "Q1061257", en="112"), riga("Q38", "Q11185210", en="118"), riga("Q30", "Q533806", en="911"),
              riga("Q29", "Q1", en="non un numero")],
    "P2853": [riga("Q38", "Q1378312", en="Europlug", x="Type C"), riga("Q38", "Q1378312", en="Europlug", x="CEE 7/16"),
              riga("Q38", "Q1123613", en="Schuko", x="Type F"), riga("Q38", "Q1520890", en="Type L"),
              riga("Q30", "Q24288454", en="NEMA 1-15", x="Type A"), riga("Q30", "Q24288456", en="NEMA 5-15"),
              riga("Q29", "Q1383497", en="BS 546"), riga("Q29", "Q2", en="Presa sconosciuta")],
    "P2884": [riga("Q38", "230"), riga("Q30", "120"), riga("Q30", "120.0"), riga("Q29", "230"), riga("Q29", "5000")],
}


# Elementi delle pagine Wikivoyage EN delle regioni divise: la California ha capitale e fuso, le Canarie no.
SITELINK = [{"item": E + "Q99", "name": "California"}, {"item": E + "Q5813", "name": "Canary Islands"}]
REGIONALI = {
    "P36": [riga("Q99", "Q18013", it="Sacramento", en="Sacramento")],
    "P421": [riga("Q99", "Q2", en="UTC−08:00")],
}


def finto_endpoint(query, user_agent):
    if "schema:isPartOf" in query:
        return SITELINK
    if "wd:Q99" in query:
        return next(rows for prop, rows in REGIONALI.items() if f"p:{prop} ?st" in query)
    if "wdt:P297" in query:
        return [{"c": E + c, "iso": iso} for c, iso in
                (("Q38", "IT"), ("Q29", "ES"), ("Q30", "US"), ("Q644636", "IT"), ("Q142", "FR"))]
    for prop, rows in RISPOSTE.items():
        if f"p:{prop} ?st" in query:
            return rows
    raise AssertionError(query)


COLONNE = wikidata_countries.COLONNE


class RaccogliTest(unittest.TestCase):
    def setUp(self):
        self.rows = self.raccogli({})

    def raccogli(self, correzioni):
        regions = wikidata_countries.regioni(REGIONS_SH)
        with mock.patch("sys.stderr"), mock.patch.object(wikidata_countries, "CORREZIONI", correzioni):
            rows = wikidata_countries.raccogli(regions, "test", run=finto_endpoint)
        return {r[0]: dict(zip(COLONNE, r)) for r in rows}

    def test_regioni_solo_da_all_regions(self):
        self.assertEqual(wikidata_countries.regioni(REGIONS_SH)[0], ("italia", "it", "Italy"))
        self.assertEqual(len(wikidata_countries.regioni(REGIONS_SH)), 5)

    def test_regioni_ereditano_dal_paese_e_codice_sconosciuto_scartato(self):
        self.assertEqual(sorted(self.rows), ["isole-canarie", "italia", "stati-uniti-california", "stati-uniti-texas"])
        cal, tex = self.rows["stati-uniti-california"], self.rows["stati-uniti-texas"]
        diversi = {"regionId", "capital_it", "capital_en", "timezones"}
        self.assertEqual({k: v for k, v in cal.items() if k not in diversi}, {k: v for k, v in tex.items() if k not in diversi})
        self.assertEqual(self.rows["isole-canarie"]["iso2"], "es")
        # Parti del paese senza una capitale propria (Canarie e Texas qui): nessuna, non quella del paese.
        self.assertEqual(self.rows["isole-canarie"]["capital_it"], "")
        self.assertEqual(self.rows["stati-uniti-texas"]["capital_en"], "")

    def test_regioni_divise_con_capitale_e_fusi_propri(self):
        cal = self.rows["stati-uniti-california"]
        self.assertEqual((cal["capital_it"], cal["capital_en"], cal["timezones"]), ("Sacramento", "Sacramento", "UTC-08:00"))
        self.assertEqual(wikidata_countries.divise(wikidata_countries.regioni(REGIONS_SH)),
                         {"isole-canarie": "Canary_Islands", "stati-uniti-california": "California", "stati-uniti-texas": "Texas"})

    def test_correzioni_per_paese_poi_per_regione(self):
        rows = self.raccogli({"us": {"languages_it": "inglese", "timezones": "UTC-06:00"},
                              "stati-uniti-california": {"timezones": "UTC-07:00"}})
        self.assertEqual(rows["stati-uniti-texas"]["languages_it"], "inglese")
        self.assertEqual(rows["stati-uniti-texas"]["timezones"], "UTC-06:00")
        self.assertEqual(rows["stati-uniti-california"]["timezones"], "UTC-07:00")
        self.assertEqual(rows["italia"]["timezones"], "UTC+01:00")

    def test_correzioni_vere_con_colonne_note(self):
        for chiave, campi in wikidata_countries.CORREZIONI.items():
            self.assertLessEqual(set(campi), set(COLONNE[2:]), chiave)

    def test_codice_ripetuto_vince_il_qid_piu_basso(self):
        self.assertEqual(self.rows["italia"]["capital_en"], "Rome")

    def test_rango_preferito_ed_etichette(self):
        us = wikidata_countries.valori(RISPOSTE["P36"])["Q30"]
        self.assertEqual([(v["it"], v["en"]) for v in us], [("Washington", "Washington, D.C.")])
        es = self.rows["isole-canarie"]
        self.assertEqual((es["languages_it"], es["languages_en"]), ("Español", "Español"))
        self.assertEqual((self.rows["italia"]["currency"], self.rows["italia"]["currency_it"]), ("EUR", "euro"))

    def test_fusi_normalizzati_senza_ora_legale(self):
        self.assertEqual(self.rows["italia"]["timezones"], "UTC+01:00")
        # Le Canarie e il Texas non hanno fusi propri qui: quelli del paese.
        self.assertEqual(self.rows["isole-canarie"]["timezones"], "UTC+00:00; UTC+01:00")
        self.assertEqual(self.rows["stati-uniti-texas"]["timezones"], "UTC-05:00; UTC-03:30")

    def test_codici_valuta_nell_ordine_dei_nomi(self):
        dati = {p: {} for p in wikidata_countries.PROPRIETA}
        dati["P38"] = wikidata_countries.valori([riga("Q1013", "Q181907", it="loti", en="loti", x="LSL"),
                                                  riga("Q1013", "Q81893", it="rand", en="rand", x="ZAR")])
        campi = dict(zip(COLONNE[2:], wikidata_countries.fatti(dati, "Q1013", set())))
        self.assertEqual((campi["currency"], campi["currency_it"]), ("ZAR; LSL", "rand; loti"))

    def test_avviso_per_titolo_senza_elemento(self):
        with mock.patch("sys.stderr") as err:
            wikidata_countries.raccogli(wikidata_countries.regioni(REGIONS_SH), "test", run=finto_endpoint)
        testo = "".join(c.args[0] for c in err.write.call_args_list)
        self.assertIn("nessun elemento Wikidata per la pagina Wikivoyage EN 'Texas'", testo)

    def test_prese_in_lettere(self):
        self.assertEqual(self.rows["italia"]["plugs"], "C, F, L")
        self.assertEqual(self.rows["stati-uniti-texas"]["plugs"], "A, B")
        self.assertEqual(self.rows["isole-canarie"]["plugs"], "D, M")

    def test_guida_prefisso_emergenza_tensione(self):
        it, us, es = self.rows["italia"], self.rows["stati-uniti-texas"], self.rows["isole-canarie"]
        self.assertEqual((it["driving"], it["calling_code"], it["emergency"], it["voltage"]), ("right", "+39", "112; 118", "230"))
        self.assertEqual((us["calling_code"], us["voltage"]), ("+1", "120"))
        self.assertEqual((es["calling_code"], es["emergency"], es["voltage"]), ("+34", "", "230"))

    def test_avvisi_per_fusi_e_prese_sconosciuti(self):
        with mock.patch("sys.stderr") as err:
            wikidata_countries.raccogli(wikidata_countries.regioni(REGIONS_SH), "test", run=finto_endpoint)
        testo = "".join(c.args[0] for c in err.write.call_args_list)
        self.assertIn("fuso non riconosciuto: Q999", testo)
        self.assertIn("presa non riconosciuta: Q2", testo)
        self.assertIn("nessun paese su Wikidata per il codice 'zz'", testo)
        self.assertNotIn("Summer", testo)


class FintoFuso(tzinfo):
    """Fuso fisso con ora solare e legale in minuti: sostituisce zoneinfo, che su Windows senza tzdata non ha i fusi."""
    def __init__(self, solare, legale):
        self.solare, self.legale = solare, legale

    def utcoffset(self, dt):
        return timedelta(minutes=self.solare + self.legale)

    def dst(self, dt):
        return timedelta(minutes=self.legale)


# A meta' gennaio: Sydney e' in ora legale (emisfero sud).
FUSI_IANA = {"Europe/Rome": (60, 0), "America/Santo_Domingo": (-240, 0), "Australia/Sydney": (600, 60)}


def finto_zoneinfo(nome):
    if nome not in FUSI_IANA:
        raise ZoneInfoNotFoundError(nome)
    return FintoFuso(*FUSI_IANA[nome])


class NormalizzazioniTest(unittest.TestCase):
    def test_query_senza_enunciati_finiti_ne_ora_legale(self):
        query = wikidata_countries.query_proprieta("P421", {"Q38"})
        self.assertIn("pq:P582", query)
        self.assertIn("pq:P1264 wd:Q36669", query)
        self.assertIn("wikibase:DeprecatedRank", query)

    def fuso(self, en, x=()):
        return wikidata_countries.scarto_fuso({"v": "Q1", "en": en, "x": set(x)})

    def test_formati_utc(self):
        self.assertEqual(self.fuso("UTC+05:30"), 330)
        self.assertEqual(self.fuso("UTC−03:30"), -210)
        self.assertEqual(self.fuso("UTC+14:00"), 840)
        self.assertEqual(wikidata_countries.formato_fuso(-210), "UTC-03:30")
        self.assertEqual(wikidata_countries.formato_fuso(345), "UTC+05:45")

    @mock.patch.object(wikidata_countries, "ZoneInfo", finto_zoneinfo)
    def test_nomi_iana_con_ora_solare(self):
        self.assertEqual(self.fuso("Europe/Rome"), 60)
        self.assertEqual(self.fuso("America/Santo Domingo"), -240)
        # Emisfero sud: a gennaio c'e' l'ora legale, ma conta quella solare.
        self.assertEqual(self.fuso("Australia/Sydney"), 600)
        # Fuso sconosciuto: ripiego sullo scarto di Wikidata (P2907), altrimenti nessuno.
        self.assertEqual(self.fuso("Africa/Atlantide", x=["2"]), 120)
        self.assertIsNone(self.fuso("Africa/Atlantide"))

    def test_prefisso(self):
        self.assertEqual(wikidata_countries.prefisso("+1-340"), "+1 340")
        self.assertEqual(wikidata_countries.prefisso("+44 1481"), "+44 1481")
        self.assertEqual(wikidata_countries.prefisso("nessuno"), "")
        self.assertEqual(wikidata_countries.prefisso("+1787"), "+1 787")


class MainTest(unittest.TestCase):
    def esegui(self, rows):
        out = os.path.join(tempfile.mkdtemp(), "countries.tsv")
        with mock.patch.object(wikidata_countries, "raccogli", return_value=rows), \
                mock.patch.object(sys, "argv", ["wikidata_countries.py", "--out", out]), mock.patch("sys.stderr"):
            return wikidata_countries.main(), out

    def test_scrittura_atomica(self):
        codice, out = self.esegui([["r%d" % i, "it", "Roma", "Rome"] + [""] * 11 for i in range(wikidata_countries.MIN_ROWS)])
        self.assertEqual(codice, 0)
        with open(out, encoding="utf-8") as f:
            righe = [r for r in f.read().splitlines() if not r.startswith("#")]
        self.assertEqual(len(righe), wikidata_countries.MIN_ROWS)
        self.assertFalse(os.path.exists(out + ".tmp"))

    def test_poche_capitali_esce_con_1_senza_scrivere(self):
        codice, out = self.esegui([["r%d" % i, "it", "", ""] + [""] * 11 for i in range(500)])
        self.assertEqual(codice, 1)
        self.assertFalse(os.path.exists(out))


if __name__ == "__main__":
    unittest.main()
