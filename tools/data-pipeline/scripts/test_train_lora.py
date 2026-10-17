#!/usr/bin/env python3
"""Test della scelta del checkpoint da riprendere in train_lora.py. Lo script gira a livello di modulo e importa
torch, quindi la funzione si estrae dal sorgente senza eseguirlo.

Uso: python test_train_lora.py
"""
import ast
import tempfile
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parent / "train_lora.py").read_text(encoding="utf-8")
NODE = next(n for n in ast.parse(SOURCE).body if isinstance(n, ast.FunctionDef) and n.name == "last_complete_checkpoint")
NAMESPACE = {"Path": Path}
exec(compile(ast.Module([NODE], []), "train_lora.py", "exec"), NAMESPACE)
last_complete_checkpoint = NAMESPACE["last_complete_checkpoint"]


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


if __name__ == "__main__":
    unittest.main()
