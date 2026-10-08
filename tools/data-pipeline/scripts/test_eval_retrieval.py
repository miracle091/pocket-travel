#!/usr/bin/env python3
"""Test di eval_retrieval.py: la replica della scelta del contesto dell'assistente (TravelAssistant.kt,
FtsRanking.kt) su un database FTS4 in memoria, senza rete.

Uso: python test_eval_retrieval.py
"""
import re
import sqlite3
import tempfile
import unittest
from pathlib import Path

import eval_retrieval as ev


class ReplicaTest(unittest.TestCase):
    def test_fts_query_come_build_fts_query(self):
        self.assertEqual(ev.fts_query("Quale valuta si usa a San Marino?", "san-marino"), "valut*")
        self.assertEqual(ev.fts_query("hi to a in", "italia"), "")
        self.assertEqual(ev.fts_query("Quali sono i piatti tipici della Puglia?", "italia"), "piatt* OR tipic* OR pugli*")
        self.assertEqual(ev.fts_query("Serve il passaporto per entrare?", "italia"), "passap* OR entra*")
        self.assertEqual(ev.fts_query("What are the typical dishes?", "italia"), "typic* OR dishe*")

    def test_fts_query_senza_la_citta_nominata(self):
        self.assertEqual(ev.fts_query("Cosa si mangia a Napoli?", "italia", "Napoli"), "cosa OR mangi*")
        self.assertEqual(ev.fts_query("Cosa vedere a Forli?", "italia", "Forlì"), "cosa OR veder*")
        self.assertEqual(ev.fts_query("Napoli?", "italia", "Napoli"), "napol*")

    def test_stopwords_come_l_app(self):
        kotlin = (Path(__file__).resolve().parents[3] / "feature/ai/src/main/kotlin/com/pockettravel/feature/ai/TravelAssistant.kt")
        block = kotlin.read_text(encoding="utf-8").split("private val questionStopwords = setOf(")[1].split(")")[0]
        self.assertEqual(set(re.findall(r'"(\w+)"', block)), ev.STOPWORDS)

    def test_named_cities_senza_disambiguatore_e_accenti(self):
        cities = ["Porto (Portogallo)", "Porto Santo", "Forlì", "Bra", "Braga", "Ne"]
        self.assertEqual(ev.named_cities("Cosa vedere a Porto?", cities), ["Porto (Portogallo)"])
        self.assertEqual(ev.named_cities("Come arrivare a Porto Santo?", cities), ["Porto Santo"])
        self.assertEqual(ev.named_cities("Dove dormire a forli?", cities), ["Forlì"])
        self.assertEqual(ev.named_cities("Musei di Braga", cities), ["Braga"])
        self.assertEqual(ev.named_cities("Quanti ne servono?", cities), [])
        homonyms = ["Nice", "Mobile", "Split", "Malé"]
        self.assertEqual(ev.named_cities("Is there a nice beach?", homonyms, "en"), [])
        self.assertEqual(ev.named_cities("Mi sento male, dove trovo un medico?", homonyms), [])
        self.assertEqual(ev.named_cities("Beaches in nice?", homonyms, "en"), ["Nice"])
        self.assertEqual(ev.named_cities("Cosa vedere a male?", homonyms), ["Malé"])
        self.assertEqual(ev.named_cities("Quanto dista Siena da Firenze?", ["Firenze", "Roma", "Siena"]), ["Firenze", "Siena"])

    def test_focus_stems_senza_la_citta(self):
        self.assertEqual(ev.focus_stems("estate OR piove OR rimini", "Rimini"), {"estat", "piove"})
        self.assertEqual(ev.focus_stems("cosa OR mangi* OR passap*", "Napoli"), {"cosa", "mangi", "passa"})

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

    def test_cibo_della_citta_nominata_prima_delle_altre_sezioni(self):
        # Il nome "Rimini" non conta nella classifica: vince la sezione con "dormire", non quella che nomina di piu' Rimini.
        top, stems = ev.search(self.db, "italia", "Dove dormire a Rimini?", self.cities)
        self.assertEqual([(s["city"], s["category"]) for s in top][:1], [("Rimini", "ALLOGGIO")])
        self.assertEqual(stems, {"dove", "dormi"})

    def test_parole_di_storia_e_clima(self):
        self.assertTrue(ev.HISTORY_CLIMATE_WORDS.search("Does it rain a lot in Porto?"))
        self.assertFalse(ev.HISTORY_CLIMATE_WORDS.search("How do I get to Porto by train?"))

    def test_due_citta_nominate_e_ripiego_sul_nome(self):
        rows = [
            ("Siena", "TRASPORTI", "Come arrivare", "Da Firenze (72 km) con la Chiantigiana.", "u"),
            ("Siena", "COSA_VEDERE", "Cosa vedere", "Piazza del Campo, cuore di Siena.", "u"),
            ("Firenze", "TRASPORTI", "Come arrivare", "Aeroporto a 5 km dal centro di Firenze.", "u"),
        ]
        db = ev.build_db(Path(self.tmp.name) / "guides.db", "italia", rows)
        top, _ = ev.search(db, "italia", "Quanto dista Siena da Firenze?", ["Firenze", "Siena"])
        self.assertIn(rows[0][3], [s["body"] for s in top])
        self.assertEqual({s["city"] for s in top}, {"Firenze", "Siena"})
        # Nessuna sezione di Siena parla di musei: si cerca anche "siena", e le sue sezioni vengono prima del paese.
        top, _ = ev.search(db, "italia", "Quali musei ci sono a Siena?", ["Firenze", "Siena"])
        self.assertEqual([(s["city"], s["category"]) for s in top], [("Siena", "COSA_VEDERE")])
        db.close()

    def test_sezioni_per_una_nazionalita_come_nell_app_senza_nazionalita(self):
        guides = Path(self.tmp.name) / "palestina.db"
        g = sqlite3.connect(guides)
        g.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
        g.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?)", [
            ("palestina", "SICUREZZA", "Aree a rischio", "Evitare Gaza.", "u#for-nationality=IL"),
            ("palestina", "SICUREZZA", "Israele", "Vedi la guida di Israele.", "u#not-for-nationality=IL"),
            ("palestina", "SALUTE", "Salute", "Bere acqua.", "u"),
        ])
        g.commit()
        g.close()
        db = ev.build_db(guides, "palestina", [])
        self.assertEqual([t for (t,) in db.execute("SELECT title FROM guide_sections ORDER BY id")], ["Israele", "Salute"])
        db.close()

    def test_matchinfo_pcxnal_letto(self):
        blob = self.db.execute("SELECT matchinfo(city_sections_fts, 'pcxnal') FROM city_sections_fts "
                               "WHERE city_sections_fts MATCH 'alberghi'").fetchone()[0]
        info = ev.parse_matchinfo(blob)
        self.assertEqual((info["p"], info["c"], info["n"]), (1, 2, 3))
        self.assertEqual(info["docs"][0][1], 3)


if __name__ == "__main__":
    unittest.main()
