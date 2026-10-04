#!/usr/bin/env python3
"""Test degli esempi --distances di generate_sft.py: con le coordinate la distanza calcolata in testa al contesto, con
lo stesso testo dell'app (cityDistanceContext in TravelAssistant.kt); senza, la frase della guida con km o tempi di
viaggio che nomina l'altra citta', e il rifiuto quando le due citta' si nominano senza.

Uso: python test_sft_distances.py
"""
import random
import re
import unittest

import eval_retrieval
import generate_sft
from generate_sft import (DIST_QUESTIONS, distance_context, distance_example, distance_pairs, distance_sentences,
                          haversine_m, travel_time)

REFUSAL = {"it": "Il contesto non contiene informazioni", "en": "The context does not contain information"}

TORINO = [("ARRIVARE", "Torino ha un aeroporto internazionale a Caselle. Da Milano sono 140 km in autostrada, circa 1 ora "
                       "e 40 minuti. Da Genova si arriva in treno."),
          ("COSA_VEDERE", "La Mole Antonelliana ospita il Museo del Cinema.")]
MILANO = [("TRASPORTI", "Milano ha tre linee di metropolitana e una fitta rete di tram e autobus.")]
GENOVA = [("ARRIVARE", "Genova ha un porto con traghetti per la Sardegna e la Corsica. La stazione principale e' Brignole.")]


def refusal(lang):
    return lambda topic: generate_sft.LANGS[lang]["refusal"](topic, "verifica.")


class DistanceSentencesTest(unittest.TestCase):
    def test_frase_con_la_citta_e_una_distanza(self):
        self.assertEqual(distance_sentences(TORINO[0][1], "Milano"),
                         ["Da Milano sono 140 km in autostrada, circa 1 ora e 40 minuti."])

    def test_senza_distanza_o_con_un_altro_nome_niente(self):
        self.assertEqual(distance_sentences(TORINO[0][1], "Genova"), [])
        self.assertEqual(distance_sentences("Da Milanofiori sono 5 km.", "Milano"), [])

    def test_unita_scritte_per_esteso(self):
        self.assertTrue(distance_sentences("Lisbon is 300 kilometres from Porto.", "Porto"))
        self.assertTrue(distance_sentences("Firenze dista 90 chilometri da Siena.", "Siena"))


class DistancePairsTest(unittest.TestCase):
    def test_coppie_con_e_senza_distanza(self):
        pairs = distance_pairs({"Torino": TORINO, "Milano": MILANO, "Genova": GENOVA})
        self.assertEqual(pairs, {("Torino", "Milano"): True, ("Torino", "Genova"): False})

    def test_solo_le_sezioni_per_arrivare_e_spostarsi(self):
        self.assertEqual(distance_pairs({"Torino": [("COSA_VEDERE", "Da Milano 140 km.")], "Milano": MILANO}), {})


class DistanceExampleTest(unittest.TestCase):
    by_name = {"Torino": TORINO, "Milano": MILANO, "Genova": GENOVA}

    def test_positivo_con_la_frase_della_guida(self):
        context, q, answer, kind, computed = distance_example(random.Random(1), "Torino", "Milano", self.by_name, "it", refusal("it"))
        self.assertEqual((kind, computed), ("pos", False))
        self.assertEqual(answer, "Da Milano sono 140 km in autostrada, circa 1 ora e 40 minuti.")
        self.assertIn(answer, context)
        self.assertIn("Torino", q)
        self.assertIn("Milano", q)

    def test_rifiuto_quando_la_guida_non_da_la_distanza(self):
        for lang in ("it", "en"):
            context, q, answer, kind, computed = distance_example(random.Random(1), "Torino", "Genova", self.by_name, lang, refusal(lang))
            self.assertEqual((kind, computed), ("neg", False))
            self.assertTrue(answer.startswith(REFUSAL[lang]), answer)
            self.assertIn("Genova", answer)
            self.assertIn("Genova", context)

    def test_dalla_pagina_dell_altra_citta(self):
        by_name = {"Milano": MILANO, "Torino": TORINO}
        _, _, answer, kind, _ = distance_example(random.Random(3), "Milano", "Torino", by_name, "it", refusal("it"))
        self.assertEqual((kind, answer), ("pos", "Da Milano sono 140 km in autostrada, circa 1 ora e 40 minuti."))

    def test_la_frase_della_guida_in_testa_al_contesto_non_e_una_distanza_calcolata(self):
        by_name = {"Asti": [("ARRIVARE", "Da Torino sono 60 km.")], "Torino": TORINO}
        context, _, answer, kind, computed = distance_example(random.Random(1), "Asti", "Torino", by_name, "it", refusal("it"))
        self.assertTrue(context.startswith(answer))
        self.assertEqual((kind, computed), ("pos", False))

    def test_senza_sezioni_per_arrivare_niente_esempio(self):
        self.assertIsNone(distance_example(random.Random(1), "Torino", "Milano", {"Torino": TORINO[1:], "Milano": MILANO},
                                           "it", refusal("it")))


