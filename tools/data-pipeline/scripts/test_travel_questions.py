#!/usr/bin/env python3
"""Test di travel_questions.py (senza rete): python3 test_travel_questions.py"""
import tempfile
import unittest
from collections import Counter
from pathlib import Path

import travel_questions as tq

KEYWORDS = {"SALUTE": ["hospital", "vaccin"], "TRASPORTI": [" bus", "train"], "ALLOGGIO": ["hotel", "hostel"],
            "ACQUISTI": ["dress", "money"]}


def cat(q):
    return tq.classify(q, KEYWORDS)


class ParseDataTest(unittest.TestCase):
    def test_lista_con_apostrofi_e_virgolette(self):
        raw = "['Which hiking trail would you recommend?', \"As an AI language model, I don't know.\", 'Thanks!']"
        self.assertEqual(tq.parse_data(raw), "Which hiking trail would you recommend?")

    def test_spazi_normalizzati(self):
        self.assertEqual(tq.parse_data("['Is  there\\na hospital?', 'Yes']"), "Is there a hospital?")

    def test_non_lista(self):
        self.assertIsNone(tq.parse_data("{'title': 'Thanksgiving Travel - Help!'}"))
        self.assertIsNone(tq.parse_data("[]"))
        self.assertIsNone(tq.parse_data("[1, 2]"))
        self.assertIsNone(tq.parse_data("non e' python"))


class ClassifyTest(unittest.TestCase):
    def test_domanda_adatta(self):
        self.assertEqual(cat("Where is the nearest hospital?"), ("SALUTE", None))
        self.assertEqual(cat("Should I get a vaccination before going?"), ("SALUTE", None))  # "I" ammesso
        self.assertEqual(cat("Is there a bus to the airport?"), ("TRASPORTI", None))  # radice con spazio

    def test_lunghezza(self):
        self.assertEqual(cat("Any hotel?")[1], "lunghezza")
        self.assertEqual(cat("Is there a hotel " + "really " * 20 + "near?")[1], "lunghezza")

    def test_forma(self):
        self.assertEqual(cat("Which hotel is best.")[1], "forma")  # niente "?"
        self.assertEqual(cat("Hotels are expensive. Is there a cheap hotel?")[1], "forma")  # due frasi
        self.assertEqual(cat("Is there a hôtel near the station?")[1], "forma")  # non ASCII
        self.assertEqual(cat("Hotel prices are high, are there cheap ones?")[1], "forma")  # non inizia da una domanda

    def test_cifre_e_url(self):
        self.assertEqual(cat("Is there a hotel for under 50 dollars?")[1], "cifre o url")
        self.assertEqual(cat("Is www.hotels.info a good hotel site?")[1], "cifre o url")

    def test_chiacchiera(self):
        self.assertEqual(cat("Can you write a poem about a hotel?")[1], "chiacchiera")
        self.assertEqual(cat("As an AI, what hotel would you pick?")[1], "forma")  # non inizia da una domanda
        self.assertEqual(cat("Can you recommend me a good hotel?")[1], "chiacchiera")
        self.assertEqual(cat("What is your favorite hotel?")[1], "chiacchiera")

    def test_da_saggio(self):
        self.assertEqual(cat("How has the hotel industry changed?")[1], "da saggio")
        self.assertEqual(cat("Are there hotels such as hostels nearby?")[1], "da saggio")

    def test_prima_persona(self):
        self.assertEqual(cat("Is my hotel safe from theft?")[1], "prima persona")
        self.assertEqual(cat("Where can we find a hostel?")[1], "prima persona")
        self.assertEqual(cat("I'm tired, is there a hotel nearby?")[1], "forma")

    def test_nome_proprio(self):
        self.assertEqual(cat("Is there a cheap hotel in Paris?")[1], "nome proprio")
        self.assertEqual(cat("Is the hotel near Central Station?")[1], "nome proprio")

    def test_categoria(self):
        self.assertEqual(cat("Is it rude to wear shorts?")[1], "nessuna categoria")
        self.assertEqual(cat("Is there a train to the hospital?")[1], "ambigua")

    def test_radice_a_inizio_parola(self):
        # "dress" non e' in "address"
        self.assertEqual(cat("What is the address of the post office?")[1], "nessuna categoria")
        self.assertEqual(cat("Is there a dress code at the temple?"), ("ACQUISTI", None))


