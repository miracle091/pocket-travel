#!/usr/bin/env python3
"""Test di eval_reasoning.py: prompt con il thinking spento e acceso, tabella del rapporto.

Uso: python test_eval_reasoning.py
"""
import unittest

from eval_common import EMPTY_THINK
from eval_reasoning import prompt, report

ROW = {"context": "Linea 15: 06:51, 07:01", "question": "A che ora passa la prossima?"}


def done(mode, kind, ok, tokens, truncated=False):
    return {"mode": mode, "kind": kind, "ok": ok, "tokens": tokens, "truncated": truncated, "seconds": 2.0}


class PromptTest(unittest.TestCase):
    def test_contesto_e_domanda_nel_turno_utente(self):
        text = prompt(ROW, "off")
        self.assertIn("<|im_start|>user\nLinea 15: 06:51, 07:01\n\nA che ora passa la prossima?<|im_end|>", text)

    def test_spento_blocco_vuoto_acceso_think_aperto(self):
        self.assertTrue(prompt(ROW, "off").endswith("<|im_start|>assistant\n" + EMPTY_THINK))
        self.assertTrue(prompt(ROW, "on").endswith("<|im_start|>assistant\n<think>\n"))


class ReportTest(unittest.TestCase):
    def test_una_riga_per_modalita(self):
        rows = [done("off", "next", True, 20), done("off", "count", False, 40),
                done("on", "next", True, 300), done("on", "count", True, 900, truncated=True)]
        lines = report(rows).splitlines()
        self.assertEqual(4, len(lines))
        self.assertEqual("| off | 1/1 | 0/0 | 0/0 | 0/0 | 0/1 | 50.0% (1/2) | 30 | 60 | 0 | 2.0 |", lines[2])
        self.assertEqual("| on | 1/1 | 0/0 | 0/0 | 0/0 | 1/1 | 100.0% (2/2) | 600 | 600 | 1 | 2.0 |", lines[3])

    def test_nessuna_giusta(self):
        self.assertIn("| 0.0% (0/1) | 10 | - |", report([done("off", "next", False, 10)]))


if __name__ == "__main__":
    unittest.main()
