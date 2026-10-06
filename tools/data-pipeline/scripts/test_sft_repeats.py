#!/usr/bin/env python3
"""Test di cap_repeats (generate_sft.py): al massimo SAME_ANSWER_MAX righe positive con la stessa domanda e la stessa
risposta (cambia solo il contesto); i rifiuti non si toccano.

Uso: python test_sft_repeats.py
"""
import unittest

import generate_sft


def make(lang, context, q, ans, kind="pos"):
    L = generate_sft.LANGS[lang]
    return {"messages": [{"role": "user", "content": L["prompt"](context, q)}, {"role": "assistant", "content": ans}],
            "kind": kind, "region": "francia", "category": "CIBO_BEVANDE", "translated": False}


class CapRepeatsTest(unittest.TestCase):
    def test_al_massimo_due_per_domanda_e_risposta(self):
        for lang in ("it", "en"):
            rows = [make(lang, f"Contesto {i}.", "Cosa si mangia?", "Pasta.") for i in range(5)]
            rows += [make(lang, "Contesto 9.", "Cosa si beve?", "Vino."), make(lang, "Contesto 8.", "Cosa si mangia?", "Pizza.")]
            kept = generate_sft.cap_repeats(rows, generate_sft.LANGS[lang]["prompt"])
            self.assertEqual(kept, rows[:2] + rows[5:])

    def test_rifiuti_invariati(self):
        rows = [make("it", f"Contesto {i}.", "Cosa si mangia?", "Il contesto non lo dice.", "neg") for i in range(4)]
        self.assertEqual(generate_sft.cap_repeats(rows, generate_sft.LANGS["it"]["prompt"]), rows)

    def test_domanda_dal_prompt(self):
        prompt = generate_sft.LANGS["it"]["prompt"]
        self.assertEqual(generate_sft.question_of(prompt("Riga uno.\n\nRiga due.", "Dove dormo?"), prompt), "Dove dormo?")


if __name__ == "__main__":
    unittest.main()
