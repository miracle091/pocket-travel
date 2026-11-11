#!/usr/bin/env python3
"""Test di eval_answers.py: risposte salvate unite alle righe del test per tipo, regione e domanda; senza --test,
uso delle sezioni del contesto nelle risposte di eval_gguf.py --contexts; con --oracle, le risposte attese del test
passate dai controlli.

Uso: python test_eval_answers.py
"""
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from eval_answers import checked_rows, main, oracle_failures
from eval_common import REFUSAL, on_device_prompt


def test_row(kind, context, question, region="canada"):
    return {"kind": kind, "region": region, "messages": [{"role": "user", "content": on_device_prompt(context, question, "it")},
                                                         {"role": "assistant", "content": "A"}]}


def answer(kind, question, got, region="canada"):
    return {"kind": kind, "region": region, "question": question, "got": got}


class CheckedRowsTest(unittest.TestCase):
    def test_contesto_dalla_riga_con_la_stessa_chiave_nell_ordine(self):
        test = [test_row("pos_para", "Il museo apre alle 9.", "Quando apre?"),
                test_row("pos_para", "Il parco apre alle 8.", "Quando apre?"),
                test_row("pos_para", "Il museo apre alle 9.", "Quando apre?", region="italia")]
        rows = checked_rows([answer("pos_para", "Quando apre? ", "Il museo apre alle 9."),
                             answer("pos_para", "Quando apre?", "Il museo apre alle 9.")], test)
        self.assertEqual(["literal", "out"], [r["fidelity"] for r in rows])  # la seconda risposta va con il contesto del parco
        self.assertEqual("it", rows[0]["question_lang"])

    def test_out_riscrive_le_risposte_con_i_controlli_ricalcolati(self):
        # un file salvato con il giudizio di prima: una frase copiata dalla guida letta come rifiuto
        got = "Il museo non indica i prezzi."
        old = {**answer("pos_para", "Quando apre?", got), "category": "COSA_VEDERE", "want": got, "refused": True}
        with tempfile.TemporaryDirectory() as tmp:
            answers, test, out = Path(tmp, "risposte.jsonl"), Path(tmp, "test.jsonl"), Path(tmp, "nuove.jsonl")
            answers.write_text(json.dumps(old) + "\n", encoding="utf-8")
            test.write_text(json.dumps(test_row("pos_para", got, "Quando apre?")) + "\n", encoding="utf-8")
            argv = ["eval_answers.py", str(answers), "--test", str(test), "--out", str(out)]
            with mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()):
                main()
            [row] = [json.loads(line) for line in out.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(("COSA_VEDERE", got, False, "literal"), (row["category"], row["want"], row["refused"], row["fidelity"]))

    def test_risposta_senza_riga_del_test_ferma_lo_script(self):
        with self.assertRaises(SystemExit):
            checked_rows([answer("pos_para", "Altra domanda?", "x")], [test_row("pos_para", "Contesto.", "Quando apre?")])


def oracle_row(kind, context, want, region="canada"):
    return {"kind": kind, "region": region, "messages": [{"role": "user", "content": on_device_prompt(context, "Quando apre?", "it")},
                                                         {"role": "assistant", "content": want}]}


class OracleTest(unittest.TestCase):
    def test_risposte_attese_giuste_passano_e_le_altre_con_la_causa(self):
        context = "Il museo apre alle 9. Ingresso gratuito."
        good = [oracle_row("pos_para", context, "Il museo apre alle 9."),
                oracle_row("neg_city", context, REFUSAL + " sugli alberghi: consulta una fonte ufficiale.")]
        bad = [oracle_row("pos_para", context, REFUSAL + " sul museo: altrove."),
               oracle_row("pos_para", context, "Il contesto non menziona l'orario del museo."),
               oracle_row("neg_city", context, "Il museo apre alle 9."),
               oracle_row("pos_para", context, "Il Colosseo e il Pantheon sono bellissimi."),
               oracle_row("pos_para", context, "Il museo apre alle 9. " * 21),
               oracle_row("pos_para", "The museum opens at 9.", "The museum opens at 9."),
               oracle_row("pos_para", context, "<think>\n\n</think>\n\nIl museo apre alle 9.")]
        failures = oracle_failures(good + bad)
        self.assertEqual(bad, [row for row, _ in failures])
        self.assertEqual([["positivo con la frase di rifiuto"], ["positivo letto come rifiuto con parole proprie"],
                          ["negativo senza la frase di rifiuto"], ["fuori dal contesto"], ["oltre 450 caratteri"],
                          ["lingua sbagliata"], ["<think> iniziale"]], [causes for _, causes in failures])

    def test_del_dataset_solo_le_regioni_di_test_del_test_esteso_tutte(self):
        rows = [oracle_row("pos", "Il museo apre alle 9.", "Altro.", region="canada"),
                oracle_row("pos", "Il museo apre alle 9.", "Altro.", region="italia")]
        with tempfile.TemporaryDirectory() as tmp:
            out = {}
            for name in ("pocket_travel_sft.v11.it.jsonl", "eval_extended.jsonl"):
                path = Path(tmp) / name
                path.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
                buf = io.StringIO()
                with mock.patch.object(sys, "argv", ["eval_answers.py", "--oracle", str(path)]), contextlib.redirect_stdout(buf):
                    main()
                out[name] = buf.getvalue()
        self.assertIn("oracolo: 1 righe su 1 ", out["pocket_travel_sft.v11.it.jsonl"])
        self.assertIn("oracolo: 2 righe su 2 ", out["eval_extended.jsonl"])
        self.assertIn("fuori dal contesto: 2 (pos 2)\n  pos canada | Quando apre? | Altro.\n", out["eval_extended.jsonl"])


class ContextUseModeTest(unittest.TestCase):
    def run_main(self, rows, *args):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "risposte.jsonl"
            path.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
            out = io.StringIO()
            with mock.patch.object(sys, "argv", ["eval_answers.py", str(path), *args]), contextlib.redirect_stdout(out):
                main()
            return out.getvalue()

    def test_senza_test_uso_delle_parti_rispetto_alla_ricerca_scelta(self):
        part = {"general_guide": False, "expected": True, "text": "Ingresso: visto elettronico obbligatorio."}
        row = {"lang": "it", "series": "direct", "region": "canada", "question": "Serve il visto?", "refused": False,
               "got": "Serve il visto elettronico obbligatorio.", "parts": [part]}
        out = self.run_main([{**row, "search": "a", "context": "x"}, {**row, "search": "b", "context": "y"}], "--baseline", "b")
        self.assertIn("it direct a: 1 | 1 | 0", out)
        self.assertIn("rispetto a b", out)
        self.assertIn("it direct a: 1 | 0 | 0 | 0 | 0", out)

    def test_senza_test_e_senza_parti_errore(self):
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            self.run_main([{"kind": "pos_para", "question": "x", "got": "y"}])


if __name__ == "__main__":
    unittest.main()
