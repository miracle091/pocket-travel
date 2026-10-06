#!/usr/bin/env python3
"""Test degli articoli tematici di Wikipedia EN nel dataset inglese (generate_sft.py --lang en): titoli candidati,
controllo del tema dopo il redirect, paragrafi inglesi senza le sezioni finali, ripiego sulla traduzione da Wikipedia IT
per i temi senza articolo inglese.

Uso: python test_sft_wikipedia_en.py
"""
import unittest

import generate_sft_dataset as g
from generate_sft import drop_shared_wp, wp_en_sections, wp_fallback
from generate_sft_dataset_en import KEYWORDS as EN_KEYWORDS, WP_KEYWORDS_EN

_app_clean = g.app_clean


def setUpModule():
    g.app_clean = lambda raw: raw.strip()  # niente JVM: i testi dei test sono gia' puliti


def tearDownModule():
    g.app_clean = _app_clean

FOOD = ("The cuisine of the islands is based on seafood: conch salad, grouper and rice dishes are served in every "
        "restaurant, and fresh fish is the main food of most meals, often with a local beer or rum drink.")
ARTICLE = f"""Intro paragraph about food that the encyclopedic lead should never use, even if it mentions cuisine and dish.

== Dishes ==
{FOOD}

== See also ==
{FOOD.replace("seafood", "imported food")}

== References ==
{FOOD.replace("seafood", "canned food")}
"""


class WpEnSectionsTest(unittest.TestCase):
    def test_primo_titolo_candidato_con_paragrafi_inglesi(self):
        pages = {"Cuisine of the Bahamas": ("Cuisine of the Bahamas", ARTICLE)}
        title, secs = wp_en_sections(pages, "The_Bahamas", "CIBO_BEVANDE", EN_KEYWORDS)
        self.assertEqual(title, "Cuisine of the Bahamas")
        self.assertEqual(secs, [("CIBO_BEVANDE", FOOD)])  # niente incipit, "See also" e "References"

    def test_secondo_titolo_candidato(self):
        pages = {"Fujian cuisine": ("Fujian cuisine", ARTICLE)}
        self.assertEqual(wp_en_sections(pages, "Fujian", "CIBO_BEVANDE", EN_KEYWORDS)[0], "Fujian cuisine")

    def test_redirect_alla_voce_del_paese_non_vale(self):
        pages = {"Vatican City cuisine": ("Vatican City", ARTICLE)}
        self.assertEqual(wp_en_sections(pages, "Vatican_City", "CIBO_BEVANDE", EN_KEYWORDS), (None, []))

    def test_vale_solo_il_redirect_a_un_articolo_del_tema(self):
        pages = {"Cuisine of Guyana": ("Culture of Guyana", ARTICLE)}
        self.assertEqual(wp_en_sections(pages, "Guyana", "CIBO_BEVANDE", EN_KEYWORDS), (None, []))
        pages = {"Cuisine of Guyana": ("Guyanese cuisine", ARTICLE)}
        self.assertEqual(wp_en_sections(pages, "Guyana", "CIBO_BEVANDE", EN_KEYWORDS)[0], "Guyanese cuisine")

    def test_articolo_assente(self):
        self.assertEqual(wp_en_sections({}, "The_Bahamas", "USI_COSTUMI", EN_KEYWORDS), (None, []))


class ParseWpTest(unittest.TestCase):
    def test_sezioni_finali_della_lingua(self):
        it_notes, en_notes = "Incipit.\n\n== Note ==\n" + FOOD, "Incipit.\n\n== Notes ==\n" + FOOD
        self.assertEqual(g.parse_wp(it_notes, "CIBO_BEVANDE", EN_KEYWORDS), [])
        self.assertEqual(g.parse_wp(it_notes, "CIBO_BEVANDE", EN_KEYWORDS, g.WP_SKIP_SECTIONS_EN), [("CIBO_BEVANDE", FOOD)])
        self.assertEqual(g.parse_wp(en_notes, "CIBO_BEVANDE", EN_KEYWORDS, g.WP_SKIP_SECTIONS_EN), [])

    def test_parole_chiave_della_lingua(self):
        raw = "Incipit.\n\n== Dishes ==\n" + FOOD
        self.assertEqual(g.parse_wp(raw, "CIBO_BEVANDE"), [])  # le radici italiane non coprono il testo inglese
        self.assertEqual(g.parse_wp(raw, "CIBO_BEVANDE", EN_KEYWORDS, g.WP_SKIP_SECTIONS_EN), [("CIBO_BEVANDE", FOOD)])

    def test_sottotitolo_non_riapre_una_sezione_saltata(self):
        raw = f"Incipit.\n\n== See also ==\n=== Dishes ===\n{FOOD}\n\n== Dishes ==\n{FOOD.replace('seafood', 'fish')}"
        self.assertEqual(g.parse_wp(raw, "CIBO_BEVANDE", EN_KEYWORDS, g.WP_SKIP_SECTIONS_EN),
                         [("CIBO_BEVANDE", FOOD.replace("seafood", "fish"))])

    def test_titolo_senza_riga_vuota_prima(self):
        raw = f"Incipit.\n\n== Dishes ==\n{FOOD}\n== See also ==\n{FOOD.replace('seafood', 'fish')}"
        self.assertEqual(g.parse_wp(raw, "CIBO_BEVANDE", EN_KEYWORDS, g.WP_SKIP_SECTIONS_EN), [("CIBO_BEVANDE", FOOD)])

    def test_usi_e_costumi_solo_parole_di_comportamento(self):
        heritage = ("The cultural heritage of the country is rich in traditional music, religious buildings and ancient "
                    "monuments, and its literature is studied in many universities across the region.")
        manners = ("Greeting with a handshake is polite, shoes are removed before entering a home, and tipping is not "
                   "expected in restaurants, while loud voices in public are considered rude.")
        raw = f"Incipit.\n\n== Culture ==\n{heritage}\n\n== Etiquette ==\n{manners}"
        self.assertEqual(g.parse_wp(raw, "USI_COSTUMI", WP_KEYWORDS_EN, g.WP_SKIP_SECTIONS_EN), [("USI_COSTUMI", manners)])


