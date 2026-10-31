#!/usr/bin/env python3
"""Test di eval_online.py: messaggio system uguale a quello dell'app, campione stabile, attese dei 429.

Uso: python test_eval_online.py
"""
import re
import unittest
from pathlib import Path

from eval_common import on_device_prompt
from eval_online import CONTEXT_EN, CONTEXT_IT, SYSTEM_EN, SYSTEM_IT, report_line, retry_seconds, sample, system_prompt

TEMPLATES = (Path(__file__).resolve().parents[3] / "feature" / "ai" / "src" / "main" / "kotlin" / "com" / "pockettravel"
             / "feature" / "ai" / "OnlinePromptTemplates.kt")


def kotlin_strings():
    """I letterali di OnlinePromptTemplates.kt uniti come li unisce Kotlin (concatenazioni di "..." + "...")."""
    source = TEMPLATES.read_text(encoding="utf-8")
    return [s.encode().decode("unicode_escape") for s in re.findall(r'"((?:[^"\\]|\\.)*)"', source)]


def row(kind, question, context="Il museo apre alle 9."):
    return {"kind": kind, "region": "canada", "messages": [{"role": "user", "content": on_device_prompt(context, question, "it")},
                                                           {"role": "assistant", "content": "A"}]}


class SystemPromptTest(unittest.TestCase):
    def test_le_istruzioni_sono_quelle_dell_app(self):
        joined = "".join(kotlin_strings())
        for text in (SYSTEM_IT, CONTEXT_IT.rstrip("\n"), SYSTEM_EN, CONTEXT_EN.rstrip("\n")):
            self.assertIn(text, joined)

    def test_senza_contesto_solo_le_istruzioni(self):
        self.assertEqual(SYSTEM_IT, system_prompt("", "it"))
        self.assertEqual(SYSTEM_EN, system_prompt("", "en"))

    def test_con_contesto_un_blocco_dopo_l_istruzione_di_citare(self):
        prompt = system_prompt("Il museo apre alle 9.", "it")
        self.assertTrue(prompt.startswith(SYSTEM_IT + CONTEXT_IT + '<contesto fonte="'))
        self.assertTrue(prompt.endswith("\nIl museo apre alle 9.\n</contesto>"))


class SampleTest(unittest.TestCase):
    def test_passo_fisso_su_tutti_i_tipi(self):
        rows = [row("neg_city", f"n{i}?") for i in range(10)] + [row("pos_para", f"p{i}?") for i in range(10)]
        picked = sample(list(reversed(rows)), 4)
        self.assertEqual(["neg_city", "neg_city", "pos_para", "pos_para"], [r["kind"] for r in picked])
        self.assertEqual(picked, sample(rows, 4))

    def test_senza_limite_tutte(self):
        self.assertEqual(3, len(sample([row("pos_para", f"p{i}?") for i in range(3)], 0)))


class RetryTest(unittest.TestCase):
    def test_retry_after_poi_retry_delay_poi_20_s(self):
        self.assertEqual(7, retry_seconds("", "7"))
        self.assertEqual(17.0, retry_seconds('{"@type":"RetryInfo","retryDelay": "16s"}', None))
        self.assertEqual(20, retry_seconds("{}", None))


class ReportTest(unittest.TestCase):
    def test_riga_del_rapporto(self):
        rows = [{"kind": "neg_city", "correct": True, "refused": True, "fidelity": None, "context_share": None,
                 "too_long": False, "wrong_lang": False},
                {"kind": "pos_para", "correct": True, "refused": False, "fidelity": "literal", "context_share": 1.0,
                 "too_long": True, "wrong_lang": False}]
        self.assertEqual("| rag | 2 | 100.0% (1/1) | 100.0% (1/1) | 100.0% (1/1) | 0.0% (0/1) | 0.0% (0/1) | 1.00 | 1 | 0 |",
                         report_line("rag", rows))


if __name__ == "__main__":
    unittest.main()
