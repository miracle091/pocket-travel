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
llama.cpp — src/llama-chat.cpp — non dal chat_template scritto qui nel file); per Qwen3 questo formatter
non inserisce il blocco "<think>\n\n</think>\n\n" che il training assume sempre presente nel prefisso del
turno assistente. Non e' un problema di questo
script: se serve parita' di comportamento con il training per i modelli Qwen3, va risolto lato ai_chat.cpp.
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

from status import duration, phase

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("merged", type=Path)
ap.add_argument("output", type=Path)
ap.add_argument("--llama-cpp", type=Path, required=True, help="cartella del clone locale di llama.cpp (tag v0.4.1)")
ap.add_argument("--quantize", default="Q4_K_M", help='tipo llama-quantize (es. Q4_K_M, Q8_0), "none" per tenere il f16')
ap.add_argument("--bin-dir", type=Path, help="cartella con llama-quantize/llama-imatrix, se non sono nel clone o nel PATH")
ap.add_argument("--imatrix-rows", type=int, default=400,
                help="righe del dataset SFT (regioni di training) per calibrare l'imatrix; 0 = quantizzazione senza imatrix")
ap.add_argument("--empty-think", action="store_true",
                help="calibrazione con il blocco <think> vuoto, come ai_chat.cpp per i template Qwen3/Qwen3.5")
a = ap.parse_args()

HERE = Path(__file__).resolve().parent


def find_bin(name):
    """Eseguibile di llama.cpp: PATH, --bin-dir o le cartelle di build del clone (anche .exe e Release/)."""
    dirs = [a.bin_dir] if a.bin_dir else []
    dirs += [a.llama_cpp / "build" / "bin", a.llama_cpp / "build" / "bin" / "Release", a.llama_cpp / "build"]
    found = shutil.which(name) or next(
        (str(d / f"{name}{ext}") for d in dirs for ext in ("", ".exe") if (d / f"{name}{ext}").exists()), None)
    if not found:
        sys.exit(f"{name} non trovato (PATH, --bin-dir, {a.llama_cpp / 'build'}): compilalo con "
                 f"`cmake -B build && cmake --build build --target {name}` nel clone di llama.cpp")
    return found


def write_calibration(path):
    """Testo di calibrazione per l'imatrix: prompt e risposte del dataset, formattati come a runtime
    (ChatML di ai_chat.cpp), solo regioni di training: l'imatrix pesa i canali che il modello usa davvero
    per questo compito (italiano/inglese, contesto Wikivoyage, rifiuti) invece di un testo generico."""
    from eval_common import SFT_DIR, TEST_REGIONS
    rows = [json.loads(l) for l in open(SFT_DIR / "pocket_travel_sft.jsonl", encoding="utf-8")]
    rows = [r for r in rows if r["region"] not in TEST_REGIONS]
    think = "<think>\n\n</think>\n\n" if a.empty_think else ""
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
    subprocess.run(cmd, check=True)
    phase(f"{name} completata", f"in {duration(time.monotonic() - start)}")


with tempfile.TemporaryDirectory() as tmp:
    f16_path = Path(tmp) / "model-f16.gguf"
    step("conversione GGUF F16", str(a.merged),
         [sys.executable, str(convert_script), str(a.merged), "--outfile", str(f16_path), "--outtype", "f16"])

    if a.quantize.lower() == "none":
        shutil.move(str(f16_path), a.output)
    else:
        imatrix = []
        if a.imatrix_rows:
            calib, imatrix_path = Path(tmp) / "calibration.txt", Path(tmp) / "imatrix.gguf"
            write_calibration(calib)
            step("calcolo imatrix", f"{a.imatrix_rows} righe del dataset di training",
                 [find_bin("llama-imatrix"), "-m", str(f16_path), "-f", str(calib), "-o", str(imatrix_path), "-c", "512"])
            imatrix = ["--imatrix", str(imatrix_path)]
        step("quantizzazione", a.quantize + (" con imatrix" if imatrix else ""),
             [find_bin("llama-quantize"), *imatrix, str(f16_path), str(a.output), a.quantize])

phase("fatto", f"{a.output} ({a.output.stat().st_size / 2**20:.1f} MiB)")
