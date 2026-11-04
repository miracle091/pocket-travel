#!/usr/bin/env python3
"""Test delle coppie di dpo_lora.py: gruppi, risposte scartate, regioni escluse, tetto per gruppo; esempi, loss e pesi
di KTO.

Uso: python test_dpo_lora.py
"""
import math
import unittest

from dpo_lora import (build_kto_examples, build_pairs, context_of, dpo_loss, dpo_margin, first_sentence, kto_loss,
                      kto_weights, leftover_scale)


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
        self.assertEqual(context_of(ROWS[0]), "▸ Storia\nFondata dai Greci. Poi romana.")

    def test_dataset_inglese_non_scartato(self):
        content = "You are an offline travel guide.\n\nCONTEXT: ▸ History\nFounded by Greeks. Then Roman.\n\nQUESTION: What to eat?"
        en = {"messages": [{"role": "user", "content": content}, {"role": "assistant", "content": "No info."}],
              "kind": "neg", "region": "citta:Nicosia", "category": "CIBO_BEVANDE"}
        self.assertEqual("▸ History\nFounded by Greeks. Then Roman.", context_of(en))
        pairs = build_pairs([en], set(), {})["neg_city"]
        self.assertEqual(("No info.", "Founded by Greeks."), pairs[0][1:])

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


def result(want, *samples):
    """Risultato di collect_onpolicy.py con le risposte (got, label) date."""
    return {"prompt": "Sei una guida turistica offline.\n\nCONTESTO: x\n\nDOMANDA: y", "want": want,
            "samples": [{"seed": i, "got": got, "label": lab} for i, (got, lab) in enumerate(samples)]}


class KtoTest(unittest.TestCase):
    def test_loss_halves_at_z0_and_follows_the_class(self):
        self.assertAlmostEqual(kto_loss(0.0, 0.0, True, 0.1), 0.5)
        self.assertAlmostEqual(kto_loss(3.0, 1.0, False, 0.1), 1 / (1 + math.exp(-0.2)))
        # sopra z0 la desiderata costa meno, l'indesiderata di piu'
        self.assertLess(kto_loss(5.0, 0.0, True, 0.1), 0.5)
        self.assertGreater(kto_loss(5.0, 0.0, False, 0.1), 0.5)
        self.assertAlmostEqual(kto_loss(0.0, 0.0, True, 0.1, desirable_weight=2.0), 1.0)

    def test_loss_stable_for_large_ratios(self):
        self.assertAlmostEqual(kto_loss(1e6, 0.0, True, 0.1), 0.0)
        self.assertAlmostEqual(kto_loss(-1e6, 0.0, True, 0.1), 1.0)

    def test_examples_only_from_prompts_with_errors(self):
        results = [result("Meze.", ("Meze.", "good")),
                   result("Meze ovunque.", (REFUSAL, "bad"), (REFUSAL, "bad"), ("Altro.", "bad"), ("Meze, ovunque.", "good"),
                          ("Dubbio", "unsure"))]
        examples = build_kto_examples(results, max_bad=2)
        self.assertEqual([(c, d) for _, c, d in examples],
                         [(REFUSAL, False), ("Altro.", False), ("Meze ovunque.", True), ("Meze, ovunque.", True)])
        self.assertEqual([m["role"] for m in examples[0][0]], ["user"])
        self.assertEqual(1, len(build_kto_examples(results, max_bad=1)) - 2)

    def test_good_sample_equal_to_want_not_repeated(self):
        examples = build_kto_examples([result("Meze.", (REFUSAL, "bad"), ("Meze.", "good"))])
        self.assertEqual([(c, d) for _, c, d in examples], [(REFUSAL, False), ("Meze.", True)])

    def test_weights_balance_the_classes(self):
        examples = [([], "a", False)] * 3 + [([], "b", True)] * 6
        desirable_weight, undesirable_weight = kto_weights(examples)
        self.assertAlmostEqual(6 * desirable_weight, 3 * undesirable_weight)


class AccumulationTest(unittest.TestCase):
    def test_resto_di_fine_epoca_riscalato_sulle_coppie_presenti(self):
        # 3 coppie con loss/16 sommano 3/16 del gradiente medio: il fattore le porta alla media
        self.assertAlmostEqual(3 / 16 * leftover_scale(3, 16), 1.0)
        self.assertEqual(1.0, leftover_scale(16, 16))


if __name__ == "__main__":
    unittest.main()
