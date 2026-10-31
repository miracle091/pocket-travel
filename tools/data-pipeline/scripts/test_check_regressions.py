#!/usr/bin/env python3
"""Test di check_regressions.py: soglie sulle risposte giuste e su tg, benchmark non confrontabili, codice d'uscita.

Uso: python test_check_regressions.py
"""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from check_regressions import answer_regressions, bench_regressions

HERE = Path(__file__).resolve().parent


def rows(neg_refused, pos_answered, n=100):
    """n righe negative con neg_refused rifiutate e n positive con pos_answered con risposta."""
    out = [{"kind": "neg_city", "region": "r", "question": f"n{i}", "refused": i < neg_refused} for i in range(n)]
    return out + [{"kind": "pos_para", "region": "r", "question": f"p{i}", "refused": i >= pos_answered} for i in range(n)]


def bench(tg, device="cpu", threads=4, unstable=False):
    return {"device": device, "threads": threads, "n_prompt": 512, "n_gen": 128, "tg": {"median_tps": tg},
            "unstable": unstable}


class AnswersTest(unittest.TestCase):
    def test_tre_punti_si_quattro_no(self):
        self.assertEqual([], answer_regressions(rows(95, 90), rows(92, 90), 3.0)[1])
        regressions = answer_regressions(rows(95, 90), rows(91, 90), 3.0)[1]
        self.assertEqual(1, len(regressions))
        self.assertTrue(regressions[0].startswith("tutti i neg: 95.0% -> 91.0%"))

    def test_anche_i_positivi(self):
        self.assertEqual(1, len(answer_regressions(rows(95, 90), rows(95, 80), 3.0)[1]))

    def test_un_miglioramento_non_e_una_regressione(self):
        self.assertEqual([], answer_regressions(rows(80, 80), rows(99, 99), 3.0)[1])


class BenchTest(unittest.TestCase):
    def test_soglia_di_tg(self):
        self.assertEqual([], bench_regressions(bench(60.0), bench(55.0), 10.0)[1])
        self.assertEqual(1, len(bench_regressions(bench(60.0), bench(53.0), 10.0)[1]))

    def test_dispositivi_diversi_non_si_confrontano(self):
        lines, regressions = bench_regressions(bench(60.0), bench(10.0, device="gpu"), 10.0)
        self.assertEqual([], regressions)
        self.assertIn("non confrontabili", lines[0])

    def test_misura_instabile_segnalata(self):
        self.assertIn("instabile", bench_regressions(bench(60.0), bench(59.0, unstable=True), 10.0)[0][0])


class ExitCodeTest(unittest.TestCase):
    def run_script(self, base, new):
        with tempfile.TemporaryDirectory() as d:
            paths = []
            for name, data in (("base.jsonl", base), ("new.jsonl", new)):
                p = Path(d) / name
                p.write_text("".join(json.dumps(r) + "\n" for r in data), encoding="utf-8")
                paths.append(str(p))
            return subprocess.run([sys.executable, str(HERE / "check_regressions.py"), *paths],
                                  capture_output=True, text=True, encoding="utf-8")

    def test_uscita_1_con_una_regressione(self):
        result = self.run_script(rows(95, 90), rows(85, 90))
        self.assertEqual(1, result.returncode)
        self.assertIn("REGRESSIONE", result.stdout)
        self.assertEqual(0, self.run_script(rows(95, 90), rows(95, 90)).returncode)


if __name__ == "__main__":
    unittest.main()
