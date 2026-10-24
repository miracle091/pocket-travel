#!/usr/bin/env python3
"""Test di eval_answers.py: risposte salvate unite alle righe del test per tipo, regione e domanda.

Uso: python test_eval_answers.py
"""
import unittest

from eval_answers import checked_rows
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


if __name__ == "__main__":
    unittest.main()
