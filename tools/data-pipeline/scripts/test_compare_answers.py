#!/usr/bin/env python3
"""Test di compare_answers.py: intervalli di Wilson, McNemar esatto, righe appaiate e giuste per tipo.

Uso: python test_compare_answers.py
"""
import unittest

import compare_answers as ca


def row(kind, question, refused, **extra):
    return {"kind": kind, "region": "germania", "category": "TRASPORTI", "question": question, "refused": refused, **extra}


class StatsTest(unittest.TestCase):
    def test_wilson_come_nel_piano(self):
        # valutazione-modelli.md: neg_city 17/20 = 85% -> 64-95%; pos_near 4/14 -> 12-55%
        lo, hi = ca.wilson(17, 20)
        self.assertEqual((round(100 * lo), round(100 * hi)), (64, 95))
        lo, hi = ca.wilson(4, 14)
        self.assertEqual((round(100 * lo), round(100 * hi)), (12, 55))
        self.assertEqual(ca.wilson(0, 0), (0.0, 1.0))

    def test_mcnemar_esatto_come_nel_piano(self):
        # 5-0 da' p = 0,062; 6-1 da' 0,125; nessuna riga cambiata: 1
        self.assertAlmostEqual(ca.mcnemar(5, 0), 0.0625, places=4)
        self.assertAlmostEqual(ca.mcnemar(6, 1), 0.125, places=3)
        self.assertEqual(ca.mcnemar(0, 0), 1.0)
        self.assertLess(ca.mcnemar(6, 0), 0.05)


class CompareTest(unittest.TestCase):
    def test_righe_giuste_e_appaiate(self):
        a = [row("neg_city", "q1", True), row("neg_city", "q2", False), row("pos_para", "q3", True), row("pos_para", "q4", False)]
        b = [row("neg_city", "q1", True), row("neg_city", "q2", True), row("pos_para", "q3", False), row("pos_para", "q4", False),
             row("pos_para", "solo in B", False)]
        r = ca.compare(a, b)
        self.assertEqual(r["neg_city"]["a"], (1, 2))
        self.assertEqual(r["neg_city"]["b"], (2, 2))
        self.assertEqual((r["neg_city"]["worse"], r["neg_city"]["better"]), (0, 1))
        self.assertEqual(r["pos_para"]["b"], (3, 3))
        self.assertEqual(r["pos_para"]["paired"], 2)
        self.assertEqual(r["tutti i pos"]["better"], 1)

    def test_domande_ripetute_restano_distinte(self):
        a = [row("neg_off", "ciao", True), row("neg_off", "ciao", False)]
        b = [row("neg_off", "ciao", False), row("neg_off", "ciao", False)]
        r = ca.compare(a, b)
        self.assertEqual(r["neg_off"]["paired"], 2)
        self.assertEqual((r["neg_off"]["worse"], r["neg_off"]["better"]), (1, 0))

    def test_report_con_controlli_sulle_istruzioni(self):
        a = [row("pos_para", "q", False, fidelity="out", too_long=True)]
        b = [row("pos_para", "q", False, fidelity="literal", too_long=False)]
        text = ca.report(a, b, ["v10", "v11"])
        self.assertIn("| pos_para | 100.0% (21-100) | 100.0% (21-100) | 1 | 0 / 0 | 1.000 |", text)
        self.assertIn("fuori dal contesto 1 -> 0", text)


if __name__ == "__main__":
    unittest.main()
