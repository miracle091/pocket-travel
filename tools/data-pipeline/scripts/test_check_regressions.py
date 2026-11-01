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

from check_regressions import answer_regressions, bench_regressions, only_variant

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
        regressions = answer_regressions(rows(95, 90), rows(95, 80), 3.0)[1]
        self.assertEqual(2, len(regressions))
        self.assertTrue(regressions[0].startswith("tutti i pos: 90.0% -> 80.0%"))
        self.assertTrue(regressions[1].startswith("pos_para: peggio in 10 righe"))

    def test_un_miglioramento_non_e_una_regressione(self):
        self.assertEqual([], answer_regressions(rows(80, 80), rows(99, 99), 3.0)[1])

    def test_un_tipo_in_regressione_significativa_anche_sotto_la_soglia_complessiva(self):
        base = rows(100, 100, n=100) + [{"kind": "neg_other", "region": "r", "question": f"o{i}", "refused": True} for i in range(300)]
        new = rows(100, 100, n=100) + [{"kind": "neg_other", "region": "r", "question": f"o{i}", "refused": i >= 12} for i in range(300)]
        lines, regressions = answer_regressions(base, new, 3.0)
        self.assertEqual(["neg_other: peggio in 12 righe, meglio in 0 (p 0.000)"], regressions)

    def test_poche_righe_appaiate_sono_un_errore(self):
        other = [dict(r, question="altra " + r["question"]) for r in rows(95, 90)]
        regressions = answer_regressions(rows(95, 90), other, 3.0)[1]
        self.assertTrue(any("righe appaiate" in r for r in regressions))
        self.assertEqual([], [r for r in answer_regressions(rows(95, 90), rows(95, 90), 3.0)[1] if "appaiate" in r])

    def test_solo_la_variante_scelta(self):
        both = [dict(r, variant="rag") for r in rows(95, 90)] + [dict(r, variant="norag") for r in rows(50, 50)]
        self.assertEqual(rows(95, 90), [{k: v for k, v in r.items() if k != "variant"} for r in only_variant(both, "rag")])
        self.assertEqual(rows(95, 90), only_variant(rows(95, 90), "rag"))


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
