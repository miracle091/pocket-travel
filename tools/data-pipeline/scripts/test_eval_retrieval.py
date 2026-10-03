#!/usr/bin/env python3
"""Test di eval_retrieval.py: la replica della ricerca dell'assistente (TravelAssistant.kt, FtsRanking.kt) su un
database FTS4 in memoria, senza rete.

Uso: python test_eval_retrieval.py
"""
import sqlite3
import tempfile
import unittest
from pathlib import Path

import eval_retrieval as ev


class ReplicaTest(unittest.TestCase):
    def test_fts_query_come_build_fts_query(self):
        self.assertEqual(ev.fts_query("Quale valuta si usa a San Marino?", "san-marino"), "quale OR valuta")
        self.assertEqual(ev.fts_query("hi to a in", "italia"), "")

    def test_named_city_senza_disambiguatore_e_accenti(self):
        cities = ["Porto (Portogallo)", "Porto Santo", "Forlì", "Bra", "Braga", "Ne"]
        self.assertEqual(ev.named_city("Cosa vedere a Porto?", cities), "Porto (Portogallo)")
        self.assertEqual(ev.named_city("Come arrivare a Porto Santo?", cities), "Porto Santo")
        self.assertEqual(ev.named_city("Dove dormire a forli?", cities), "Forlì")
        self.assertEqual(ev.named_city("Musei di Braga", cities), "Braga")
        self.assertIsNone(ev.named_city("Quanti ne servono?", cities))
        homonyms = ["Nice", "Mobile", "Split", "Malé"]
        self.assertIsNone(ev.named_city("Is there a nice beach?", homonyms, "en"))
        self.assertIsNone(ev.named_city("Mi sento male, dove trovo un medico?", homonyms))
        self.assertEqual(ev.named_city("Beaches in nice?", homonyms, "en"), "Nice")
        self.assertEqual(ev.named_city("Cosa vedere a male?", homonyms), "Malé")

    def test_focus_stems_senza_la_citta(self):
        self.assertEqual(ev.focus_stems("estate OR piove OR rimini", "Rimini"), {"estat", "piove"})

    def test_relevant_paragraphs_e_select_context(self):
        clima = "Il clima e' temperato.\nIn inverno nevica spesso in collina.\nLe estati sono calde e afose."
        self.assertEqual(ev.relevant_paragraphs(clima, {"estat"}, 60), "Il clima e' temperato.\nLe estati sono calde e afose.")
        storia = "Fondata dai Romani.\n" + "B" * 300 + "\nNel Medioevo fu libero comune."
        self.assertEqual(ev.select_context(["A" * 100, storia], {"roman", "medio"}, max_chars=160),
                         "A" * 100 + "\n\nFondata dai Romani.\nNel Medioevo fu libero comune.")


class SearchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        guides = Path(self.tmp.name) / "guides.db"
        g = sqlite3.connect(guides)
        g.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
        g.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?)", [
            ("italia", "DOGANE", "Dogane", "Per entrare serve il passaporto o la carta d'identita'.", "u"),
            ("italia", "TRASPORTI", "Come muoversi", "I treni collegano le citta' principali.", "u"),
        ])
        g.commit()
        g.close()
        rows = [
            ("Rimini", "ALLOGGIO", "Dove alloggiare", "Alberghi sul lungomare per dormire vicino al mare.", "u"),
            ("Rimini", "STORIA", "Storia", "Colonia romana.\n" + "Alberghi e treni del Novecento. " * 40, "u"),
            ("Bologna", "ALLOGGIO", "Dove alloggiare", "Alberghi in centro per dormire vicino alla stazione.", "u"),
        ]
        self.db = ev.build_db(guides, "italia", rows)
        self.cities = ["Bologna", "Rimini"]

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def test_la_citta_nominata_limita_le_sezioni_delle_citta(self):
        top, _ = ev.search(self.db, "italia", "Dove dormire a Rimini?", self.cities)
        self.assertEqual(top[0]["city"], "Rimini")
        self.assertEqual(top[0]["category"], "ALLOGGIO")
        self.assertNotIn("Bologna", [s["city"] for s in top])

    def test_senza_citta_viene_prima_la_guida_del_paese(self):
        top, _ = ev.search(self.db, "italia", "Serve il passaporto per entrare?", self.cities)
        self.assertEqual(top[0]["category"], "DOGANE")
        self.assertIsNone(top[0]["city"])

    def test_storia_pesa_poco_se_la_domanda_non_ne_parla(self):
        # La Storia di Rimini ripete "alberghi" e "treni" 40 volte: senza parole di storia finisce dopo l'alloggio.
        top, _ = ev.search(self.db, "italia", "Treni e alberghi a Rimini?", self.cities)
        categories = [s["category"] for s in top]
        self.assertLess(categories.index("ALLOGGIO"), categories.index("STORIA"))
        top, _ = ev.search(self.db, "italia", "Storia di treni e alberghi a Rimini?", self.cities)
        self.assertEqual(top[0]["category"], "STORIA")

    def test_parole_di_storia_e_clima(self):
        self.assertTrue(ev.HISTORY_CLIMATE_WORDS.search("Does it rain a lot in Porto?"))
        self.assertFalse(ev.HISTORY_CLIMATE_WORDS.search("How do I get to Porto by train?"))

    def test_matchinfo_pcxnal_letto(self):
        blob = self.db.execute("SELECT matchinfo(city_sections_fts, 'pcxnal') FROM city_sections_fts "
                               "WHERE city_sections_fts MATCH 'alberghi'").fetchone()[0]
        info = ev.parse_matchinfo(blob)
        self.assertEqual((info["p"], info["c"], info["n"]), (1, 2, 3))
        self.assertEqual(info["docs"][0][1], 3)


if __name__ == "__main__":
    unittest.main()
