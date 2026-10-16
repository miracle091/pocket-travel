#!/usr/bin/env python3
"""Test delle coppie di dpo_lora.py: gruppi, risposte scartate, regioni escluse, tetto per gruppo.

Uso: python test_dpo_lora.py
"""
import math
import unittest

from dpo_lora import build_pairs, context_of, dpo_loss, dpo_margin, first_sentence


def row(kind, region, category, context, answer, question="Cosa c'e'?"):
    content = f"Sei una guida turistica offline.\n\nCONTESTO: {context}\n\nDOMANDA: {question}"
    return {"messages": [{"role": "user", "content": content}, {"role": "assistant", "content": answer}],
            "kind": kind, "region": region, "category": category}


REFUSAL = "Il contesto non contiene informazioni sul cibo."
ROWS = [
    row("neg", "citta:Nicosia", "CIBO_BEVANDE", "▸ Storia\nFondata dai Greci. Poi romana.", REFUSAL),
    row("pos", "citta:Nicosia", "CIBO_BEVANDE", "▸ Cibo\nMeze ovunque.", "Meze ovunque."),
    row("pos", "cipro", "VICINO", "Punti di interesse entro 1 km:\n• Farmacia Kostas", "Farmacia Kostas"),
    row("neg", "cipro", "VICINO", "Punti di interesse entro 1 km:\n• Bar Zorba", "Nel contesto non ci sono ristoranti."),
    row("pos", "cipro", "SICUREZZA", "Zona sicura.", "Zona sicura."),
    row("neg", "citta:Berlino", "CIBO_BEVANDE", "▸ Storia\nMuro.", REFUSAL),
    row("neg", "germania", "CIBO_BEVANDE", "▸ Storia\nMuro.", REFUSAL),
]


class ContextTest(unittest.TestCase):
    def test_context_between_markers(self):
        self.assertEqual(context_of(ROWS[0]["messages"][0]["content"]), "▸ Storia\nFondata dai Greci. Poi romana.")

    def test_first_sentence_skips_titles_and_bullets(self):
        self.assertEqual(first_sentence("▸ Storia\nFondata dai Greci. Poi romana."), "Fondata dai Greci.")
        self.assertEqual(first_sentence("Punti di interesse entro 1 km:\n• Bar Zorba"), "Punti di interesse entro 1 km:")
        self.assertEqual(first_sentence("▸ Solo titolo"), "")


class PairsTest(unittest.TestCase):
    def test_groups_and_rejected_answers(self):
        pairs = build_pairs(ROWS, excluded_regions={"citta:Berlino"}, per_group={})
        self.assertEqual([len(pairs[g]) for g in ("neg_city", "pos_city", "pos_near", "neg_near")], [1, 1, 1, 1])
        _, chosen, rejected = pairs["neg_city"][0]
        self.assertEqual((chosen, rejected), (REFUSAL, "Fondata dai Greci."))
        _, chosen, rejected = pairs["pos_city"][0]
        self.assertEqual((chosen, rejected), ("Meze ovunque.", REFUSAL))
        _, chosen, rejected = pairs["pos_near"][0]
        self.assertEqual((chosen, rejected), ("Farmacia Kostas", "Nel contesto non ci sono ristoranti."))

    def test_prompt_is_only_the_user_turn(self):
        prompt, _, _ = build_pairs(ROWS, set(), {})["pos_city"][0]
        self.assertEqual([m["role"] for m in prompt], ["user"])

    def test_test_regions_and_extended_regions_excluded(self):
        pairs = build_pairs(ROWS, excluded_regions={"citta:Nicosia"}, per_group={})
        regions = {p[0][0]["content"] for p in pairs["neg_city"]}
        self.assertEqual(len(pairs["neg_city"]), 1)  # Berlino; Nicosia esclusa
        self.assertIn("Muro.", next(iter(regions)))

    def test_cap_per_group(self):
        many = [row("pos", f"citta:C{i}", "CIBO_BEVANDE", "x", f"risposta {i}") for i in range(10)] + [ROWS[0]]
        pairs = build_pairs(many, set(), {"pos_city": 3})
        self.assertEqual(len(pairs["pos_city"]), 3)


class LossTest(unittest.TestCase):
    def test_margin_positive_when_policy_moves_towards_chosen(self):
        self.assertAlmostEqual(dpo_margin(-10.0, -20.0, -12.0, -18.0), 4.0)
        self.assertAlmostEqual(dpo_margin(-12.0, -18.0, -12.0, -18.0), 0.0)

    def test_loss_is_minus_log_sigmoid(self):
        for margin in (-30.0, -2.0, 0.0, 1.5, 40.0):
            expected = -math.log(1 / (1 + math.exp(-0.1 * margin)))
            self.assertAlmostEqual(dpo_loss(margin, 0.1), expected, places=9)
        self.assertAlmostEqual(dpo_loss(0.0, 0.1), math.log(2))

    def test_loss_stable_for_large_margins(self):
        self.assertAlmostEqual(dpo_loss(1e6, 0.1), 0.0)
        self.assertAlmostEqual(dpo_loss(-1e6, 0.1), 1e5)


if __name__ == "__main__":
    unittest.main()