class KeywordsTest(unittest.TestCase):
    def test_radici_a_inizio_parola(self):
        self.assertFalse(g.covers("VITA_QUOTIDIANA", "Pay immediately when ordering.", EN_KEYWORDS))
        self.assertTrue(g.covers("VITA_QUOTIDIANA", "Local media are free.", EN_KEYWORDS))
        self.assertFalse(g.covers("USI_COSTUMI", "Agriculture and customs offices, respectively.", EN_KEYWORDS))
        self.assertFalse(g.covers("CONNETTIVITA", "The francophone north.", EN_KEYWORDS))
        self.assertTrue(g.covers("CONNETTIVITA", "Buy a smartphone plan.", EN_KEYWORDS))
        self.assertTrue(g.covers("ACQUISTI", "A coffee costs 2€.", EN_KEYWORDS))

    def test_radice_con_spazio_davanti(self):
        self.assertTrue(g.covers("TRASPORTI", "Take the bus.", EN_KEYWORDS))
        self.assertFalse(g.covers("TRASPORTI", "A minibus-free zone.", {"TRASPORTI": [" bus"]}))


class DropSharedWpTest(unittest.TestCase):
    @staticmethod
    def region(wp=(), wp_en=(), it=()):
        return ("nome", {"it": list(it), "en": [], "wp": list(wp), "wp_en": list(wp_en)})

    def test_articolo_a_una_sola_regione_prima_quelle_di_test(self):
        culture = ("USI_COSTUMI", "culture", "Culture of Kiribati")
        raw = {"kiribati-gilbert": self.region(wp_en=[culture, ("CIBO_BEVANDE", "food", "Cuisine of Kiribati")]),
               "kiribati-line": self.region(wp_en=[culture], it=[("SICUREZZA", "sicuro")]),
               "isola-test": self.region(wp_en=[("CIBO_BEVANDE", "food", "Cuisine of Kiribati")])}
        dropped = drop_shared_wp(raw, {"isola-test"})
        self.assertEqual(sorted(dropped), [("kiribati-gilbert", "wp_en", "CIBO_BEVANDE", "regione-di-test"),
                                           ("kiribati-line", "wp_en", "USI_COSTUMI", "pagina-condivisa")])
        self.assertEqual(raw["kiribati-gilbert"][1]["wp_en"], [culture])
        self.assertEqual(raw["kiribati-line"][1]["wp_en"], [])  # resta per la sezione di Wikivoyage

    def test_regione_senza_altro_testo_tolta_e_paragrafi_dello_stesso_articolo_tenuti(self):
        two = [("CIBO_BEVANDE", "p1", "Cucina dell'Anhui"), ("CIBO_BEVANDE", "p2", "Cucina dell'Anhui")]
        raw = {"cina-anhui": self.region(wp=two), "cina-ningxia": self.region(wp=two[:1])}
        drop_shared_wp(raw, set())
        self.assertEqual(list(raw), ["cina-anhui"])
        self.assertEqual(raw["cina-anhui"][1]["wp"], two)


class WpFallbackTest(unittest.TestCase):
    def test_traduzione_solo_per_i_temi_senza_articolo_inglese(self):
        secs = {"wp": [("CIBO_BEVANDE", "cucina", "Cucina bahamense"), ("USI_COSTUMI", "cultura", "Cultura delle Bahamas")],
                "wp_en": [("CIBO_BEVANDE", "cuisine", "Cuisine of the Bahamas")]}
        self.assertEqual(wp_fallback(secs), [("USI_COSTUMI", "cultura", "Cultura delle Bahamas")])

    def test_senza_articoli_inglesi_tutto_tradotto(self):
        secs = {"wp": [("CIBO_BEVANDE", "cucina", "Cucina bahamense")], "wp_en": []}
        self.assertEqual(wp_fallback(secs), secs["wp"])


if __name__ == "__main__":
    unittest.main()
