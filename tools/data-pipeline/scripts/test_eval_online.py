#!/usr/bin/env python3
"""Test di eval_online.py: messaggio system uguale a quello dell'app, campione stabile, attese dei 429.

Uso: python test_eval_online.py
"""
import io
import json
import re
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest import mock

import eval_online
from eval_common import on_device_prompt
from eval_online import (CONTEXT_EN, CONTEXT_IT, MAX_ATTEMPTS, SYSTEM_EN, SYSTEM_IT, chat, report_line, retry_seconds,
                         sample, system_prompt)

TEMPLATES = (Path(__file__).resolve().parents[3] / "feature" / "ai" / "src" / "main" / "kotlin" / "com" / "pockettravel"
             / "feature" / "ai" / "OnlinePromptTemplates.kt")


def kotlin_strings():
    """I letterali di OnlinePromptTemplates.kt uniti come li unisce Kotlin (concatenazioni di "..." + "...")."""
    source = TEMPLATES.read_text(encoding="utf-8")
    # Solo gli escape dei letterali (\n, \", \\): unicode_escape rovinerebbe le lettere accentate.
    return [re.sub(r"\\(.)", lambda m: "\n" if m.group(1) == "n" else m.group(1), s)
            for s in re.findall(r'"((?:[^"\\]|\\.)*)"', source)]


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


class Reply:
    """Risposta 200 con il corpo dato, usabile come context manager."""

    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def read(self):
        return json.dumps(self.payload).encode("utf-8")


GOOD = Reply({"choices": [{"message": {"content": "ok"}}]})


def ask():
    return chat("https://x.example/v1", "chiave", "m", None, "sys", "domanda")


class ChatRetryTest(unittest.TestCase):
    def run_chat(self, outcomes):
        with mock.patch.object(eval_online.OPENER, "open", side_effect=outcomes) as opened, \
                mock.patch.object(eval_online.time, "sleep") as slept:
            try:
                return ask(), opened.call_count, slept.call_count
            except SystemExit as e:
                return e, opened.call_count, slept.call_count

    def test_errori_di_rete_si_ritentano(self):
        for failure in (urllib.error.URLError("giu'"), TimeoutError(), ConnectionResetError()):
            with self.subTest(failure=type(failure).__name__):
                self.assertEqual(("ok", 2, 1), self.run_chat([failure, GOOD]))

    def test_risposta_200_senza_choices_si_ritenta(self):
        self.assertEqual(("ok", 3, 2), self.run_chat([Reply({"error": "x"}), Reply({"choices": []}), GOOD]))

    def test_tetto_di_tentativi(self):
        result, calls, _ = self.run_chat([TimeoutError()] * 10)
        self.assertIsInstance(result, SystemExit)
        self.assertEqual(MAX_ATTEMPTS, calls)

    def test_corpo_dell_errore_corto(self):
        with urllib.error.HTTPError("u", 400, "Bad", {}, io.BytesIO(b"x" * 5000)) as error:
            result, calls, _ = self.run_chat([error])
        self.assertEqual(1, calls)
        self.assertLess(len(str(result.code)), 400)

    def test_i_redirect_non_si_seguono(self):
        handler = eval_online.RefuseRedirect()
        request = urllib.request.Request("https://a.example/", headers={"Authorization": "Bearer k"})
        self.assertIsNone(handler.redirect_request(request, None, 302, "Found", {}, "https://altro.example/"))
        self.assertTrue(any(isinstance(h, eval_online.RefuseRedirect) for h in eval_online.OPENER.handlers))


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
