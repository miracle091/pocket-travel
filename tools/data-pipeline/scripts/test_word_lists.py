#!/usr/bin/env python3
"""Test di word_lists.py: la lettura di word_lists.txt, il file delle liste di parole che legge anche l'app
(WordLists.kt, con gli stessi casi in WordListsTest).

Uso: python test_word_lists.py
"""
import re
import unittest

import word_lists as wl


class WordListsTest(unittest.TestCase):
    def test_sezioni_commenti_e_righe(self):
        text = "# commento\n[a]\nuno due\n\n# altro\ntre\n[b x]\nquattro\n"
        self.assertEqual(wl.sections(text), {"a": ["uno due", "tre"], "b x": ["quattro"]})

    def test_riga_fuori_da_una_sezione(self):
        with self.assertRaises(ValueError):
            wl.sections("uno\n[a]\n")

    def test_liste_del_file(self):
        self.assertIn("conviene", wl.STOPWORDS)
        self.assertNotIn("come", wl.STOPWORDS)  # nei titoli delle sezioni ("Come arrivare")
        self.assertIn("best+time", wl.CATEGORY_TRIGGERS["CLIMA"].split())
        self.assertEqual(wl.CATEGORY_GUIDE_WORDS["en"]["TRASPORTI"], "get in, get around")
        self.assertEqual(set(wl.CATEGORY_GUIDE_WORDS["it"]), set(wl.CATEGORY_GUIDE_WORDS["en"]))
        # Ogni gruppo delle radici ha le parole dei titoli in tutte e due le lingue.
        self.assertLessEqual(set(wl.CATEGORY_TRIGGERS), set(wl.CATEGORY_GUIDE_WORDS["it"]))
        self.assertIn("tomorrow", wl.NEAR_TIME_STEMS.split())

    def test_storia_e_clima_senza_spazi_tra_le_righe(self):
        self.assertEqual(wl.HISTORY_CLIMATE_WORDS.flags & re.I, re.I)
        self.assertTrue(wl.HISTORY_CLIMATE_WORDS.search("Quando conviene andare a Malta?"))
        self.assertTrue(wl.HISTORY_CLIMATE_WORDS.search("How old is Lagos?"))
        self.assertFalse(wl.HISTORY_CLIMATE_WORDS.search("How do I get to Porto by train?"))
        self.assertNotIn("\n", wl.HISTORY_CLIMATE_WORDS.pattern)


if __name__ == "__main__":
    unittest.main()
