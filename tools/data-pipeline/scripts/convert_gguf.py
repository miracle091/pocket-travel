#!/usr/bin/env python3
"""Converte un Qwen2.5/Qwen3 fine-tunato (cartella merged) in GGUF quantizzato con gli strumenti di llama.cpp.

Uso (solo Linux/WSL, richiede un clone separato di llama.cpp allo stesso tag vendorizzato in
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
turno assistente (a differenza del vecchio export LiteRT-LM, che lo forzava). Non e' un problema di questo
script: se serve parita' di comportamento con il training per i modelli Qwen3, va risolto lato ai_chat.cpp.
"""
import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("merged", type=Path)
ap.add_argument("output", type=Path)
ap.add_argument("--llama-cpp", type=Path, required=True, help="cartella del clone locale di llama.cpp (tag v0.4.1)")
ap.add_argument("--quantize", default="Q4_K_M", help='tipo llama-quantize (es. Q4_K_M, Q8_0), "none" per tenere il f16')
a = ap.parse_args()

convert_script = a.llama_cpp / "convert_hf_to_gguf.py"
if not convert_script.exists():
    sys.exit(f"convert_hf_to_gguf.py non trovato in {a.llama_cpp} (e' un clone di llama.cpp aggiornato al tag v0.4.1?)")

with tempfile.TemporaryDirectory() as tmp:
    f16_path = Path(tmp) / "model-f16.gguf"
    subprocess.run(
        [sys.executable, str(convert_script), str(a.merged), "--outfile", str(f16_path), "--outtype", "f16"],
        check=True,
    )

    if a.quantize.lower() == "none":
        shutil.move(str(f16_path), a.output)
    else:
        quantize_bin = shutil.which("llama-quantize") or next(
            (str(p) for p in (a.llama_cpp / "build" / "bin" / "llama-quantize",
                               a.llama_cpp / "build" / "llama-quantize") if p.exists()),
            None,
        )
        if not quantize_bin:
            sys.exit(
                f"llama-quantize non trovato (ne' nel PATH ne' in {a.llama_cpp / 'build'}): compilalo con "
                "`cmake -B build && cmake --build build --target llama-quantize` nel clone di llama.cpp",
            )
        subprocess.run([quantize_bin, str(f16_path), str(a.output), a.quantize], check=True)

print("scritto in", a.output, f"({a.output.stat().st_size / 2**20:.1f} MiB)")
