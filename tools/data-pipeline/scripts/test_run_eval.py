#!/usr/bin/env python3
"""Test di run_eval.py: scelta del generatore del test esteso e sequenza dei lanci di eval_behavior.py, con
subprocess e l'esistenza dei file sostituiti da mock (nessun modello, nessuna GPU).

Uso: python test_run_eval.py
"""
import json
import runpy
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent


def run(argv, extended_exists, dataset=None):
    """Esegue run_eval.py come __main__ con il test esteso presente o assente; restituisce i comandi lanciati."""
    commands = []
    real_exists = Path.exists

    def fake_exists(self):
        if self.name.startswith("eval_extended"):
            return extended_exists
        return real_exists(self)

    with tempfile.TemporaryDirectory() as tmp:
        merged = Path(tmp) / "run" / "merged"
        merged.mkdir(parents=True)
        if dataset:
            (merged.parent / "run.json").write_text(json.dumps({"dataset": dataset}), encoding="utf-8")
        with mock.patch("subprocess.run", side_effect=lambda cmd, check: commands.append(cmd)), \
                mock.patch.object(Path, "exists", fake_exists), \
                mock.patch.object(sys, "argv", ["run_eval.py", str(merged), *argv]), mock.patch("builtins.print"):
            runpy.run_path(str(HERE / "run_eval.py"), run_name="__main__")
    return [[Path(c[1]).name, *c[2:]] for c in commands], str(merged)


class RunEvalTest(unittest.TestCase):
    def test_con_test_esteso_presente_lancia_solo_base_ed_esteso(self):
        commands, merged = run(["--errors"], extended_exists=True)
        self.assertEqual([["eval_behavior.py", merged, "--errors"],
                          ["eval_behavior.py", merged, "--errors", "--extended"]], commands)

    def test_test_esteso_assente_rigenera_con_il_generatore_italiano(self):
        commands, _ = run([], extended_exists=False)
        self.assertEqual(["generate_eval_set.py"], commands[0])
        self.assertEqual(3, len(commands))

    def test_dataset_inglese_usa_il_generatore_inglese(self):
        commands, _ = run([], extended_exists=False, dataset="pocket_travel_sft.v10.en.jsonl")
        self.assertEqual(["generate_eval_set_en.py"], commands[0])

    def test_opzioni_senza_modello_usano_la_cartella_di_default(self):
        commands = []
        with mock.patch("subprocess.run", side_effect=lambda cmd, check: commands.append(cmd)), \
                mock.patch.object(Path, "exists", lambda self: self.name != "run.json"), \
                mock.patch.object(sys, "argv", ["run_eval.py", "--errors"]), mock.patch("builtins.print"):
            runpy.run_path(str(HERE / "run_eval.py"), run_name="__main__")
        self.assertEqual("--errors", commands[0][2])  # nessun modello: l'opzione resta al suo posto
        self.assertEqual(["--errors", "--extended"], commands[1][2:])


if __name__ == "__main__":
    unittest.main()
