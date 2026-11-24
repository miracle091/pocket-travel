#!/usr/bin/env python3
"""Test dell'attesa di /health di llama-server in model_categories.py e in RerankScorer (eval_retrieval.py): server
terminato o mai pronto = SystemExit con un messaggio, risposta di /health sempre chiusa. Il modulo gguf, llama-server
(subprocess.Popen) e la rete (urllib) sono sostituiti da mock: nessun modello, nessun processo.

Uso: python test_model_categories.py
"""
import sys
import tempfile
import types
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

import eval_common

sys.modules.setdefault("gguf", types.SimpleNamespace(GGUFReader=lambda path: types.SimpleNamespace(fields={})))

import eval_retrieval as ev
import model_categories as mc


class FakeHealth:
    def __init__(self, status=200):
        self.status, self.closed = status, False

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.closed = True
        return False


class HealthWaitTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        (self.tmp / "model.gguf").write_bytes(b"x")
        self.responses = []

    def server(self, exit_code):
        return mock.Mock(poll=lambda: exit_code, returncode=exit_code)

    def patches(self, server, health):
        """health: lista di FakeHealth, oppure None = /health sempre irraggiungibile."""
        def urlopen(request, timeout):
            if health is None:
                raise urllib.error.URLError("connessione rifiutata")
            self.responses.append(health[0])
            return health[0]
        return [mock.patch.object(eval_common, "find_llama_bin", return_value="llama-server"),
                mock.patch.object(eval_common, "llama_env", return_value={}),
                mock.patch("subprocess.Popen", return_value=server),
                mock.patch("urllib.request.urlopen", side_effect=urlopen), mock.patch("time.sleep")]

    def run_categories(self, server, health):
        argv = ["model_categories.py", str(self.tmp / "model.gguf"), "--llama-cpp", str(self.tmp), "--out", str(self.tmp / "o.json")]
        patches = self.patches(server, health) + [mock.patch.object(sys, "argv", argv),
                                                  mock.patch.object(mc, "find_llama_bin", return_value="llama-server")]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        mc.main()

    def make_scorer(self, server, health):
        for p in self.patches(server, health):
            p.start()
            self.addCleanup(p.stop)
        with mock.patch("atexit.register"):
            return ev.RerankScorer(self.tmp / "model.gguf", self.tmp, 0, None, self.tmp / "cache.json")

    def test_categorie_server_terminato_esce_con_il_codice(self):
        server = self.server(3)
        with self.assertRaises(SystemExit) as ctx:
            self.run_categories(server, None)
        self.assertIn("codice 3", str(ctx.exception))
        server.kill.assert_called()

    def test_categorie_health_mai_pronto_esce_con_un_messaggio(self):
        server = self.server(None)
        with self.assertRaises(SystemExit) as ctx:
            self.run_categories(server, None)
        self.assertIn("non pronto", str(ctx.exception))
        server.kill.assert_called()

    def test_categorie_chiude_la_risposta_di_health(self):
        health = [FakeHealth()]
        with mock.patch.object(mc, "questions", side_effect=RuntimeError("stop")), self.assertRaises(RuntimeError):
            self.run_categories(self.server(None), health)
        self.assertTrue(health[0].closed)

    def test_rerank_server_terminato_esce_con_il_codice(self):
        with self.assertRaises(SystemExit) as ctx:
            self.make_scorer(self.server(7), None)
        self.assertIn("codice 7", str(ctx.exception))

    def test_rerank_health_mai_pronto_esce_con_un_messaggio(self):
        with self.assertRaises(SystemExit) as ctx:
            self.make_scorer(self.server(None), None)
        self.assertIn("non pronto", str(ctx.exception))

    def test_rerank_chiude_la_risposta_di_health(self):
        health = [FakeHealth()]
        scorer = self.make_scorer(self.server(None), health)
        self.assertTrue(health[0].closed)
        self.assertEqual({}, scorer.cache)


if __name__ == "__main__":
    unittest.main()