class DistanceContextTest(unittest.TestCase):
    """Stessi testi attesi del test di cityDistanceContext in TravelAssistantLogicTest.kt: l'app li mette nel contesto."""

    def test_come_l_app(self):
        self.assertEqual("Torino e Milano distano 126 km in linea d'aria. Su strada la distanza è maggiore.",
                         distance_context("Torino", "Milano", 125_640.0, None, "it"))
        self.assertEqual("Torino e Milano distano 126 km in linea d'aria. In auto il percorso è di 142 km, circa 1 h 35 min.",
                         distance_context("Torino", "Milano", 125_640.0, (141_800.0, 5_710.0), "it"))
        self.assertEqual("Porto and Braga are 49 km apart in a straight line. By car the route is 55 km, about 45 min.",
                         distance_context("Porto (Portugal)", "Braga", 48_700.0, (55_200.0, 2_690.0), "en"))
        self.assertEqual("Pisa and Lucca are 1 km apart in a straight line. By road the distance is longer.",
                         distance_context("Pisa", "Lucca", 300.0, None, "en"))
        self.assertEqual("Torino e Milano distano 126 km in linea d'aria. In auto il percorso è di 142 km, circa 2 h.",
                         distance_context("Torino", "Milano", 125_640.0, (141_800.0, 7_190.0), "it"))

    def test_arrotonda_come_kotlin(self):
        self.assertEqual("3 min", travel_time(150))  # round() di Python darebbe 2
        self.assertEqual("1 min", travel_time(5))

    def test_haversine(self):
        self.assertAlmostEqual(126_000, haversine_m((45.07034, 7.68686), (45.46427, 9.18951)), delta=1_000)


class DistanceBlockTest(unittest.TestCase):
    by_name = {"Torino": TORINO, "Genova": GENOVA}
    coords = {"Torino": (45.07034, 7.68686), "Genova": (44.40565, 8.94626)}

    def examples(self, lang="it", n=60):
        rng = random.Random(5)
        return [distance_example(rng, "Torino", "Genova", self.by_name, lang, refusal(lang), coords=self.coords) for _ in range(n)]

    def test_con_le_coordinate_il_contesto_si_apre_con_la_distanza_calcolata(self):
        blocks = [e for e in self.examples() if e[4]]
        self.assertTrue(blocks)
        for context, q, answer, kind, computed in blocks:
            self.assertEqual(kind, "pos")
            self.assertTrue(context.startswith(answer))
            self.assertRegex(answer, r"^(Torino e Genova|Genova e Torino) distano 1\d\d km in linea d'aria\. ")
            self.assertLessEqual(len(context), generate_sft.it.MAX_CONTEXT)
            if "Su strada" in answer:  # sola linea d'aria: mai per una domanda sul solo tempo di viaggio
                self.assertIn(q, [t.format(c="Torino", o="Genova") for t in DIST_QUESTIONS["it"]["distance"]])
            else:
                self.assertRegex(answer, r"In auto il percorso è di \d+ km, circa (\d+ h)?( ?\d+ min)?\.$")

    def test_tutte_le_varianti_e_rifiuto_senza_blocco(self):
        answers = [e[2] for e in self.examples()]
        self.assertTrue(any("In auto" in a for a in answers))
        self.assertTrue(any("Su strada" in a for a in answers))
        self.assertTrue(any(a.startswith(REFUSAL["it"]) for a in answers))

    def test_senza_coordinate_di_una_citta_niente_blocco(self):
        rng = random.Random(5)
        for _ in range(20):
            context, _, answer, _, computed = distance_example(rng, "Torino", "Genova", self.by_name, "it", refusal("it"),
                                                     coords={"Torino": self.coords["Torino"]})
            self.assertNotIn("in linea d'aria", context)
            self.assertFalse(computed)

    def test_in_inglese(self):
        blocks = [e for e in self.examples("en") if e[4]]
        self.assertTrue(blocks)
        self.assertTrue(all(" km apart in a straight line." in a for _, _, a, _, _ in blocks))


class QuestionsTest(unittest.TestCase):
    def test_domande_disgiunte_dalla_valutazione(self):
        for lang, eval_questions in (("it", eval_retrieval.DISTANCE_Q), ("en", eval_retrieval.DISTANCE_Q_EN)):
            mine = DIST_QUESTIONS[lang]["distance"] + DIST_QUESTIONS[lang]["time"]
            self.assertFalse(set(mine) & set(eval_questions))

    def test_l_app_riconosce_le_domande(self):
        # Le stesse parole di distanceWords e travelTimeWords in TravelAssistant.kt: senza, l'app non mette il blocco.
        distance = re.compile(r"\b(dist(a|ano|ante|anti|anza|anze)|distance|distant|lontan\w*|chilometri|km|far|kilomet\w*|miles?)\b", re.I)
        time = re.compile(r"quanto ci (si )?(vuole|mette)|quanto tempo (serve|ci vuole|ci si mette) per (andare|arrivare)|"
                          r"how long (does|is|will) (it take|the (trip|drive|journey))", re.I)
        for lang in ("it", "en"):
            for q in DIST_QUESTIONS[lang]["distance"]:
                self.assertRegex(q, distance)
            for q in DIST_QUESTIONS[lang]["time"]:
                self.assertRegex(q, time)


if __name__ == "__main__":
    unittest.main()
