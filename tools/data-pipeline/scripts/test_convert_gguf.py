#!/usr/bin/env python3
"""Test di convert_gguf.py: sequenza dei comandi, opzione --no-mtp, testo di calibrazione dell'imatrix e uscite di
errore. Lo script gira a livello di modulo, quindi si esegue con runpy; subprocess e vram sono sostituiti da mock
(nessun clone di llama.cpp, nessun modello vero).

Uso: python test_convert_gguf.py
"""
import json
import runpy
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import eval_common

HERE = Path(__file__).resolve().parent


def sft_row(region, user, answer):
    return {"region": region, "messages": [{"role": "user", "content": user}, {"role": "assistant", "content": answer}]}


class Workspace:
    """Cartelle temporanee: modello fuso, clone di llama.cpp, dataset SFT con righe di training e di test."""

    def __init__(self, tmp, config=None, template=None):
        root = Path(tmp)
        self.merged = root / "run" / "merged"
        self.merged.mkdir(parents=True)
        (self.merged / "config.json").write_text(json.dumps(config or {}), encoding="utf-8")
        if template is not None:
            (self.merged / "chat_template.jinja").write_text(template, encoding="utf-8")
        self.clone = root / "llama.cpp"
        self.clone.mkdir()
        (self.clone / "convert_hf_to_gguf.py").write_text("", encoding="utf-8")
        self.sft = root / "sft"
        self.sft.mkdir()
        rows = [sft_row("italia", f"Domanda {i} città?", f"Risposta {i} è così.") for i in range(5)]
        rows.append(sft_row("canada", "Domanda di test", "Risposta di test"))  # regione di test: mai nella calibrazione
        (self.sft / eval_common.DEFAULT_DATASET).write_text(
            "\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")
        self.output = root / "out" / "model.gguf"
        self.output.parent.mkdir()


def run(ws, extra=(), bins=None):
    """Esegue convert_gguf.py; restituisce (comandi, calibrazione letta durante llama-imatrix, output di phase)."""
    commands, seen = [], {}

    def fake_run(cmd, check, env, cwd=None):
        commands.append((cmd, cwd))
        if "--outfile" in cmd:
            Path(cmd[cmd.index("--outfile") + 1]).write_bytes(b"F16")
        elif Path(cmd[0]).name.startswith("llama-imatrix"):
            seen["calibration"] = (Path(cwd) / "calibration.txt").read_text(encoding="utf-8")
        elif Path(cmd[0]).name.startswith("llama-quantize"):
            Path(cmd[-2]).write_bytes(b"Q4")

    argv = ["convert_gguf.py", str(ws.merged), str(ws.output), "--llama-cpp", str(ws.clone), *extra]
    with mock.patch("subprocess.run", side_effect=fake_run), \
            mock.patch.object(eval_common, "SFT_DIR", ws.sft), \
            mock.patch.object(eval_common, "find_llama_bin", side_effect=lambda name, *d: str(Path(ws.clone) / name)), \
            mock.patch("vram.check_llama") as check_llama, \
            mock.patch.object(sys, "argv", argv), mock.patch("status.phase") as phase:
        runpy.run_path(str(HERE / "convert_gguf.py"), run_name="__main__")
    return commands, seen.get("calibration"), phase, check_llama


