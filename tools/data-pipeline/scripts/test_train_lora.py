#!/usr/bin/env python3
"""Test della scelta del checkpoint da riprendere, del merge a piena precisione, dell'arresto a loss non finita e della
compilazione a blocchi di Unsloth spenta in train_lora.py. Lo script gira a livello di modulo e importa
torch, quindi la funzione si estrae dal sorgente senza eseguirlo.

Uso: python test_train_lora.py
"""
import ast
import math
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


def stop_on_non_finite():
    """StopOnNonFinite e non_finite_metric estratte dal sorgente, con un TrainerCallback finto."""
    nodes = [n for n in ast.parse(SOURCE).body
             if isinstance(n, (ast.FunctionDef, ast.ClassDef)) and n.name in ("non_finite_metric", "StopOnNonFinite")]
    namespace = {"math": math, "sys": sys, "TrainerCallback": object}
    exec(compile(ast.Module(nodes, []), "train_lora.py", "exec"), namespace)
    return namespace["StopOnNonFinite"]()


class StopOnNonFiniteTest(unittest.TestCase):
    ARGS = types.SimpleNamespace(output_dir="run")
    STATE = types.SimpleNamespace(global_step=7)

    def test_loss_e_gradiente_finiti_continuano(self):
        stop_on_non_finite().on_log(self.ARGS, self.STATE, None, logs={"loss": 0.62, "grad_norm": 1.08, "learning_rate": 2e-4})
        stop_on_non_finite().on_log(self.ARGS, self.STATE, None, logs={"eval_loss": 0.35})
        stop_on_non_finite().on_log(self.ARGS, self.STATE, None, logs=None)

    def test_si_ferma_a_loss_gradiente_o_eval_non_finiti(self):
        # Loss 0 con grad_norm NaN: la loss da sola sembrerebbe buona
        for logs in ({"loss": 0.0, "grad_norm": float("nan")}, {"loss": float("inf")}, {"eval_loss": float("nan")}):
            with self.subTest(logs=logs), self.assertRaises(SystemExit) as stop:
                stop_on_non_finite().on_log(self.ARGS, self.STATE, None, logs=logs)
            self.assertIn("passo 7", str(stop.exception.code))


class UnslothBlockCompileTest(unittest.TestCase):
    def test_spenta_prima_di_importare_unsloth(self):
        # In cima allo script, prima di Unsloth e del caricamento del modello, anche quando lo si lancia senza train_auto.py
        body = ast.parse(SOURCE).body
        setdefault = next(i for i, n in enumerate(body) if "UNSLOTH_BLOCK_COMPILE" in ast.unparse(n))
        first_import = next(i for i, n in enumerate(body) if "import unsloth" in ast.unparse(n))
        self.assertLess(setdefault, first_import)
        self.assertEqual("os.environ.setdefault('UNSLOTH_BLOCK_COMPILE', '0')", ast.unparse(body[setdefault]))


if __name__ == "__main__":
    unittest.main()
