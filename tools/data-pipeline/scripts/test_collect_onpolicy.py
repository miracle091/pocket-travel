#!/usr/bin/env python3
"""Test di collect_onpolicy.py: gruppi, scelta delle righe per quota, etichette delle risposte.

Uso: python test_collect_onpolicy.py
"""
import unittest

from collect_onpolicy import group_of, label, pick_rows
from eval_common import REFUSAL


def row(kind, region, category, context, answer, question="Cosa c'e'?"):
    content = ("Sei una guida turistica offline.\nRispondi in massimo 3 frasi, in italiano, usando solo le informazioni "
               f"nel CONTESTO.\n\nCONTESTO: {context}\n\nDOMANDA: {question}")
    return {"messages": [{"role": "user", "content": content}, {"role": "assistant", "content": answer}],
            "kind": kind, "region": region, "category": category}


CONTEXT = "▸ Cibo\nA Nicosia si mangiano i meze nelle taverne del centro. Il caffe' e' forte."
POS = row("pos", "citta:Nicosia", "CIBO_BEVANDE", CONTEXT, "A Nicosia si mangiano i meze nelle taverne del centro.")
NEG = row("neg", "cipro", "SALUTE", CONTEXT, f"{REFUSAL} sulla salute: chiedi in farmacia.")
NEAR = row("pos", "cipro", "VICINO", "Punti di interesse entro 600 m:\n• Farmacia Kostas (120 m)",
           "Entro 600 m dalla tua posizione c'e' Farmacia Kostas (120 m).")


class GroupTest(unittest.TestCase):
    def test_groups_like_the_extended_test(self):
        self.assertEqual("pos_city", group_of(POS))
        self.assertEqual("neg", group_of(NEG))
        self.assertEqual("pos_near", group_of(NEAR))
        self.assertEqual("neg_dep", group_of(row("neg", "citta:Roma", "PARTENZE", "x", "y")))

    def test_quota_fraction_and_cap_and_excluded_regions(self):
        rows = [row("pos", f"r{i}", "VICINO", "x", "y") for i in range(10)]
        rows += [row("neg", f"r{i}", "SALUTE", "x", "y") for i in range(10)]
        rows += [row("neg", "germania", "SALUTE", "x", "y"), row("neg", "esclusa", "SALUTE", "x", "y")]
        picked = pick_rows(rows, {"esclusa"}, {"pos_near": 0.5, "neg": 3})
        self.assertEqual(5, sum(group_of(r) == "pos_near" for r in picked))
        self.assertEqual(3, sum(group_of(r) == "neg" for r in picked))
        self.assertFalse({"germania", "esclusa"} & {r["region"] for r in picked})
        self.assertEqual(picked, pick_rows(rows, {"esclusa"}, {"pos_near": 0.5, "neg": 3}))
        self.assertEqual([], pick_rows(rows, set(), {}))


class LabelTest(unittest.TestCase):
    def test_pos(self):
        self.assertEqual("good", label(POS, "A Nicosia si mangiano i meze nelle taverne del centro."))
        self.assertEqual("good", label(POS, "Il caffe' e' forte."))  # frase del contesto, inizio diverso
        self.assertEqual("bad", label(POS, f"{REFUSAL} sul cibo."))
        self.assertEqual("bad", label(POS, "Il contesto non menziona il cibo."))  # rifiuto con parole proprie
        self.assertEqual("unsure", label(POS, "Si mangia bene ovunque in citta'."))
        self.assertEqual("unsure", label(POS, "The food is great and the coffee is strong."))

    def test_near_accepts_adherent_template_sentences(self):
        self.assertEqual("good", label(NEAR, "Vicino a te c'e' Farmacia Kostas (120 m)."))
        self.assertEqual("unsure", label(NEAR, "Vicino a te c'e' Farmacia Zorba (300 m)."))

    def test_neg(self):
        self.assertEqual("good", label(NEG, f"{REFUSAL} sulla salute: prova altrove."))
        self.assertEqual("unsure", label(NEG, f"{REFUSAL} sul cibo: prova altrove."))
        self.assertEqual("bad", label(NEG, "A Nicosia si mangiano i meze nelle taverne del centro."))


if __name__ == "__main__":
    unittest.main()
