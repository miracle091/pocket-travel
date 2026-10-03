#!/usr/bin/env python3
"""Test di sft_nearby.py e degli esempi --nearby di generate_sft.py: i blocchi "qui vicino" e "prossime partenze" sono
gli stessi di nearbyPoiContext e transitContext (TravelAssistant.kt, casi di TravelAssistantLogicTest), le etichette quelle
di strings.xml, le domande attivano il blocco giusto e le risposte usano solo fatti del blocco.

Uso: python test_sft_nearby.py
"""
import html
import random
import re
import unittest
from datetime import date
from pathlib import Path

import generate_sft
import generate_sft_dataset as it
from sft_nearby import (ASK, CATS, MODES, NEARBY_CONTEXT_MAX, NEARBY_WORDS, TRANSIT_ASK, TRANSIT_WORDS, nearby_pois,
                        nearby_radius, poi_context, poi_example, transit_context, transit_example)

RES = Path(__file__).resolve().parents[3] / "core" / "ui" / "src" / "main" / "res"
REFUSAL = {"it": "Il contesto non contiene informazioni", "en": "The context does not contain information"}


def refusal(lang):
    return lambda topic: generate_sft.LANGS[lang]["refusal"](topic, "verifica.")


class ContextFormatTest(unittest.TestCase):
    def test_poi_come_l_app(self):
        pois = [("FARMACIA", "Farmacia Centrale", 80, "Mo-Fr 08:30-19:30"), ("CIBO_BEVANDE", "Bar Roma", 120, None),
                ("FARMACIA", "Farmacia Nord", 250, None)]
        self.assertEqual(poi_context(pois, 300, "it"),
                         "Punti di interesse entro 300 m dalla tua posizione:\n"
                         "Farmacie: Farmacia Centrale (80 m, orari Mo-Fr 08:30-19:30), Farmacia Nord (250 m)\n"
                         "Ristoranti e bar: Bar Roma (120 m)")
        self.assertEqual(poi_context([("FARMACIA", "Pharmacy Riga", 40, None)], 150, "en"),
                         "Points of interest within 150 m of your position:\nPharmacies: Pharmacy Riga (40 m)")
        self.assertEqual(poi_context([("BANCOMAT", "atm", 25, "24/7")], 600, "en"),
                         "Points of interest within 600 m of your position:\nATMs: atm (25 m, hours 24/7)")
        self.assertIsNone(poi_context([], 600, "it"))

    def test_partenze_come_l_app(self):
        board = ("departures", [(14 * 60 + 5, 3, "TRAM", "7", "Centrale"), (14 * 60 + 20, 18, "BUS", "22", None)])
        self.assertEqual(transit_context(board, "it"),
                         "Prossime partenze dalle fermate qui vicino:\n14:05 (tra 3 min) Tram 7 per Centrale\n14:20 (tra 18 min) Autobus 22")
        self.assertEqual(transit_context(board, "en"),
                         "Next departures from the stops nearby:\n14:05 (in 3 min) Tram 7 to Centrale\n14:20 (in 18 min) Bus 22")
        self.assertEqual(transit_context(("expired", date(2026, 9, 30)), "it"),
                         "Gli orari dei mezzi pubblici installati sono scaduti il 30/9/2026.")
        self.assertEqual(transit_context(("expired", date(2026, 9, 30)), "en"),
                         "The installed public transport timetables expired on 2026-09-30.")
        self.assertEqual(transit_context(("departures", []), "en"), "No departures in the next hours from the stops nearby.")
        self.assertIsNone(transit_context(None, "it"))

    def test_raggio_come_poi_repository(self):
        self.assertEqual(nearby_radius([10, 20, 30, 40, 150]), 150)
        self.assertEqual(nearby_radius([10, 20, 30, 40, 151, 300]), 300)
        self.assertEqual(nearby_radius([10, 20, 30, 40]), 600)

    def test_etichette_e_mezzi_come_strings_xml(self):
        labels = {}
        for lang, folder in (("it", "values"), ("en", "values-en")):
            text = (RES / folder / "strings.xml").read_text(encoding="utf-8")
            labels[lang] = {k: html.unescape(v) for k, v in re.findall(r'<string name="(poi_\w+)">([^<]*)</string>', text)}
        it_to_key = {v: k for k, v in labels["it"].items()}
        for cat, (label_it, label_en, *_) in CATS.items():
            key = it_to_key.get(label_it)
            self.assertIsNotNone(key, cat)
            self.assertEqual(labels["en"][key], label_en, cat)
        # promptName di TravelAssistant.kt
        self.assertEqual(MODES["BUS"][:2], ("Autobus", "Bus"))
        self.assertEqual(MODES["CABLE"][:2], ("Funivia", "Cable car"))


