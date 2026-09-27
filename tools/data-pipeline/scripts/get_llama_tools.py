#!/usr/bin/env python3
"""Scarica i binari ufficiali di llama.cpp per Windows x64 (llama-server, llama-imatrix, llama-quantize,
llama-completion) per il backend scelto, alla build fissata LLAMA_BUILD: stessa serie della v0.4.1
dell'app e del clone di llama.cpp da cui convert_gguf.py usa convert_hf_to_gguf.py (build 11030; la
release b11030 non ha i binari Windows, la b11035 e' la prima successiva che li ha).

Backend:
  cpu         qualunque CPU x64
  vulkan      GPU AMD, NVIDIA o Intel con driver Vulkan (nessun SDK da installare)
  rocm        GPU AMD Radeon (HIP): il pacchetto non ha hipblas/rocblas, che si prendono dall'HIP SDK
              di AMD (HIP_PATH) o dal ROCm dei wheel PyTorch (_rocm_sdk_* del Python che lancia lo
              script, es. quello di Unsloth Studio); le cartelle finiscono in extra-path.txt, che
              eval_gguf.py e convert_gguf.py aggiungono al PATH (llama_env in eval_common.py)
  cuda-12.4   GPU NVIDIA, driver recenti o meno (scarica anche le DLL del runtime CUDA)
  cuda-13.4   GPU NVIDIA, driver recenti
Non disponibili: OpenCL (llama.cpp lo pubblica solo per Adreno ARM64) e OpenGL (llama.cpp non ha un
backend OpenGL).

Con una GPU, eval_gguf.py e convert_gguf.py (imatrix) vanno lanciati con --gpu-layers 99; non mentre
un training occupa la stessa GPU.

Uso: python get_llama_tools.py <backend> [--dest CARTELLA]
Stampa la cartella dei binari, da passare a eval_gguf.py --llama-cpp / convert_gguf.py --bin-dir.
"""
import argparse
import glob
import io
import os
import site
import sys
import urllib.request
import zipfile
from pathlib import Path

LLAMA_BUILD = "b11035"
RELEASE = f"https://github.com/ggml-org/llama.cpp/releases/download/{LLAMA_BUILD}"


def download(url, dest):
    print(f"-- scarico {url}", file=sys.stderr)
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "PocketTravelDataPipeline"})) as r:
        zipfile.ZipFile(io.BytesIO(r.read())).extractall(dest)


def rocm_lib_dirs():
    """Cartelle con hipblas.dll/rocblas.dll: HIP SDK di AMD, altrimenti ROCm dei wheel PyTorch."""
    if os.environ.get("HIP_PATH"):
        return [str(Path(os.environ["HIP_PATH"]) / "bin")]
    return [d for sp in site.getsitepackages()
            for d in glob.glob(f"{sp}/_rocm_sdk_libraries_*/bin") + glob.glob(f"{sp}/_rocm_sdk_core/bin")]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("backend", choices=("cpu", "vulkan", "rocm", "cuda-12.4", "cuda-13.4"))
    ap.add_argument("--dest", type=Path, default=Path(r"D:\pocket-travel-train\llama-tools"),
                    help="cartella base: i binari finiscono in <dest>/<build>-<backend>")
    a = ap.parse_args()
    asset = "rocm-10.0" if a.backend == "rocm" else a.backend  # nome del pacchetto HIP nella release
    dest = a.dest / f"{LLAMA_BUILD}-{a.backend}"
    if not (dest / "llama-server.exe").exists():
        download(f"{RELEASE}/llama-{LLAMA_BUILD}-bin-win-{asset}-x64.zip", dest)
        if a.backend.startswith("cuda"):
            download(f"{RELEASE}/cudart-llama-bin-win-{a.backend}-x64.zip", dest)
    if a.backend == "rocm":
        dirs = rocm_lib_dirs()
        if not any(Path(d, "hipblas.dll").exists() for d in dirs):
            sys.exit("hipblas.dll non trovata: installa l'HIP SDK di AMD o lancia lo script con un Python che ha "
                     "PyTorch per ROCm (wheel _rocm_sdk_*)")
        (dest / "extra-path.txt").write_text("\n".join(dirs), encoding="utf-8")
    missing = [n for n in ("llama-server", "llama-imatrix", "llama-quantize", "llama-completion")
               if not (dest / f"{n}.exe").exists()]
    if missing:
        sys.exit(f"{dest}: mancano {', '.join(missing)}")
    print(dest)


if __name__ == "__main__":
    main()
