#!/usr/bin/env python3
"""Test delle parti di eval_common.py usate da eval_gguf.py --contexts: il prompt come PromptTemplates.kt, il
riconoscimento dei rifiuti e il conteggio dei rifiuti con e senza la sezione attesa nel contesto.

Uso: python test_eval_common.py
"""
import re
import unittest
from pathlib import Path

from eval_common import context_report, is_refusal, on_device_prompt

PROMPT_TEMPLATES = (Path(__file__).resolve().parents[3]
                    / "feature/ai/src/main/kotlin/com/pockettravel/feature/ai/PromptTemplates.kt").read_text(encoding="utf-8")


def kotlin_prompt(branch, context, question):
    """Il testo di onDevicePrompt in PromptTemplates.kt per un ramo ("en" o "it"), con contesto e domanda."""
    body = PROMPT_TEMPLATES.split("fun onDevicePrompt(")[1].split('if (language == "en") {')[1].split("fun emptyContext(")[0]
    en, it = body.split("} else {")
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', en if branch == "en" else it)
    return "".join(literals).replace("\\n", "\n").replace("$context", context).replace("$question", question)


class PromptTest(unittest.TestCase):
    def test_prompt_come_l_app(self):
        for lang in ("it", "en"):
            self.assertEqual(on_device_prompt("Il treno arriva.", "Come arrivo?", lang),
                             kotlin_prompt(lang, "Il treno arriva.", "Come arrivo?"))

    def test_contesto_vuoto_come_l_app(self):
        en, it = re.search(r'emptyContext.*?"en"\) "([^"]*)" else "([^"]*)"', PROMPT_TEMPLATES, re.S).groups()
        self.assertEqual(on_device_prompt("", "Q?", "en"), kotlin_prompt("en", en, "Q?"))
        self.assertEqual(on_device_prompt("  ", "D?", "it"), kotlin_prompt("it", it, "D?"))


class RefusalTest(unittest.TestCase):
    def test_rifiuto_per_lingua(self):
        self.assertTrue(is_refusal("Il contesto non contiene informazioni sugli alberghi.", "it"))
        self.assertTrue(is_refusal("The context does not contain information about hotels.", "en"))
        self.assertFalse(is_refusal("A Rimini ci sono alberghi sul lungomare.", "it"))
        self.assertFalse(is_refusal("Il contesto non contiene informazioni sugli alberghi.", "en"))

    def test_rifiuti_con_e_senza_la_sezione(self):
        row = lambda search, section, refused: {"lang": "it", "series": "direct", "search": search,
                                                "section_in_context": section, "refused": refused}
        rows = [row("word-lists", True, False), row("word-lists", True, True), row("word-lists", False, True),
                row("word-lists", False, False), row("before-rag", False, True)]
        self.assertEqual(context_report(rows), {("it", "direct", "word-lists"): (4, 2, 1, 1),
                                                ("it", "direct", "before-rag"): (1, 0, 0, 1)})


if __name__ == "__main__":
    unittest.main()
