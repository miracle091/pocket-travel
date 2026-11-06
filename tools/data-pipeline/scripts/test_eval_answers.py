#!/usr/bin/env python3
"""Test di eval_answers.py: risposte salvate unite alle righe del test per tipo, regione e domanda; senza --test,
uso delle sezioni del contesto nelle risposte di eval_gguf.py --contexts.

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

from eval_answers import checked_rows, main
from eval_common import on_device_prompt


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

    def test_risposta_senza_riga_del_test_ferma_lo_script(self):
        with self.assertRaises(SystemExit):
            checked_rows([answer("pos_para", "Altra domanda?", "x")], [test_row("pos_para", "Contesto.", "Quando apre?")])


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
