#!/usr/bin/env python3
"""Lancia train_lora.py adattandosi all'hardware presente (auto-switching).

Uso: python train_auto.py [--dry-run] [opzioni di train_lora.py, es. --max-steps 30 --merge]
Va eseguito col Python di un venv che abbia PyTorch (CUDA o ROCm).

Cosa decide, ad ogni lancio:
- GPU: la discreta con piu' VRAM (l'iGPU AMD compare come device 0 e va evitata);
  nessuna GPU -> si ferma (il training su CPU non ha senso a queste dimensioni);
- backend: --unsloth se Unsloth e' installato e, su Windows, il compilatore MSVC e' disponibile
  (Triton compila con clang-cl e gli servono INCLUDE/LIB); altrimenti peft puro;
- variabili d'ambiente per il figlio: CUDA_VISIBLE_DEVICES / HIP_VISIBLE_DEVICES, ambiente MSVC.
Non passa --4bit da solo: sceglilo tu (solo NVIDIA).
"""
import argparse
import importlib.util
import json
import os
import platform
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Eseguito in un processo a parte: cosi' torch non inizializza la GPU nel launcher e
# le variabili *_VISIBLE_DEVICES impostate dopo hanno effetto sul figlio.
PROBE = """
import json, torch
gpus = []
for i in range(torch.cuda.device_count()):
    p = torch.cuda.get_device_properties(i)
    integrated = bool(getattr(p, "is_integrated", False)) or "Graphics" in p.name
    gpus.append({"index": i, "name": p.name, "vram_gib": round(p.total_memory / 2**30, 1), "integrated": integrated})
print(json.dumps({"cuda": torch.version.cuda, "hip": getattr(torch.version, "hip", None), "gpus": gpus}))
"""


def probe():
    out = subprocess.run([sys.executable, "-c", PROBE], capture_output=True, text=True)
    lines = [l for l in out.stdout.splitlines() if l.startswith("{")]
    if out.returncode != 0 or not lines:
        sys.exit(f"Impossibile interrogare PyTorch:\n{out.stderr[-600:]}")
    return json.loads(lines[-1])


def msvc_env():
    """Variabili dell'ambiente MSVC (vcvars64) su Windows, o None se non installato."""
    pf = os.environ.get("ProgramFiles(x86)") or r"C:\Program Files (x86)"  # bash non esporta "(x86)"
    vswhere = Path(pf) / "Microsoft Visual Studio" / "Installer" / "vswhere.exe"
    if not vswhere.exists():
        return None
    root = subprocess.run([str(vswhere), "-products", "*", "-property", "installationPath"],
                          capture_output=True, text=True).stdout.strip().splitlines()
    vcvars = Path(root[0]) / "VC" / "Auxiliary" / "Build" / "vcvars64.bat" if root else None
    if not vcvars or not vcvars.exists():
        return None
    env = dict(os.environ)
    env["PATH"] += os.pathsep + str(vswhere.parent)  # vcvars cerca vswhere.exe
    # Stringa unica (non lista): list2cmdline escaperebbe le virgolette in \" che cmd non capisce
    dump = subprocess.run(f'cmd /d /s /c ""{vcvars}" >nul && set"', capture_output=True, text=True, env=env)
    return dict(l.split("=", 1) for l in dump.stdout.splitlines() if "=" in l) or None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true", help="mostra le scelte e il comando senza lanciare")
    ap.add_argument("--gpu", type=int, help="forza l'indice della GPU invece della scelta automatica")
    a, passthrough = ap.parse_known_args()

    info = probe()
    if not info["gpus"]:
        sys.exit("Nessuna GPU visibile a PyTorch: niente training (installa PyTorch CUDA/ROCm o controlla i driver).")
    vendor = "NVIDIA" if info["cuda"] else "AMD"
    candidates = [g for g in info["gpus"] if not g["integrated"]] or info["gpus"]
    gpu = next((g for g in info["gpus"] if g["index"] == a.gpu), None) if a.gpu is not None \
        else max(candidates, key=lambda g: g["vram_gib"])
    if gpu is None:
        sys.exit(f"GPU {a.gpu} non trovata tra: {[g['index'] for g in info['gpus']]}")

    env = dict(os.environ)
    env["CUDA_VISIBLE_DEVICES" if vendor == "NVIDIA" else "HIP_VISIBLE_DEVICES"] = str(gpu["index"])
    env["PYTHONIOENCODING"] = "utf-8"

    use_unsloth, why = importlib.util.find_spec("unsloth") is not None, "Unsloth installato"
    if not use_unsloth:
        why = "Unsloth non installato"
    elif platform.system() == "Windows":
        vc = msvc_env()
        if vc:
            env.update(vc)
        else:
            use_unsloth, why = False, "MSVC Build Tools non trovati (servono a Triton su Windows)"

    flags = ["--unsloth"] if use_unsloth else []
    cmd = [sys.executable, str(HERE / "train_lora.py"), *flags, *passthrough]
    print(f"Hardware: {vendor}, GPU {gpu['index']} = {gpu['name']} ({gpu['vram_gib']} GiB)")
    print(f"Backend: {'Unsloth' if use_unsloth else 'peft puro'} ({why})")
    print("Comando:", " ".join(cmd))
    if not a.dry_run:
        sys.exit(subprocess.call(cmd, env=env))


if __name__ == "__main__":
    main()
