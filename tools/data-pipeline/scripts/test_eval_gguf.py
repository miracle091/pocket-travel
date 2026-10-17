#!/usr/bin/env python3
"""Test di eval_gguf.py con runpy: il prompt ChatML mandato a llama-server (con e senza blocco <think> vuoto), il
punteggio dei rifiuti, il file --answers e il rapporto di --contexts. Il modulo gguf, llama-server (subprocess.Popen) e
la rete (urllib) sono sostituiti da mock: nessun modello, nessun processo.

Uso: python test_eval_gguf.py
"""
import gc
import io
import json
import runpy
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

import eval_common
from eval_common import REFUSAL, REFUSAL_EN

HERE = Path(__file__).resolve().parent
SECRET_TEMPLATE = b"{% if enable_thinking %}...{% endif %}"


def fake_gguf(template):
    """Modulo gguf finto: GGUFReader(path).fields["tokenizer.chat_template"] con la struttura parts/data di gguf-py."""
    field = types.SimpleNamespace(parts=[template], data=[0]) if template is not None else None
    reader = types.SimpleNamespace(fields={"tokenizer.chat_template": field} if field else {})
    return types.SimpleNamespace(GGUFReader=lambda path: reader)


class FakeResponse:
    def __init__(self, payload=None, status=200):
        self.payload, self.status = payload, status

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, *args):
        return json.dumps(self.payload).encode("utf-8")


def row(kind, user, assistant, region="canada"):
    return {"kind": kind, "region": region, "category": "SALUTE",
            "messages": [{"role": "user", "content": user}, {"role": "assistant", "content": assistant}]}


class EvalGgufTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        (self.tmp / "model.gguf").write_bytes(b"x")
        self.prompts = []

    def run_script(self, *argv, template=None, replies=(), rows=()):
        """Esegue eval_gguf.py; replies = risposte successive di /completion (testo, o dict della risposta intera).
        Restituisce l'output."""
        replies = list(replies)

        def urlopen(request, timeout):
            url = request if isinstance(request, str) else request.full_url
            if url.endswith("/health"):
                return FakeResponse(status=200)
            body = json.loads(request.data.decode("utf-8"))
            self.prompts.append(body)
            reply = replies.pop(0)
            return FakeResponse(reply if isinstance(reply, dict) else {"content": reply})

        server = mock.Mock(poll=lambda: None)
        out = io.StringIO()
        sys_argv = ["eval_gguf.py", str(self.tmp / "model.gguf"), "--llama-cpp", str(self.tmp), *argv]
        with mock.patch.dict(sys.modules, {"gguf": fake_gguf(template)}), \
                mock.patch.object(sys, "argv", sys_argv), mock.patch.object(sys, "stdout", out), \
                mock.patch.object(sys, "stderr", io.StringIO()), \
                mock.patch.object(eval_common, "find_llama_bin", return_value=str(self.tmp / "llama-server")), \
                mock.patch.object(eval_common, "load_test_rows", return_value=(list(rows), {"canada"})), \
                mock.patch("subprocess.Popen", return_value=server) as popen, \
                mock.patch("urllib.request.urlopen", side_effect=urlopen), mock.patch("time.sleep"), \
                mock.patch("status.phase"), mock.patch("gpu_lock.gpu_job"):  # niente lucchetto ne' monitor veri
            try:
                runpy.run_path(str(HERE / "eval_gguf.py"), run_name="__main__")
            except SystemExit as e:
                self.assertIn(e.code, (0, None))
            finally:
                gc.collect()  # lo script lascia aperto il file di --answers: va chiuso prima di cancellare la cartella
        self.popen, self.server = popen, server
        return out.getvalue()

    def test_prompt_chatml_grezzo_e_punteggio(self):
        rows = [row("pos", "Sei una guida.\n\nDOMANDA: Cosa mangiare?", "Si mangia il pesce fresco ovunque."),
                row("neg", "Sei una guida.\n\nDOMANDA: Dov'e' l'ospedale?", REFUSAL + " sulla salute: consulta una fonte ufficiale.")]
        out = self.run_script(replies=["Si mangia il pesce fresco ovunque.", REFUSAL + " sulla salute: altro."], rows=rows)
        self.assertEqual("<|im_start|>user\nSei una guida.\n\nDOMANDA: Cosa mangiare?<|im_end|>\n<|im_start|>assistant\n",
                         self.prompts[0]["prompt"])
        self.assertEqual({"n_predict": 256, "temperature": 0, "top_k": 1, "cache_prompt": False},
                         {k: v for k, v in self.prompts[0].items() if k != "prompt"})
        self.assertIn("POS: rifiuto sbagliato 0.0% | inizio uguale all'atteso 100.0%", out)
        self.assertIn("NEG: rifiuta 100.0% | tema del rifiuto giusto 100.0%", out)
        self.server.kill.assert_called()   # il server si chiude sempre

    def test_blocco_think_vuoto_se_il_template_del_gguf_usa_enable_thinking(self):
        rows = [row("pos", "D?", "R.")]
        self.run_script(template=SECRET_TEMPLATE, replies=["R."], rows=rows)
        self.assertTrue(self.prompts[0]["prompt"].endswith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))

    def test_senza_template_con_thinking_niente_blocco_a_meno_di_empty_think(self):
        rows = [row("pos", "D?", "R.")]
        self.run_script(template=b"{{ messages }}", replies=["R."], rows=rows)
        self.assertNotIn("<think>", self.prompts[0]["prompt"])
        self.prompts.clear()
        self.run_script("--empty-think", replies=["R."], rows=rows)
        self.assertIn("<think>", self.prompts[0]["prompt"])

    def test_limit_e_answers(self):
        rows = [row("pos", f"Q: Domanda {i}?\n\nDOMANDA: Domanda {i}?", f"Risposta {i}.") for i in range(5)]
        answers = self.tmp / "answers.jsonl"
        self.run_script("--limit", "2", "--answers", str(answers), replies=["Risposta 0.", {"content": "Altro", "stop_type": "limit"}],
                        rows=rows)
        lines = [json.loads(l) for l in answers.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(2, len(lines))
        self.assertEqual({"kind": "pos", "region": "canada", "category": "SALUTE", "question": "Domanda 0?",
                          "want": "Risposta 0.", "got": "Risposta 0.", "truncated": False}, lines[0])
        self.assertTrue(lines[1]["truncated"])  # llama-server fermo al limite di token

    def test_contexts_conta_i_rifiuti_con_e_senza_la_sezione(self):
        contexts = self.tmp / "contexts.jsonl"
        items = [
            {"lang": "it", "series": "direct", "search": "word-lists", "context": "Ctx A", "question": "D1?", "section_in_context": True},
            {"lang": "it", "series": "direct", "search": "word-lists", "context": "Ctx B", "question": "D2?", "section_in_context": False},
            {"lang": "en", "series": "direct", "search": "word-lists", "context": "Ctx C", "question": "Q3?", "section_in_context": True},
            # stesso prompt di Ctx A: la decodifica e' greedy, non si richiede di nuovo
            {"lang": "it", "series": "direct", "search": "before-rag", "context": "Ctx A", "question": "D1?", "section_in_context": True},
        ]
        contexts.write_text("\n".join(json.dumps(r) for r in items) + "\n", encoding="utf-8")
        out = self.run_script("--contexts", str(contexts), replies=["Risposta ok.", REFUSAL + " altro.", REFUSAL_EN + " other."])
        self.assertEqual(3, len(self.prompts))
        self.assertIn("it direct word-lists: 2 | 1 | 0/1 | 1/1", out)
        self.assertIn("it direct before-rag: 1 | 1 | 0/1 | 0/0", out)
        self.assertIn("en direct word-lists: 1 | 1 | 1/1 | 0/0", out)
        self.assertIn("CONTESTO: Ctx A", self.prompts[0]["prompt"])
        self.assertIn("CONTEXT: Ctx C", self.prompts[2]["prompt"])

    def test_server_uscito_subito_e_un_errore(self):
        dead = mock.Mock(poll=lambda: 3, returncode=3)
        with mock.patch.dict(sys.modules, {"gguf": fake_gguf(None)}), \
                mock.patch.object(sys, "argv", ["eval_gguf.py", str(self.tmp / "model.gguf"), "--llama-cpp", str(self.tmp)]), \
                mock.patch.object(sys, "stdout", io.StringIO()), mock.patch.object(sys, "stderr", io.StringIO()), \
                mock.patch.object(eval_common, "find_llama_bin", return_value="llama-server"), \
                mock.patch.object(eval_common, "load_test_rows", return_value=([row("pos", "D?", "R.")], set())), \
                mock.patch("subprocess.Popen", return_value=dead), mock.patch("status.phase"), mock.patch("gpu_lock.gpu_job"):
            with self.assertRaises(SystemExit) as ctx:
                runpy.run_path(str(HERE / "eval_gguf.py"), run_name="__main__")
        self.assertIn("codice 3", str(ctx.exception))

    def test_gpu_layers_controlla_la_vram_e_passa_il_device(self):
        with mock.patch("vram.check_llama") as check:
            self.run_script("--gpu-layers", "99", "--device", "Vulkan1", replies=["R."], rows=[row("pos", "D?", "R.")])
        check.assert_called_once()
        cmd = self.popen.call_args.args[0]
        self.assertEqual("99", cmd[cmd.index("-ngl") + 1])
        self.assertEqual("Vulkan1", cmd[cmd.index("--device") + 1])
        self.assertEqual("8192", cmd[cmd.index("-c") + 1])
        self.assertEqual("127.0.0.1", cmd[cmd.index("--host") + 1])


if __name__ == "__main__":
    unittest.main()
