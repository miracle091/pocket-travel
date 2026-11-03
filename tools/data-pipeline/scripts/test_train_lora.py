#!/usr/bin/env python3
"""Test della scelta del checkpoint da riprendere, del merge a piena precisione, dell'arresto a loss non finita o con
moduli LoRA assenti, delle velocita' di benchmark.json e della compilazione a blocchi di Unsloth spenta in train_lora.py. Lo script gira a livello di modulo e importa
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
SPOT_NODE = next(n for n in ast.parse(SOURCE).body if isinstance(n, ast.FunctionDef) and n.name == "spot_check_rows")
SPOT_NAMESPACE = {"random": __import__("random")}
exec(compile(ast.Module([SPOT_NODE], []), "train_lora.py", "exec"), SPOT_NAMESPACE)
spot_check_rows = SPOT_NAMESPACE["spot_check_rows"]
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


class SpotCheckRowsTest(unittest.TestCase):
    ROWS = [{"kind": k, "n": i} for k in ("pos", "neg") for i in range(20)]

    def test_due_positivi_e_due_negativi_sempre_gli_stessi(self):
        picked = spot_check_rows(self.ROWS)
        self.assertEqual(["pos", "pos", "neg", "neg"], [r["kind"] for r in picked])
        self.assertEqual(picked, spot_check_rows(self.ROWS))

    def test_un_tipo_senza_righe_si_salta(self):
        only_pos = [r for r in self.ROWS if r["kind"] == "pos"]
        self.assertEqual(["pos", "pos"], [r["kind"] for r in spot_check_rows(only_pos)])
        self.assertEqual([], spot_check_rows([]))


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


def extract(*names, **namespace):
    nodes = [n for n in ast.parse(SOURCE).body if isinstance(n, (ast.FunctionDef, ast.ClassDef)) and n.name in names]
    exec(compile(ast.Module(nodes, []), "train_lora.py", "exec"), namespace)
    return namespace


class LoraTargetsTest(unittest.TestCase):
    # Moduli di un Qwen3.5: i livelli a linear attention non hanno q_proj, quelli a full attention si'
    NAMES = ["model.language_model.layers.0.linear_attn.in_proj_qkv", "model.language_model.layers.0.mlp.gate_proj",
             "model.language_model.layers.3.self_attn.q_proj", "model.language_model.layers.3.self_attn.k_proj"]

    def test_basta_un_modulo_per_nome(self):
        missing_lora_targets = extract("missing_lora_targets")["missing_lora_targets"]
        self.assertEqual([], missing_lora_targets(self.NAMES, ["q_proj", "k_proj", "gate_proj"]))

    def test_elenca_i_mancanti_senza_corrispondenze_parziali(self):
        missing_lora_targets = extract("missing_lora_targets")["missing_lora_targets"]
        # "proj" non corrisponde a "q_proj": come peft, conta solo l'ultima parte intera del nome
        self.assertEqual(["v_proj", "proj"], missing_lora_targets(self.NAMES, ["q_proj", "v_proj", "proj"]))

    def test_si_ferma_se_ne_manca_uno(self):
        ns = extract("missing_lora_targets", "stop_on_missing_lora_targets", sys=sys,
                     LORA={"target_modules": ["q_proj", "o_proj"]})
        model = types.SimpleNamespace(named_modules=lambda: [(n, None) for n in self.NAMES])
        with self.assertRaises(SystemExit) as stop:
            ns["stop_on_missing_lora_targets"](model)
        self.assertIn("['o_proj']", str(stop.exception.code))


class ThroughputTest(unittest.TestCase):
    def test_dal_primo_passo_misurato_all_ultimo(self):
        throughput = extract("throughput")["throughput"]
        # Ripreso al passo 100: contano i 50 passi e i token fatti in questo processo
        self.assertEqual({"steps_per_second": 0.25, "tokens_per_second": 1000.0},
                         throughput((101, 400_000, 10.0), (151, 600_000, 210.0)))

    def test_senza_misure_o_con_un_passo_solo(self):
        throughput = extract("throughput")["throughput"]
        empty = {"steps_per_second": None, "tokens_per_second": None}
        self.assertEqual(empty, throughput(None, None))
        self.assertEqual(empty, throughput((1, 900, 5.0), (1, 900, 5.0)))

    def test_token_non_contati(self):
        throughput = extract("throughput")["throughput"]
        self.assertEqual({"steps_per_second": 0.5, "tokens_per_second": None}, throughput((1, 0, 0.0), (3, 0, 4.0)))


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