class SelectTest(unittest.TestCase):
    QS = ["Is there a bus to the airport?", "is there a bus to the airport?", "Where is the nearest hospital?",
          "Is there a hotel near the beach?", "Is there a train to the hospital?", "Is there a hotel in Rome?",
          "How much money should I carry?"]

    def test_categorie_dedup_e_stats(self):
        stats = Counter()
        out = tq.select(self.QS, KEYWORDS, stats=stats)
        self.assertEqual(out["TRASPORTI"], ["Is there a bus to the airport?"])  # la variante minuscola e' un doppione
        self.assertEqual(out["SALUTE"], ["Where is the nearest hospital?"])
        self.assertEqual(out["ALLOGGIO"], ["Is there a hotel near the beach?"])
        self.assertEqual(out["ACQUISTI"], ["How much money should I carry?"])
        self.assertEqual(stats, Counter({"doppione": 1, "ambigua": 1, "nome proprio": 1}))

    def test_tetto_deterministico(self):
        qs = [f"Is there a hotel with a {w} room?" for w in "red green blue pink gray gold teal plum lime navy".split()]
        a = tq.select(qs, KEYWORDS, per_category=4, seed=1)["ALLOGGIO"]
        self.assertEqual(len(a), 4)
        self.assertEqual(a, tq.select(list(reversed(qs)), KEYWORDS, per_category=4, seed=1)["ALLOGGIO"])
        self.assertEqual(a, sorted(a))
        self.assertTrue(set(a) <= set(qs))


class FakeTranslator:
    def __init__(self, table):
        self.table, self.calls = table, []

    def translate_many(self, bodies):
        self.calls.append(list(bodies))
        return [self.table.get(b) for b in bodies]


class ToItalianTest(unittest.TestCase):
    def test_scarti(self):
        by_cat = {"SALUTE": ["Where is a hospital?", "Is there a vaccination?", "Is a hospital free?", "Is a hospital close?"],
                  "ALLOGGIO": ["Is there a hotel?", "Is there a hostel?"], "TRASPORTI": []}
        fake = FakeTranslator({"Where is a hospital?": "Dov'e' un ospedale?",
                               "Is there a vaccination?": None,  # traduzione sospetta
                               "Is a hospital free?": "L'ospedale e' gratuito",  # senza "?"
                               "Is a hospital close?": "  ",  # vuota
                               "Is there a hotel?": "C'e' un albergo?", "Is there a hostel?": "C'e' un albergo?"})  # doppione
        out = tq.to_italian(by_cat, fake)
        self.assertEqual(out, {"SALUTE": ["Dov'e' un ospedale?"], "ALLOGGIO": ["C'e' un albergo?"], "TRASPORTI": []})
        self.assertEqual(len(fake.calls), 1)  # un solo lotto


class LoadTest(unittest.TestCase):
    def test_cache_riusata_senza_rete(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / tq.CACHE_NAME).write_text(
                "ultrachat\tWhere is the nearest hospital?\nultrachat\tIs there a hotel near the beach?\n", encoding="utf-8")
            en = tq.load(d, "en", KEYWORDS)
            self.assertEqual(en["SALUTE"], ["Where is the nearest hospital?"])
            fake = FakeTranslator({"Where is the nearest hospital?": "Dov'e' l'ospedale piu' vicino?"})
            it = tq.load(d, "it", KEYWORDS, translator=fake)
            self.assertEqual(it["SALUTE"], ["Dov'e' l'ospedale piu' vicino?"])
            self.assertEqual(it["ALLOGGIO"], [])  # traduzione assente: scartata

    def test_attribuzione(self):
        self.assertEqual(len(tq.ATTRIBUTION), len(tq.ALLOWED_SOURCES))
        self.assertTrue(all(r[0] == "-" and r[3] == "MIT" and r[2].startswith("https://") for r in tq.ATTRIBUTION))

    def test_categorie_del_generatore(self):
        """Le categorie con domande reali (generate_sft.py) esistono nelle parole chiave inglesi con cui si classificano e
        tra le domande (dei paesi o delle citta') delle due lingue."""
        import generate_sft
        import generate_sft_dataset_en
        for cat in generate_sft.REAL_QUESTION_CATS:
            self.assertIn(cat, generate_sft_dataset_en.KEYWORDS)
            for lang in ("it", "en"):
                L = generate_sft.LANGS[lang]
                self.assertTrue(cat in L["questions"] or cat in L["city_questions"], (cat, lang))


if __name__ == "__main__":
    unittest.main()
