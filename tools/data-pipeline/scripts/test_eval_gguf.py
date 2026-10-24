#!/usr/bin/env python3
"""Test di eval_gguf.py con runpy: il prompt ChatML mandato a llama-server (con e senza blocco <think> vuoto), il
punteggio dei rifiuti, il file --answers e il rapporto di --contexts. Il modulo gguf, llama-server (subprocess.Popen) e
la rete (urllib) sono sostituiti da mock: nessun modello, nessun processo.

Uso: python test_eval_gguf.py
"""
import gc
import io
import json
import re
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


def app_sampling_in_the_app():
    """Temperatura, top-k, top-p e min-p del campionamento dell'app, letti dal codice: ai_chat.cpp, LlamaOnDeviceLlmEngine.kt
    e il default di common_params_sampling in llama.cpp (ai_chat.cpp non lo cambia)."""
    repo = HERE.parents[2]
    ai_chat = (repo / "feature/ai/src/main/cpp/ai_chat.cpp").read_text(encoding="utf-8")
    engine = (repo / "feature/ai/src/main/kotlin/com/pockettravel/feature/ai/LlamaOnDeviceLlmEngine.kt").read_text(encoding="utf-8")
    common = (repo / "third-party/llama-cpp/common/common.h").read_text(encoding="utf-8")
    assert "sparams.min_p" not in ai_chat
    return {"temperature": float(re.search(r"DEFAULT_SAMPLER_TEMP\s*=\s*([\d.]+)f", ai_chat).group(1)),
            "top_k": int(re.search(r"const val TOP_K = (\d+)", engine).group(1)),
            "top_p": float(re.search(r"const val TOP_P = ([\d.]+)f", engine).group(1)),
            "min_p": float(re.search(r"float\s+min_p\s*=\s*([\d.]+)f", common).group(1))}


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

    def run_script(self, *argv, template=None, replies=(), rows=(), handler=None):
        """Esegue eval_gguf.py; replies = risposte successive di /completion (testo, o dict della risposta intera), oppure
        handler(percorso, corpo) -> risposta per ogni richiesta. Restituisce l'output."""
        replies = list(replies)

        def urlopen(request, timeout):
            url = request if isinstance(request, str) else request.full_url
            if url.endswith("/health"):
                return FakeResponse(status=200)
            body = json.loads(request.data.decode("utf-8"))
            self.prompts.append(body)
            if handler:
                return FakeResponse(handler(url.rsplit("/", 1)[1], body))
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
                          "want": "Risposta 0.", "got": "Risposta 0.", "truncated": False, "question_lang": "it",
                          "answer_lang": None, "refused": False, "fidelity": "out", "context_share": 0.0, "too_long": False,
                          "wrong_lang": False, "think_start": False},
                         lines[0])  # "fuori": la riga di prova non ha il contesto con la risposta
        self.assertTrue(lines[1]["truncated"])  # llama-server fermo al limite di token

    def test_esteso_per_lingua_della_domanda(self):
        rows = [row("pos_para", "Sei una guida.\n\nDOMANDA: Cosa si mangia a Roma?", "Si mangia la pasta."),
                row("pos_para", "Sei una guida.\n\nDOMANDA: What is the food like in Rome?", "Si mangia la pasta."),
                row("neg_city", "Sei una guida.\n\nDOMANDA: Where is the hospital?", REFUSAL + " sulla salute: altro."),
                {**row("neg_city", "Sei una guida.\n\nDOMANDA: Dov'e' l'ospedale?", REFUSAL + " sulla salute: altro."),
                 "question_lang": "en"}]  # il campo della riga vale piu' del rilevamento
        answers = self.tmp / "answers.jsonl"
        out = self.run_script("--extended", "--answers", str(answers), rows=rows,
                              replies=["Si mangia la pasta.", "The food is pasta and pizza.", REFUSAL + " sulla salute: x.",
                                       "The hospital is in the center."])
        # la tabella per tipo resta quella di prima, le tabelle per lingua vengono dopo
        self.assertIn("test esteso: 4 righe (domande fuori dai template di training)\n"
                      "neg_city: 2 righe | rifiuta 50.0% (atteso 100%)\npos_para: 2 righe | rifiuta 0.0% (atteso 0%)\ndomande en", out)
        self.assertIn("domande en: 3 righe\nneg_city: 2 righe | rifiuta 50.0% (atteso 100%)\npos_para: 1 righe | rifiuta 0.0% (atteso 0%)\n"
                      "domande it: 1 righe\npos_para: 1 righe | rifiuta 0.0% (atteso 0%)\n", out)
        self.assertIn("risposte nella lingua sbagliata (non quella del dataset): neg_city 1/2, pos_para 1/2\n", out)
        # poi i controlli sulle istruzioni, con la stessa lingua sbagliata; le righe di prova non hanno un contesto con
        # le risposte, quindi le tre risposte non rifiutate sono "fuori"
        self.assertIn("tipo neg_city: 2 righe, rifiuta 50.0%, 1 risposte | letterale 0.0% aderente 0.0% fuori 1 parole dal "
                      "contesto 0% | oltre 450: 0 | lingua sbagliata: 1 | <think>: 0\n", out)
        self.assertIn("risposte fuori dal contesto: 3\n", out)
        lines = [json.loads(l) for l in answers.read_text(encoding="utf-8").splitlines()]
        self.assertEqual([("it", "it"), ("en", "en"), ("en", "it"), ("en", "en")],
                         [(l["question_lang"], l["answer_lang"]) for l in lines])

    def test_kinds_tiene_solo_i_tipi_scelti(self):
        rows = [row("pos_near", "D?", "R."), row("neg_city", "E?", REFUSAL + "."), row("pos_para", "F?", "S.")]
        self.run_script("--kinds", "pos_near", "neg_city", replies=["R.", REFUSAL + "."], rows=rows)
        self.assertEqual(2, len(self.prompts))

    def test_app_sampling_probabilita_del_rifiuto_con_i_token_forzati(self):
        rows = [row("neg_city", "Sei una guida.\n\nDOMANDA: Dov'e' l'ospedale?", REFUSAL + " sulla salute: altro."),
                row("pos_near", "Sei una guida.\n\nDOMANDA: Cosa vedo vicino?", "Il museo.")]
        # rifiuto di 3 token; per riga, probabilita' di ciascun token dato il prefisso forzato (assente = tolto dai sampler)
        probs = {"ospedale": [0.8, 0.5, 1.0], "vicino": [0.4]}
        answers = self.tmp / "answers.jsonl"

        def handler(path, body):
            if path == "tokenize":
                self.assertEqual(REFUSAL, body["content"])
                return {"tokens": [11, 12, 13]}
            if isinstance(body["prompt"], str) and body["top_k"] == 1:   # decodifica greedy
                return {"content": REFUSAL + " sulla salute: x." if "ospedale" in body["prompt"] else "Il museo."}
            if "post_sampling_probs" in body:
                text, *forced = body["prompt"]
                self.assertEqual([11, 12, 13][:len(forced)], forced)
                p = probs["ospedale" if "ospedale" in text else "vicino"]
                step = len(forced)
                top = [{"id": 99, "prob": 0.1}] + ([{"id": [11, 12, 13][step], "prob": p[step]}] if step < len(p) else [])
                return {"completion_probabilities": [{"top_probs": top}]}
            # campioni: rifiuto con i semi pari
            return {"content": ("\n" + REFUSAL + " sulla") if body["seed"] % 2 == 0 else "Il museo."}

        out = self.run_script("--extended", "--app-sampling", "--samples", "4", "--answers", str(answers), rows=rows,
                              handler=handler)
        lines = [json.loads(l) for l in answers.read_text(encoding="utf-8").splitlines()]
        self.assertAlmostEqual(0.4, lines[0]["p_refusal"])
        self.assertEqual(0.0, lines[1]["p_refusal"])   # secondo token non tra i candidati
        self.assertEqual((4, 2), (lines[0]["samples"], lines[0]["sampled_refusals"]))
        sampling = [b for b in self.prompts if "post_sampling_probs" in b]
        self.assertEqual(5, len(sampling))   # 3 token per la prima riga, 2 per la seconda (si ferma a p = 0)
        app = {"temperature": 0.3, "top_k": 10, "top_p": 0.95, "min_p": 0.05, "n_predict": 1, "n_probs": 10,
               "post_sampling_probs": True, "cache_prompt": True}
        self.assertEqual(app, {k: v for k, v in sampling[0].items() if k in app})
        self.assertEqual(app_sampling_in_the_app(), {k: app[k] for k in ("temperature", "top_k", "top_p", "min_p")})
        # ordine di default di common_params_sampling: la temperatura dopo top-k, top-p e min-p
        self.assertEqual(["penalties", "dry", "top_n_sigma", "top_k", "typ_p", "top_p", "min_p", "xtc", "temperature"],
                         sampling[0]["samplers"])
        self.assertEqual(1, len([b for b in self.prompts if "content" in b]))   # rifiuto tokenizzato una volta sola
        self.assertEqual([0, 1, 2, 3], [b["seed"] for b in self.prompts if "seed" in b][:4])
        self.assertIn("tipo neg_city: 1 righe | rifiuta greedy 100.0% | atteso app 40.0% | campionato 50.0% (4 risposte)", out)
        self.assertIn("tipo pos_near: 1 righe | rifiuta greedy 0.0% | atteso app 0.0%", out)
        self.assertIn("righe incerte (p del rifiuto tra 0.2 e 0.8): 1\n  neg_city it p=0.40 greedy rifiuta | Dov'e' l'ospedale?", out)

    def test_samples_senza_app_sampling_e_un_errore(self):
        with mock.patch.object(sys, "argv", ["eval_gguf.py", str(self.tmp / "model.gguf"), "--llama-cpp", str(self.tmp),
                                             "--samples", "3"]), \
                mock.patch.dict(sys.modules, {"gguf": fake_gguf(None)}), mock.patch.object(sys, "stderr", io.StringIO()):
            with self.assertRaises(SystemExit) as ctx:
                runpy.run_path(str(HERE / "eval_gguf.py"), run_name="__main__")
        self.assertEqual(2, ctx.exception.code)

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