class ConvertGgufTest(unittest.TestCase):
    def test_quantize_none_sposta_il_f16_all_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            ws = Workspace(tmp)
            commands, _, _, _ = run(ws, ["--quantize", "none"])
            self.assertEqual(b"F16", ws.output.read_bytes())
        self.assertEqual(1, len(commands))
        cmd = commands[0][0]
        self.assertEqual([sys.executable, str(ws.clone / "convert_hf_to_gguf.py"), str(ws.merged)], cmd[:3])
        self.assertEqual("f16", cmd[cmd.index("--outtype") + 1])
        self.assertNotIn("--no-mtp", cmd)

    def test_quantizzazione_con_imatrix_di_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            ws = Workspace(tmp)
            commands, calibration, _, check_llama = run(ws)
            self.assertEqual(b"Q4", ws.output.read_bytes())
        self.assertEqual(3, len(commands))
        imatrix, quantize = commands[1], commands[2]
        self.assertEqual(["-f", "calibration.txt", "-o", "imatrix.gguf", "-c", "512", "-ngl", "0"], imatrix[0][3:11])
        self.assertEqual(["--imatrix", "imatrix.gguf"], quantize[0][1:3])
        self.assertEqual("Q4_K_M", quantize[0][-1])
        self.assertEqual(imatrix[1], quantize[1])  # stessa cartella temporanea: i nomi relativi funzionano
        check_llama.assert_not_called()  # senza --gpu-layers nessun controllo della VRAM
        # la calibrazione non contiene la regione di test e ha il formato ChatML dell'app, accenti compresi
        self.assertNotIn("Domanda di test", calibration)
        self.assertEqual(5, calibration.count("<|im_start|>user\n"))
        self.assertRegex(calibration, r"<\|im_start\|>user\nDomanda (\d) città\?<\|im_end\|>\n"
                                      r"<\|im_start\|>assistant\nRisposta \1 è così\.<\|im_end\|>\n")

    def test_qwen35_quantizzazione_mista_di_default(self):
        qwen35 = {"text_config": {"layer_types": ["linear_attention", "full_attention"]}}
        with tempfile.TemporaryDirectory() as tmp:
            commands, _, _, _ = run(Workspace(tmp, config=qwen35))
        quantize = commands[2][0]
        self.assertEqual("Q5_K_M", quantize[-1])
        self.assertEqual(["--imatrix", "imatrix.gguf", "--token-embedding-type", "q5_k", "--output-tensor-type", "q5_k"],
                         quantize[1:7])
        overrides = [quantize[i + 1] for i, arg in enumerate(quantize) if arg == "--tensor-type"]
        self.assertEqual([r"attn_q\.=q6_k", r"attn_k\.=q6_k", r"attn_v\.=q6_k", "attn_output=q6_k", "ssm_out=q6_k"],
                         overrides)

    def test_quantize_esplicito_vince_su_auto(self):
        qwen35 = {"layer_types": ["linear_attention"]}
        with tempfile.TemporaryDirectory() as tmp:
            plain, _, _, _ = run(Workspace(tmp, config=qwen35), ["--quantize", "Q4_K_M"])
        with tempfile.TemporaryDirectory() as tmp:
            mixed, _, _, _ = run(Workspace(tmp), ["--quantize", "Q5_K_M-mix"])
        self.assertEqual(["llama-quantize", "--imatrix", "imatrix.gguf"], [Path(plain[2][0][0]).name, *plain[2][0][1:3]])
        self.assertNotIn("--tensor-type", plain[2][0])
        self.assertEqual("Q4_K_M", plain[2][0][-1])
        self.assertIn("--tensor-type", mixed[2][0])
        self.assertEqual("Q5_K_M", mixed[2][0][-1])

    def test_imatrix_rows_zero_salta_l_imatrix(self):
        with tempfile.TemporaryDirectory() as tmp:
            ws = Workspace(tmp)
            commands, calibration, _, _ = run(ws, ["--imatrix-rows", "0", "--quantize", "Q8_0"])
        self.assertEqual(2, len(commands))
        self.assertNotIn("--imatrix", commands[1][0])
        self.assertEqual("Q8_0", commands[1][0][-1])
        self.assertIsNone(calibration)

    def test_righe_di_calibrazione_limitate_dal_dataset(self):
        with tempfile.TemporaryDirectory() as tmp:
            ws = Workspace(tmp)
            _, calibration, _, _ = run(ws, ["--imatrix-rows", "2"])
        self.assertEqual(2, calibration.count("<|im_start|>user\n"))

    def test_blocco_think_vuoto_se_il_template_usa_enable_thinking(self):
        think = "<think>\n\n</think>\n\n"
        with tempfile.TemporaryDirectory() as tmp:
            _, with_template, _, _ = run(Workspace(tmp, template="{% if enable_thinking %}x{% endif %}"))
        with tempfile.TemporaryDirectory() as tmp:
            _, without, _, _ = run(Workspace(tmp, template="{{ messages }}"))
        with tempfile.TemporaryDirectory() as tmp:
            _, forced, _, _ = run(Workspace(tmp), ["--empty-think"])
        self.assertIn("<|im_start|>assistant\n" + think + "Risposta", with_template)
        self.assertNotIn("<think>", without)
        self.assertIn(think, forced)

    def test_mtp_dichiarato_aggiunge_no_mtp_anche_in_text_config(self):
        for config in ({"mtp_num_hidden_layers": 1}, {"text_config": {"mtp_num_hidden_layers": 1}}):
            with tempfile.TemporaryDirectory() as tmp:
                commands, _, _, _ = run(Workspace(tmp, config=config), ["--quantize", "none"])
            self.assertEqual("--no-mtp", commands[0][0][-1], config)

    def test_mtp_zero_non_aggiunge_no_mtp(self):
        with tempfile.TemporaryDirectory() as tmp:
            commands, _, _, _ = run(Workspace(tmp, config={"mtp_num_hidden_layers": 0}), ["--quantize", "none"])
        self.assertNotIn("--no-mtp", commands[0][0])

    def test_gpu_layers_controlla_la_vram_prima_della_conversione(self):
        with tempfile.TemporaryDirectory() as tmp:
            commands, _, _, check_llama = run(Workspace(tmp), ["--gpu-layers", "99", "--device", "Vulkan1"])
        check_llama.assert_called_once()
        self.assertEqual("Vulkan1", check_llama.call_args.args[2])
        imatrix = commands[1][0]
        self.assertEqual("99", imatrix[imatrix.index("-ngl") + 1])
        self.assertEqual(["--device", "Vulkan1"], imatrix[-2:])

    def test_clone_senza_script_di_conversione(self):
        with tempfile.TemporaryDirectory() as tmp:
            ws = Workspace(tmp)
            (ws.clone / "convert_hf_to_gguf.py").unlink()
            with self.assertRaises(SystemExit) as ctx:
                run(ws)
        self.assertIn("convert_hf_to_gguf.py non trovato", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
