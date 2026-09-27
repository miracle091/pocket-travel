#!/usr/bin/env python3
"""Converte un Qwen2.5/Qwen3 fine-tunato (cartella merged) in GGUF quantizzato con gli strumenti di llama.cpp.

Quantizzazione con imatrix calibrato sul dataset SFT (--imatrix-rows, default 400; serve anche llama-imatrix):
e' l'alternativa riproducibile alle quant "Dynamic 2.0" di Unsloth, la cui ricetta non e' pubblicata.
Provato anche su Windows con l'ambiente Python di Unsloth Studio e --bin-dir verso una build di llama.cpp.

Uso (Linux/WSL o Windows, richiede un clone separato di llama.cpp allo stesso tag vendorizzato in
third-party/llama-cpp — v0.4.1, vedi feature/ai/src/main/cpp/CMakeLists.txt — con le dipendenze
Python installate: `pip install -r <llama.cpp>/requirements/requirements-convert_hf_to_gguf.txt`,
e llama-quantize compilato: `cmake -B build && cmake --build build --target llama-quantize`):
  python convert_gguf.py <cartella merged> <file .gguf di output> \
      --llama-cpp <path al clone di llama.cpp> [--quantize Q4_K_M]
third-party/llama-cpp resta un vendor solo Android (niente strumenti Python): il clone di conversione
vive fuori dal repo, solo sulla macchina di training, come gia' oggi per litert-torch/ai-edge-quantizer.

Il tokenizer e il chat template sono quelli della cartella merged (lo stesso ChatML del training):
convert_hf_to_gguf.py li legge da tokenizer_config.json/chat_template.jinja e li scrive nei metadata
del GGUF. Nota per chi tocca ai_chat.cpp: OnDeviceLlmEngine oggi non li usa a runtime (chat_add_and_format
chiama common_chat_format_single con use_jinja=false, quindi passa dal formatter CHATML hardcoded di
llama.cpp — src/llama-chat.cpp — non dal chat_template scritto qui nel file). Il formatter non inserisce il blocco
"<think>\n\n</think>\n\n" che il training assume nel turno assistente: lo aggiunge ai_chat.cpp quando il
template supporta enable_thinking (Qwen3/Qwen3.5), e la calibrazione dell'imatrix qui fa lo stesso.
"""
import argparse
import json
import random
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import vram
from eval_common import SFT_DIR, TEST_REGIONS, find_llama_bin, llama_env, run_dataset
from status import duration, phase

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("merged", type=Path)
ap.add_argument("output", type=Path)
ap.add_argument("--llama-cpp", type=Path, required=True, help="cartella del clone locale di llama.cpp (tag v0.4.1)")
ap.add_argument("--quantize", default="Q4_K_M", help='tipo llama-quantize (es. Q4_K_M, Q8_0), "none" per tenere il f16')
ap.add_argument("--bin-dir", type=Path, help="cartella con llama-quantize/llama-imatrix (get_llama_tools.py), se non sono nel clone o nel PATH")
ap.add_argument("--gpu-layers", type=int, default=0,
                help="layer su GPU per llama-imatrix (99 = tutti; serve una build GPU di get_llama_tools.py, e la GPU libera)")
ap.add_argument("--max-vram-held", type=float, default=vram.DEFAULT_MAX_HELD_PCT, help=vram.HELP + ", con --gpu-layers")
ap.add_argument("--device", help="GPU per llama-imatrix, come in `llama-server --list-devices` (es. Vulkan0, ROCm1)")
ap.add_argument("--imatrix-rows", type=int, default=400,
                help="righe del dataset SFT (regioni di training) per calibrare l'imatrix; 0 = quantizzazione senza imatrix")
ap.add_argument("--empty-think", action="store_true",
                help="forza il blocco <think> vuoto nella calibrazione (di default: se il chat template usa enable_thinking, come ai_chat.cpp)")
a = ap.parse_args()

HERE = Path(__file__).resolve().parent


