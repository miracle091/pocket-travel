#!/usr/bin/env python3
"""Test di build_word_lists.py: radici candidate per word_lists.txt col log-odds pesato, su guide finte in una cache
temporanea, senza rete.

Uso: python test_build_word_lists.py
"""
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

import build_word_lists as bw
import word_lists


# Sezioni finte: "piscina" e "stanze" stanno nell'alloggio; "costa" e "costruito" altrove, cosi' "cost" non e' una
# radice dell'alloggio anche se "costoso" lo e'; "Hotelbello" e' un nome (sempre con la maiuscola).
ROWS = ([("ALLOGGIO", f"citta{i}", "Albergo con piscina e stanze ampie, Hotelbello costoso. Dove dormire.") for i in range(8)]
        + [("COSA_VEDERE", f"citta{i}", "La costa e il castello costruito nel Medioevo. Dove andare.") for i in range(8)]
        + [("TRASPORTI", f"citta{i}", "Il treno arriva in stazione dalla costa. Dove scendere.") for i in range(8)])


class RulesTest(unittest.TestCase):
    def test_hits_come_question_groups(self):
        self.assertTrue(bw.hits("pisc", "piscina"))
        self.assertFalse(bw.hits("bus", "buses"))
        self.assertTrue(bw.hits("bus", "bus") and bw.hits("bus", "buss"))
        self.assertTrue(bw.hits("road=", "roads"))
        self.assertFalse(bw.hits("road=", "roadside"))

    def test_split_words_minuscole_senza_accenti(self):
        self.assertEqual(bw.split_words("Città_vecchia, è"), [("citta", True), ("vecchia", False), ("e", False)])


class StatsTest(unittest.TestCase):
    def setUp(self):
        self.counts = bw.Counts(ROWS)

    def test_z_positivo_solo_nella_categoria_tipica(self):
        z_in, precision = bw.stem_stats(self.counts, "ALLOGGIO", "piscina=", 10)
        z_out, _ = bw.stem_stats(self.counts, "TRASPORTI", "piscina=", 10)
        self.assertGreater(z_in, 0)
        self.assertEqual(z_out, 0.0)  # nessuna occorrenza nella categoria
        self.assertAlmostEqual(precision, 1.0)
        self.assertLess(bw.z_score(1, 1000, 100, 2000, 10), 0)

    def test_radice_corta_solo_se_precisa(self):
        self.assertEqual(bw.choose_stem(self.counts, "ALLOGGIO", "piscina", 10, 0.8), "pisc")
        # "cost" trova anche "costa" e "costruito": serve una lettera in piu'
        self.assertEqual(bw.choose_stem(self.counts, "ALLOGGIO", "costoso", 10, 0.8), "costo")
        self.assertEqual(bw.choose_stem(self.counts, "ALLOGGIO", "spa", 10, 0.8), "spa")

    def test_parola_intera_con_uguale_se_ogni_prefisso_e_impreciso(self):
        counts = bw.Counts(ROWS + [("TRASPORTI", f"citta{i}", "nave navette navetta") for i in range(8)]
                           + [("ALLOGGIO", f"citta{i}", "nave") for i in range(8)])
        self.assertEqual(bw.choose_stem(counts, "ALLOGGIO", "nave", 10, 0.8), "nave=")

    def test_candidate_senza_nomi_luoghi_stopword_e_parole_gia_trovate(self):
        found = bw.candidates(self.counts, {"citta0"}, "ALLOGGIO", 10, prior=10, min_sections=2, max_section_share=0.5,
                              min_places=3, triggers={"ALLOGGIO": "alberg", "CLIMA": "caldo"})
        stems = [c["stem"] for c in found]
        self.assertIn("pisc", stems)
        self.assertIn("stan", stems)
        self.assertNotIn("hote", stems)  # nome con la maiuscola
        self.assertFalse(any(s.startswith("alb") for s in stems))  # gia' trovata da "alberg"
        self.assertFalse(any(s.startswith("dove") for s in stems))  # in ogni sezione
        self.assertEqual(bw.candidates(self.counts, set(), "SALUTE", 5), [])

    def test_poche_citta_esclude(self):
        found = bw.candidates(self.counts, set(), "ALLOGGIO", 10, prior=10, min_sections=2, max_section_share=0.5,
                              min_places=9, triggers={})
        self.assertEqual(found, [])


class FilesTest(unittest.TestCase):
    def test_with_stems_aggiunge_una_riga_in_fondo_alla_sezione(self):
        text = ("# intestazione\n[stopwords]\nquale\n[triggers ALLOGGIO]\nallogg dormi\nhotel\n"
                "# commento della sezione dopo\n[triggers CIBO_BEVANDE]\nmangi\n")
        out = bw.with_stems(text, {"ALLOGGIO": ["pisc", "stanze="], "CIBO_BEVANDE": []})
        self.assertEqual(out, ("# intestazione\n[stopwords]\nquale\n[triggers ALLOGGIO]\nallogg dormi\nhotel\npisc stanze=\n"
                               "# commento della sezione dopo\n[triggers CIBO_BEVANDE]\nmangi\n"))
        with self.assertRaises(ValueError):
            bw.with_stems(text, {"SALUTE": ["farmac"]})

    def test_with_stems_sul_file_dell_app(self):
        out = bw.with_stems(bw.WORD_LISTS_PATH.read_text(encoding="utf-8"), {"TRASPORTI": ["km="]})
        sections = word_lists.sections(out)
        self.assertEqual(sections["triggers TRASPORTI"][-1], "km=")

    def test_read_corpus_guide_e_citta(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            with closing(sqlite3.connect(cache / "guides.db")) as db:
                db.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT)")
                db.execute("INSERT INTO guide_sections VALUES ('san-marino', 'SALUTE', 'Salute', 'Farmacie aperte')")
                db.commit()
            with closing(sqlite3.connect(cache / "italia.cities.db")) as db:
                db.execute("CREATE TABLE city_sections (city TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
                db.execute("INSERT INTO city_sections VALUES ('Reggio Emilia', 'ALLOGGIO', NULL, 'Ostello', '')")
                db.commit()
            rows, names = bw.read_corpus(cache, "it", ["italia", "grecia"])
        self.assertEqual(rows, [("SALUTE", "san-marino", "Salute\nFarmacie aperte"), ("ALLOGGIO", "Reggio Emilia", "\nOstello")])
        self.assertTrue({"san", "marino", "reggio", "emilia", "italia", "grecia"} <= names)


if __name__ == "__main__":
    unittest.main()
