#!/usr/bin/env python3
"""Test dell'ordine lucchetto -> scelta della GPU -> torch negli script che usano la GPU con torch (train_lora.py,
eval_behavior.py, dpo_lora.py): gpu_lock.gpu_job prima di gpu_lock.pick_discrete_gpu, e tutte e due prima di ogni
import di torch, Unsloth o delle librerie che lo caricano, e prima di ogni uso di torch. Si legge il sorgente senza
eseguirlo: importare torch inizializzerebbe la GPU.

Uso: python test_gpu_scripts.py
"""
import ast
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
GPU_MODULES = {"torch", "unsloth", "transformers", "peft", "datasets"}


def imported(node):
    """Moduli importati dall'istruzione (solo il primo nome del percorso), o un insieme vuoto."""
    if isinstance(node, ast.Import):
        return {alias.name.split(".")[0] for alias in node.names}
    if isinstance(node, ast.ImportFrom) and node.module and not node.level:
        return {node.module.split(".")[0]}
    return set()


def loads_torch(module, seen=None):
    """True se lo script locale module importa, a livello di modulo e anche passando per altri script locali, torch o
    una libreria che lo carica."""
    seen = set() if seen is None else seen
    path = HERE / f"{module}.py"
    if module in seen or not path.is_file():
        return False
    seen.add(module)
    names = set().union(*(imported(n) for n in ast.parse(path.read_text(encoding="utf-8")).body))
    return bool(names & GPU_MODULES) or any(loads_torch(name, seen) for name in names)


def first_index(body, test):
    return next((i for i, node in enumerate(body) if test(node)), None)


def touches_gpu(node):
    """Istruzione eseguita che importa torch (anche passando per uno script locale) o usa il nome torch. Le definizioni
    di funzioni e classi non si eseguono quando le si incontra e restano fuori."""
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
        return False
    if any(isinstance(n, ast.Name) and n.id == "torch" for n in ast.walk(node)):
        return True
    return any(name in GPU_MODULES or loads_torch(name)
               for n in ast.walk(node) if isinstance(n, (ast.Import, ast.ImportFrom)) for name in imported(n))


def calls(name):
    return lambda node: f"gpu_lock.{name}(" in ast.unparse(node) and not isinstance(node, (ast.FunctionDef, ast.ClassDef))


def body_of(script, function=None):
    tree = ast.parse((HERE / script).read_text(encoding="utf-8"))
    if function is None:
        return tree.body
    return next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == function).body


class GpuOrderTest(unittest.TestCase):
    def check_order(self, script, function=None):
        body = body_of(script, function)
        lock, pick, gpu = first_index(body, calls("gpu_job")), first_index(body, calls("pick_discrete_gpu")), \
            first_index(body, touches_gpu)
        self.assertIsNotNone(lock, f"{script}: manca gpu_lock.gpu_job")
        self.assertIsNotNone(pick, f"{script}: manca gpu_lock.pick_discrete_gpu")
        self.assertIsNotNone(gpu, f"{script}: nessun import di torch trovato")
        self.assertLess(lock, pick, f"{script}: la GPU si sceglie dopo aver preso il lucchetto")
        self.assertLess(pick, gpu, f"{script}: torch arriva prima della scelta della GPU: {ast.unparse(body[gpu])[:80]}")

    def test_train_lora(self):
        self.check_order("train_lora.py")

    def test_eval_behavior(self):
        self.check_order("eval_behavior.py")

    def test_dpo_lora(self):
        self.check_order("dpo_lora.py", "main")
        # dpo_lora.py si importa nei test senza GPU: a livello di modulo niente torch
        self.assertFalse(any(touches_gpu(n) for n in body_of("dpo_lora.py")))

    def test_il_controllo_vede_torch_prima_del_lucchetto(self):
        # Lo stesso controllo su uno script con l'ordine sbagliato deve fallire
        body = ast.parse("import torch\nimport gpu_lock\ngpu_lock.gpu_job('x')\ngpu_lock.pick_discrete_gpu('x')\n").body
        self.assertEqual(0, first_index(body, touches_gpu))
        self.assertEqual(2, first_index(body, calls("gpu_job")))
        # e anche torch caricato passando per uno script locale
        self.assertTrue(loads_torch("eval_behavior"))
        self.assertFalse(loads_torch("gpu_lock"))


if __name__ == "__main__":
    unittest.main()