class QuestionsTest(unittest.TestCase):
    def test_domande_vicino_attivano_solo_il_blocco_dei_poi(self):
        for lang in ASK:
            for questions, *_ in ASK[lang].values():
                for q in questions:
                    self.assertTrue(NEARBY_WORDS.search(q), q)
                    self.assertFalse(TRANSIT_WORDS.search(q), q)

    def test_domande_partenze_attivano_solo_il_tabellone(self):
        for lang, T in TRANSIT_ASK.items():
            questions = T["any"] + [q for qs in T["mode"].values() for q in qs] + [q.format(l="22") for qs in T["line"].values() for q in qs]
            for q in questions:
                self.assertTrue(TRANSIT_WORDS.search(q), q)
                self.assertFalse(NEARBY_WORDS.search(q), q)


class ExamplesTest(unittest.TestCase):
    def test_poi_sintetici_nel_raggio_e_in_ordine(self):
        rng = random.Random(7)
        for _ in range(300):
            radius, pois = nearby_pois(rng, must="FARMACIA")
            meters = [p[2] for p in pois]
            self.assertEqual(meters, sorted(meters))
            self.assertLessEqual(len(pois), NEARBY_CONTEXT_MAX)
            self.assertTrue(all(m <= radius for m in meters))
            self.assertIn("FARMACIA", {p[0] for p in pois})
            self.assertNotIn("FARMACIA", {p[0] for p in nearby_pois(rng, avoid="FARMACIA")[1]})

    def test_risposte_solo_dal_blocco_e_rifiuti_nello_stile_esistente(self):
        for lang in ("it", "en"):
            rng, kinds = random.Random(11), []
            for make in (poi_example, transit_example):
                for _ in range(1000):
                    block, q, answer, kind = make(rng, lang, refusal(lang))
                    kinds.append(kind)
                    if kind == "neg":
                        self.assertTrue(answer.startswith(REFUSAL[lang]), answer)
                        continue
                    self.assertIsNotNone(block, q)
                    self.assertLessEqual(answer.count(". "), 2, answer)  # al massimo 3 frasi
                    # distanze, orari e attese della risposta sono nel blocco
                    for fact in re.findall(r"\d+ m\b|\d\d:\d\d|\d+ min", answer):
                        self.assertIn(fact, block, f"{answer}\n{block}")
            share = kinds.count("neg") / len(kinds)
            self.assertTrue(0.15 < share < 0.4, share)

    def test_contesto_col_blocco_prima_della_guida(self):
        block = "Punti di interesse entro 150 m dalla tua posizione:\nFarmacie: Farmacia Rossi (40 m)"
        bodies = ["x" * 1500, "Le farmacie sono aperte fino alle 20."]
        for seed in range(20):
            context = generate_sft.nearby_context(random.Random(seed), block, "Dov'è la farmacia più vicina?", "Italia",
                                                  bodies, generate_sft.LANGS["it"])
            self.assertTrue(context.startswith(block + "\n\n") or context == block)
            self.assertLessEqual(len(context), it.MAX_CONTEXT)

    def test_senza_blocco_niente_sezioni_che_rispondono(self):
        L = generate_sft.LANGS["it"]
        bodies = ["Le farmacie sono aperte fino alle 20.", "Gli autobus passano ogni dieci minuti.", "Il clima e' mite."]
        for seed in range(20):
            rng = random.Random(seed)
            poi = generate_sft.nearby_context(rng, None, "Dov'è la farmacia più vicina?", "Italia", bodies, L, empty=0)
            self.assertNotIn("farmacie", poi)
            transit = generate_sft.nearby_context(rng, None, "Quando passa il prossimo tram?", "Italia", bodies, L, transport=True, empty=0)
            self.assertNotIn("autobus", transit)  # tratta i trasporti: fuori anche se non nomina il tram
        self.assertEqual(generate_sft.nearby_context(random.Random(1), None, "Dov'è la farmacia più vicina?", "Italia", bodies[:1], L),
                         L["fallback"])


if __name__ == "__main__":
    unittest.main()
