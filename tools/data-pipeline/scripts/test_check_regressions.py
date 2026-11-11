#!/usr/bin/env python3
"""Test di check_regressions.py: soglie sulle risposte giuste, sui controlli sulle istruzioni e su tg, correzione di
Holm, benchmark non confrontabili, codice d'uscita con risposte finte (rifiuta sempre, non rifiuta mai, troncate,
lingua sbagliata).

Uso: python test_check_regressions.py
"""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from check_regressions import answer_regressions, bench_regressions, instruction_regressions, only_variant
from eval_common import REFUSAL, instruction_checks, on_device_prompt

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
        # p = 2 / 2^12 = 0,00049; con Holm su 3 tipi (neg_city, pos_para, neg_other) 0,0015
        self.assertEqual(["neg_other: peggio in 12 righe, meglio in 0 (p 0.000, con Holm 0.001)"], regressions)

    def test_un_tipo_significativo_solo_senza_correzione_non_e_una_regressione(self):
        # neg_other 6-0: p = 1/32 = 0,031 < 0,05, ma con Holm su 3 tipi 0,094; i neg nel totale calano di 2 punti
        base = rows(300, 300, n=300) + [{"kind": "neg_other", "region": "r", "question": f"o{i}", "refused": True} for i in range(6)]
        new = rows(300, 300, n=300) + [{"kind": "neg_other", "region": "r", "question": f"o{i}", "refused": False} for i in range(6)]
        self.assertEqual([], answer_regressions(base, new, 3.0)[1])

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


def run_script(base, new):
    with tempfile.TemporaryDirectory() as d:
        paths = []
        for name, data in (("base.jsonl", base), ("new.jsonl", new)):
            p = Path(d) / name
            p.write_text("".join(json.dumps(r) + "\n" for r in data), encoding="utf-8")
            paths.append(str(p))
        return subprocess.run([sys.executable, str(HERE / "check_regressions.py"), *paths],
                              capture_output=True, text=True, encoding="utf-8")


class ExitCodeTest(unittest.TestCase):
    def test_uscita_1_con_una_regressione(self):
        result = run_script(rows(95, 90), rows(85, 90))
        self.assertEqual(1, result.returncode)
        self.assertIn("REGRESSIONE", result.stdout)
        self.assertEqual(0, run_script(rows(95, 90), rows(95, 90)).returncode)


CONTEXT = "Il Museo Reale si trova in centro. L'ingresso costa 12 euro. Il treno per l'aeroporto parte ogni 20 minuti."
WANT = {"pos_para": "Il Museo Reale si trova in centro e l'ingresso costa 12 euro.",
        "neg_city": REFUSAL + " sugli alberghi: per dettagli affidabili consulta una fonte ufficiale."}


def answer_file(got_of, n=40):
    """Risposte come le scrive eval_gguf.py --answers (campi di instruction_checks) a n righe pos_para e n neg_city;
    got_of(tipo, risposta attesa) da' la risposta."""
    out = []
    for kind, want in WANT.items():
        for i in range(n):
            question = f"Domanda {i}?"
            row = {"kind": kind, "region": "canada", "messages": [
                {"role": "user", "content": on_device_prompt(CONTEXT, question, "it")}, {"role": "assistant", "content": want}]}
            got = got_of(kind, want)
            out.append({"kind": kind, "region": "canada", "question": question, "want": want, "got": got,
                        **instruction_checks(row, got)})
    return out


class MutationTest(unittest.TestCase):
    """Risposte finte che check_regressions.py deve riconoscere come regressione rispetto alle risposte attese."""

    def test_le_risposte_attese_non_sono_una_regressione(self):
        self.assertEqual(0, run_script(answer_file(lambda kind, want: want), answer_file(lambda kind, want: want)).returncode)

    def test_risposte_finte_escono_con_1(self):
        mutations = {
            "rifiuta sempre": lambda kind, want: WANT["neg_city"],
            "non rifiuta mai": lambda kind, want: "Il treno per l'aeroporto parte ogni 20 minuti.",
            # al limite di token (256, oltre i 450 caratteri delle risposte del dataset) la risposta e' troncata
            "troncate al limite di token": lambda kind, want: (want + " ") * 20,
            "lingua sbagliata": lambda kind, want: (
                "The Royal Museum is in the centre and the ticket costs 12 euros." if kind == "pos_para"
                else "The context does not contain information about hotels: check an official source."),
        }
        good = answer_file(lambda kind, want: want)
        for name, got_of in mutations.items():
            with self.subTest(name):
                result = run_script(good, answer_file(got_of))
                self.assertEqual(1, result.returncode, result.stdout)
                self.assertIn("REGRESSIONE", result.stdout)

    def test_controlli_sulle_istruzioni(self):
        good = answer_file(lambda kind, want: want)
        english = answer_file(lambda kind, want: "The museum is in the centre." if kind == "pos_para" else want)
        lines, regressions = instruction_regressions(good, english, 3.0)
        # le parole inglesi non sono nel contesto italiano: anche fuori dal contesto
        self.assertEqual(["fuori dal contesto: 0.0% -> 50.0% delle righe", "lingua sbagliata: 0.0% -> 50.0% delle righe"],
                         regressions)
        self.assertIn("oltre 450 caratteri: 0.0% -> 0.0% delle righe", lines)
        self.assertEqual(([], []), instruction_regressions([], english, 3.0))


if __name__ == "__main__":
    unittest.main()
