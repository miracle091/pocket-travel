#!/usr/bin/env python3
"""Test delle domande e dei rifiuti delle citta' di generate_sft.py: rifiuti difficili (hard_refusal_sections), domande del
training diverse da quelle dei test estesi, radici di CIBO_BEVANDE per specialita' e dolci (FOOD_KEYWORDS).

Uso: python test_sft_city_refusals.py
"""
import unittest

import generate_eval_set
import generate_eval_set_en
import generate_sft
import generate_sft_dataset as it
import generate_sft_dataset_en as en


class HardRefusalsTest(unittest.TestCase):
    def test_come_arrivare_senza_spostamenti_in_citta(self):
        arrive = "Si arriva in treno dalla stazione di Monaco o in aereo all'aeroporto di Stoccarda."
        moving = "Dalla stazione l'autobus 5 porta in centro in dieci minuti."
        secs = [("ARRIVARE", arrive), ("ARRIVARE", moving), ("COSA_VEDERE", "Il castello e' aperto tutto l'anno.")]
        # Come arrivare con treno e aereo vale anche se nomina i mezzi (non e' tra i [bodies] filtrati per parola chiave)
        self.assertEqual(generate_sft.hard_refusal_sections("TRASPORTI", secs, [secs[2][1]], "it"), [arrive])
        en_secs = [("ARRIVARE", "The nearest airport is Gatwick, with trains to London Victoria."),
                   ("ARRIVARE", "From the station, walk ten minutes or take bus 4.")]
        self.assertEqual(generate_sft.hard_refusal_sections("TRASPORTI", en_secs, [], "en"), [en_secs[0][1]])

    def test_dove_dormire_solo_se_non_parla_di_cibo(self):
        sleep = "La pensione Rossi ha camere doppie con vista sul lago."
        with_food = "L'albergo ha un ristorante con cucina tipica."
        secs = [("ALLOGGIO", sleep), ("ALLOGGIO", with_food)]
        bodies = [b for _, b in secs if not it.covers("CIBO_BEVANDE", b)]
        self.assertEqual(generate_sft.hard_refusal_sections("CIBO_BEVANDE", secs, bodies, "it"), [sleep])
        self.assertEqual(generate_sft.hard_refusal_sections("SHOPPING", secs, bodies, "it"), [])


class CityQuestionsTest(unittest.TestCase):
    def test_domande_del_training_fuori_dai_test(self):
        tests = {q for qs in generate_eval_set.PARA_CITY.values() for q in qs}
        tests |= {q for lang in ("it", "en") for qs in generate_eval_set.PARA_CITY_EXTRA[lang].values() for q in qs}
        tests |= {q for qs in generate_eval_set_en.PARA_CITY_EN.values() for q in qs}
        train = {q for pool in (it.CITY_QUESTIONS, it.CITY_QUESTIONS_EN, en.CITY_QUESTIONS) for qs in pool.values() for q in qs}
        self.assertFalse(train & tests)

    def test_cena_sistemazioni_e_senza_auto(self):
        # i giri di frase che il modello rifiutava, nel training con parole diverse da quelle del test
        joined = " ".join(q for c in ("CIBO_BEVANDE", "ALLOGGIO", "TRASPORTI") for q in it.CITY_QUESTIONS[c]).lower()
        for word in ("cenare", "cena", "sistemazion", "pernottare", "senza macchina"):
            self.assertIn(word, joined)
        self.assertTrue(any("connection" in q for q in en.CITY_QUESTIONS["CONNETTIVITA"]))
        self.assertTrue(all("internet" in q or "data" in q for q in en.CITY_QUESTIONS["CONNETTIVITA"] if "connection" in q))


class FoodKeywordsTest(unittest.TestCase):
    def test_specialita_e_dolci(self):
        L = generate_sft.with_food_keywords(generate_sft.LANGS["it"], "it")
        sentence = "Dolci tipici sono la torta Titano e la Cacciatelli."  # San Marino, A tavola
        self.assertFalse(it.covers("CIBO_BEVANDE", sentence))
        self.assertTrue(it.covers("CIBO_BEVANDE", sentence, L["keywords"]))
        L_en = generate_sft.with_food_keywords(generate_sft.LANGS["en"], "en")
        self.assertTrue(it.covers("CIBO_BEVANDE", "Try the local desserts and pastries.", L_en["keywords"]))
        # le tabelle dei test estesi restano quelle di prima
        self.assertNotIn("dolc", it.KEYWORDS["CIBO_BEVANDE"])
        self.assertNotIn("dessert", en.KEYWORDS["CIBO_BEVANDE"])


if __name__ == "__main__":
    unittest.main()
