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

    def test_holm_calcolato_a_mano(self):
        # ordinati: 0,01 x 3 = 0,03; 0,03 x 2 = 0,06; 0,04 x 1 = 0,04, ma non meno del precedente: 0,06
        adjusted = ca.holm({"a": 0.01, "b": 0.04, "c": 0.03})
        self.assertEqual(set(adjusted), {"a", "b", "c"})
        for name, want in (("a", 0.03), ("c", 0.06), ("b", 0.06)):
            self.assertAlmostEqual(adjusted[name], want)
        self.assertEqual(ca.holm({"x": 0.6, "y": 0.5}), {"y": 1.0, "x": 1.0})  # al massimo 1
        self.assertEqual(ca.holm({}), {})


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

    def test_rifiuto_sull_argomento_sbagliato_non_e_giusto(self):
        self.assertTrue(ca.correct(row("neg_city", "q", True, refusal_topic_ok=True)))
        self.assertFalse(ca.correct(row("neg_city", "q", True, refusal_topic_ok=False)))
        # rifiuto con parole proprie (None) o file salvati prima del campo: conta il solo rifiuto
        self.assertTrue(ca.correct(row("neg_city", "q", True, refusal_topic_ok=None)))
        self.assertTrue(ca.correct(row("neg_city", "q", True)))

    def test_domande_ripetute_restano_distinte(self):
        a = [row("neg_off", "ciao", True), row("neg_off", "ciao", False)]
        b = [row("neg_off", "ciao", False), row("neg_off", "ciao", False)]
        r = ca.compare(a, b)
        self.assertEqual(r["neg_off"]["paired"], 2)
        self.assertEqual((r["neg_off"]["worse"], r["neg_off"]["better"]), (1, 0))

    def test_holm_sui_tipi_di_riga_non_sui_totali(self):
        # neg_city e neg_off 6-0 (p 1/32 = 0,03125), pos_para senza cambi (p 1): Holm 0,03125 x 3 = 0,094 per i due neg,
        # 1 per pos_para; il totale dei neg (12-0) resta col p senza correzione
        a = [row(k, f"{k}{i}", True) for k in ("neg_city", "neg_off") for i in range(6)] + [row("pos_para", "p", False)]
        b = [row(k, f"{k}{i}", False) for k in ("neg_city", "neg_off") for i in range(6)] + [row("pos_para", "p", False),
                                                                                             row("pos_wiki", "solo in B", False)]
        r = ca.compare(a, b)
        self.assertAlmostEqual(r["neg_city"]["p"], 0.03125)
        self.assertAlmostEqual(r["neg_city"]["p_holm"], 0.09375)
        self.assertAlmostEqual(r["neg_off"]["p_holm"], 0.09375)
        self.assertEqual(r["pos_para"]["p_holm"], 1.0)
        self.assertIsNone(r["pos_wiki"]["p_holm"])  # nessuna riga appaiata: fuori dalla famiglia
        self.assertIsNone(r["tutti i neg"]["p_holm"])
        text = ca.report(a, b, ["A", "B"])
        self.assertIn("| neg_city | 100.0% (61-100) | 0.0% (0-39) | 6 | 6 / 0 | 0.031 | 0.094 |", text)
        self.assertIn("| tutti i neg | 100.0% (76-100) | 0.0% (0-24) | 12 | 12 / 0 | 0.000 * | - |", text)
        self.assertIn("| pos_wiki | - (0-100) | 100.0% (21-100) | 0 | 0 / 0 | 1.000 | - |", text)

    def test_report_con_controlli_sulle_istruzioni(self):
        a = [row("pos_para", "q", False, fidelity="out", too_long=True)]
        b = [row("pos_para", "q", False, fidelity="literal", too_long=False)]
        text = ca.report(a, b, ["v10", "v11"])
        self.assertIn("| pos_para | 100.0% (21-100) | 100.0% (21-100) | 1 | 0 / 0 | 1.000 | 1.000 |", text)
        self.assertIn("fuori dal contesto 1 -> 0", text)


if __name__ == "__main__":
    unittest.main()
