#!/usr/bin/env python3
"""Test di render_cards.py: card nel repository uguali a quelle generate, fascia di RAM come l'app, blocchi di lingua,
lettura dei log del test esteso.

Uso: python test_render_cards.py
"""
import contextlib
import io
import tempfile
import unittest
from pathlib import Path

import render_cards


def results(n=10, rate=50.0):
    return {kind: [n, rate] for kind in render_cards.GROUPS}


def log_text(groups):
    return "\n".join(f"tipo {kind}: {n} righe, rifiuta {rate}%" for kind, (n, rate) in groups.items()) + "\n"


class RepositoryCards(unittest.TestCase):
    def test_cards_match_template_and_data(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = render_cards.main(["--check"])
        self.assertEqual(code, 0, stderr.getvalue())

    def test_no_placeholder_or_block_left(self):
        for path, text in render_cards.render_all().items():
            self.assertNotIn("{{", text, path.name)
            self.assertNotIn("<!--", text, path.name)


class RamTier(unittest.TestCase):
    def test_tiers_of_the_app(self):
        rule = render_cards.app_ram_rule()
        # Stesse fasce di GgufRamEstimateTest per le stime dei modelli del catalogo.
        self.assertEqual(render_cards.ram_tier(609, rule), 4)
        self.assertEqual(render_cards.ram_tier(1_418, rule), 6)
        self.assertEqual(render_cards.ram_tier(3_095, rule), 8)
        self.assertEqual(render_cards.ram_tier(20_000, rule), 12)

    def test_rule_read_from_kotlin(self):
        app, free, tiers = render_cards.app_ram_rule()
        self.assertGreater(app, 0)
        self.assertGreater(free, 0)
        self.assertEqual(tiers, sorted(tiers))


class LanguageBlocks(unittest.TestCase):
    TEXT = "a\n<!-- it -->\nitaliano\n<!-- /it -->\n<!-- en -->\nenglish\n<!-- /en -->\nb"

    def test_keeps_only_the_language(self):
        self.assertEqual(render_cards.keep_language(self.TEXT, "it"), "a\nitaliano\nb")
        self.assertEqual(render_cards.keep_language(self.TEXT, "en"), "a\nenglish\nb")

    def test_unclosed_block_stops(self):
        with self.assertRaises(SystemExit):
            render_cards.keep_language("a\n<!-- it -->\nb", "it")

    def test_nested_block_stops(self):
        with self.assertRaises(SystemExit):
            render_cards.keep_language("<!-- it -->\n<!-- en -->\nx\n<!-- /en -->\n<!-- /it -->", "it")


class Results(unittest.TestCase):
    def test_reads_utf16_log(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "eval.log"
            log.write_bytes(log_text(results(44, 97.7)).encode("utf-16"))
            self.assertEqual(render_cards.read_results(log), results(44, 97.7))

    def test_missing_group_stops(self):
        groups = results()
        del groups["pos_dep"]
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "eval.log"
            log.write_text(log_text(groups), encoding="utf-8")
            with self.assertRaises(SystemExit):
                render_cards.read_results(log)

    def test_table_needs_same_questions(self):
        base = results()
        base["neg_off"] = [11, 50.0]
        model = {"repo": "x", "base_model": "Qwen/X", "results": {"model": results(), "base": base}}
        with self.assertRaises(SystemExit):
            render_cards.results_table(model)

    def test_files_table_weights_groups_by_questions(self):
        old = results(10, 100.0)
        old["neg_off"] = [30, 0.0]  # 6 gruppi da 10 al 100% e uno da 30 allo 0%: 60/90 rifiuti
        model = {"file": "m-Q5_K_M.gguf", "file_mib": 1000, "memory_mib": 600, "results": {"model": results(10, 0.0)},
                 "outdated_files": [{"file": "m-Q4_K_M.gguf", "quantization": "Q4_K_M", "kept_for": "1.0",
                                     "file_mib": 900, "memory_mib": 3500, "results": old}]}
        table = render_cards.files_table(model, render_cards.app_ram_rule())
        self.assertIn("| `m-Q5_K_M.gguf` | **current** | Q5_K_M with importance matrix | 1,000 MiB | 600 MiB | "
                      "4 GB or more | 0% | 0% |", table)
        self.assertIn("| `m-Q4_K_M.gguf` | outdated | Q4_K_M | 900 MiB | 3,500 MiB | 8 GB or more | "
                      "66.7% | 100% |", table)
        self.assertIn("`m-Q4_K_M.gguf` is the previous version", render_cards.outdated_note(model))
        del model["outdated_files"]
        self.assertEqual(render_cards.outdated_note(model), "")

    def test_quant_reason_measured_and_range(self):
        def model(q4, q5, mib):
            return {"file_mib": mib, "outdated_files": [{"file_mib": 100}], "quant_kl": {"Q4_K_M": q4, "Q5_K_M": q5}}
        data = {"models": [model(0.04, 0.01, 105), model(0.05, 0.02, 107), {"file_mib": 300}]}
        measured = render_cards.quant_reason(data["models"][0], data)
        self.assertIn("drops from 0.040 to\n0.010 (75% less), for a file 5% larger", measured)
        self.assertIn("by 60-75% on held-out guide text, for files 5-7% larger",
                      render_cards.quant_reason(data["models"][2], data))

    def test_table_rows_and_total(self):
        model = {"repo": "x", "base_model": "Qwen/X", "results": {"model": results(10, 97.5), "base": results(10, 0.0)}}
        table, questions = render_cards.results_table(model)
        self.assertEqual(questions, 10 * len(render_cards.GROUPS))
        self.assertIn("| Off-topic questions (10) | 97.5% | 0% |", table)
        self.assertIn("| Base model (X) |", table)


if __name__ == "__main__":
    unittest.main()
