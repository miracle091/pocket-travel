#!/usr/bin/env python3
"""Test della scelta del checkpoint da riprendere e del merge a piena precisione in train_lora.py. Lo script gira a livello di modulo e importa
torch, quindi la funzione si estrae dal sorgente senza eseguirlo.

Uso: python test_train_lora.py
"""
import ast
import sys
import tempfile
import types
import unittest
from unittest import mock
from pathlib import Path

SOURCE = (Path(__file__).resolve().parent / "train_lora.py").read_text(encoding="utf-8")
NODE = next(n for n in ast.parse(SOURCE).body if isinstance(n, ast.FunctionDef) and n.name == "last_complete_checkpoint")
NAMESPACE = {"Path": Path}
exec(compile(ast.Module([NODE], []), "train_lora.py", "exec"), NAMESPACE)
last_complete_checkpoint = NAMESPACE["last_complete_checkpoint"]
MERGE_NODE = next(n for n in ast.parse(SOURCE).body if isinstance(n, ast.FunctionDef) and n.name == "merge_full_precision")


def checkpoint(out, step, complete=True):
    folder = Path(out) / f"checkpoint-{step}"
    folder.mkdir()
    if complete:
        (folder / "trainer_state.json").write_text("{}", encoding="utf-8")
    return str(folder)


class LastCompleteCheckpointTest(unittest.TestCase):
    def test_il_piu_recente_completo(self):
        with tempfile.TemporaryDirectory() as tmp:
            checkpoint(tmp, 100)
            newest = checkpoint(tmp, 1000)
            self.assertEqual(newest, last_complete_checkpoint(tmp))  # 1000 > 200 anche come numero, non come testo
            checkpoint(tmp, 200)
            self.assertEqual(newest, last_complete_checkpoint(tmp))

    def test_il_piu_recente_incompleto_e_saltato(self):
        with tempfile.TemporaryDirectory() as tmp:
            complete = checkpoint(tmp, 100)
            checkpoint(tmp, 200, complete=False)
            self.assertEqual(complete, last_complete_checkpoint(tmp))

    def test_nessun_checkpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(last_complete_checkpoint(tmp))
            checkpoint(tmp, 100, complete=False)
            self.assertIsNone(last_complete_checkpoint(tmp))
            self.assertIsNone(last_complete_checkpoint(Path(tmp) / "non-esiste"))


class FakeModel:
    def __init__(self, calls):
        self.calls = calls

    def merge_and_unload(self):
        self.calls.append("merge")
        return self

    def save_pretrained(self, out):
        self.calls.append(("save", out))


def merge_with_fakes(missing_keys=()):
    """merge_full_precision con transformers e peft finti: registra le chiamate."""
    calls = []
    model = FakeModel(calls)

    class AutoModelForCausalLM:
        @staticmethod
        def from_pretrained(base, dtype, output_loading_info):
            calls.append(("base", base, dtype))
            return model, {"missing_keys": list(missing_keys)}

    class PeftModel:
        @staticmethod
        def from_pretrained(full, lora_dir):
            calls.append(("lora", full is model, lora_dir))
            return model

    peft = types.ModuleType("peft")
    peft.PeftModel = PeftModel
    namespace = {"AutoModelForCausalLM": AutoModelForCausalLM, "sys": sys}
    exec(compile(ast.Module([MERGE_NODE], []), "train_lora.py", "exec"), namespace)
    with mock.patch.dict(sys.modules, {"peft": peft}):
        namespace["merge_full_precision"]("base-solo-testo", "run/lora", "run/merged", "bf16")
    return calls


class MergeFullPrecisionTest(unittest.TestCase):
    def test_fonde_la_lora_nel_modello_base_a_piena_precisione(self):
        self.assertEqual([("base", "base-solo-testo", "bf16"), ("lora", True, "run/lora"), "merge",
                          ("save", "run/merged")], merge_with_fakes())

    def test_si_ferma_se_mancano_pesi(self):
        with self.assertRaises(SystemExit):
            merge_with_fakes(missing_keys=["model.layers.0.mlp.up_proj.weight"])


if __name__ == "__main__":
    unittest.main()