def find_bin(name):
    """Eseguibile di llama.cpp: --bin-dir, le cartelle di build del clone, poi il PATH."""
    return find_llama_bin(name, a.bin_dir, a.llama_cpp)


def write_calibration(path):
    """Testo di calibrazione per l'imatrix: prompt e risposte del dataset, formattati come a runtime
    (ChatML di ai_chat.cpp), solo regioni di training: l'imatrix pesa i canali che il modello usa davvero
    per questo compito (italiano/inglese, contesto Wikivoyage, rifiuti) invece di un testo generico."""
    rows = [json.loads(l) for l in open(SFT_DIR / run_dataset(a.merged), encoding="utf-8")]
    rows = [r for r in rows if r["region"] not in TEST_REGIONS]
    template = a.merged / "chat_template.jinja"
    thinking = template.exists() and "enable_thinking" in template.read_text(encoding="utf-8")
    think = "<think>\n\n</think>\n\n" if a.empty_think or thinking else ""
    with open(path, "w", encoding="utf-8") as f:
        for r in random.Random(42).sample(rows, min(a.imatrix_rows, len(rows))):
            user, answer = r["messages"][0]["content"], r["messages"][1]["content"]
            f.write(f"<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n{think}{answer}<|im_end|>\n")


convert_script = a.llama_cpp / "convert_hf_to_gguf.py"
if not convert_script.exists():
    sys.exit(f"convert_hf_to_gguf.py non trovato in {a.llama_cpp} (e' un clone di llama.cpp aggiornato al tag v0.4.1?)")

def step(name, detail, cmd):
    """Esegue una fase e ne stampa inizio e durata (l'output degli strumenti di llama.cpp resta sotto)."""
    phase(name, detail)
    start = time.monotonic()
    subprocess.run(cmd, check=True, env=llama_env(cmd[0]))
    phase(f"{name} completata", f"in {duration(time.monotonic() - start)}")


# Qwen3.5 dichiara un layer MTP (predizione di piu' token) che il merge di train_lora.py non salva:
# senza --no-mtp il GGUF conta un blocco in piu' e llama.cpp non lo carica
# ("tensor 'blk.24.attn_norm.weight' not found"). L'app non usa MTP.
config = json.loads((a.merged / "config.json").read_text(encoding="utf-8"))
no_mtp = ["--no-mtp"] if config.get("text_config", config).get("mtp_num_hidden_layers") else []

if a.gpu_layers and a.imatrix_rows and a.quantize.lower() != "none":  # subito, non dopo la conversione F16
    imatrix_bin = find_bin("llama-imatrix")
    vram.check_llama(imatrix_bin, llama_env(imatrix_bin), a.device, a.max_vram_held)

with tempfile.TemporaryDirectory() as tmp:
    f16_path = Path(tmp) / "model-f16.gguf"
    step("conversione GGUF F16", str(a.merged),
         [sys.executable, str(convert_script), str(a.merged), "--outfile", str(f16_path), "--outtype", "f16", *no_mtp])

    if a.quantize.lower() == "none":
        shutil.move(str(f16_path), a.output)
    else:
        imatrix = []
        if a.imatrix_rows:
            calib, imatrix_path = Path(tmp) / "calibration.txt", Path(tmp) / "imatrix.gguf"
            write_calibration(calib)
            step("calcolo imatrix", f"{a.imatrix_rows} righe del dataset di training",
                 [find_bin("llama-imatrix"), "-m", str(f16_path), "-f", str(calib), "-o", str(imatrix_path), "-c", "512",
                  "-ngl", str(a.gpu_layers), *(["--device", a.device] if a.device else [])])
            imatrix = ["--imatrix", str(imatrix_path)]
        step("quantizzazione", a.quantize + (" con imatrix" if imatrix else ""),
             [find_bin("llama-quantize"), *imatrix, str(f16_path), str(a.output), a.quantize])

phase("fatto", f"{a.output} ({a.output.stat().st_size / 2**20:.1f} MiB)")
