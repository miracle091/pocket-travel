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
Non passa --4bit da solo: va scelto a mano (solo NVIDIA).

Watchdog (--no-watchdog per spegnerlo): legge le righe "[training] N/T" di train_lora.py e lo ferma quando la velocita'
degli ultimi 15 minuti e' piu' del doppio piu' lenta della migliore dello stesso processo, o quando nessun passo avanza
per 30 minuti durante il training; poi lo rilancia con lo stesso comando, e train_lora.py riprende dall'ultimo
checkpoint (ogni 100 passi). Al massimo 6 riavvii. Su Windows con ROCm il driver a volte rallenta un training fino a
3 volte (2026-10-15: 9,5 s/passo, 4,4 dopo il riavvio sugli stessi batch) e il riavvio lo riporta alla velocita' giusta.
"""
import argparse
import importlib.util
import json
import os
import platform
import queue
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

import vram

HERE = Path(__file__).resolve().parent

# Eseguito in un processo a parte: cosi' torch non inizializza la GPU nel launcher e
# le variabili *_VISIBLE_DEVICES impostate dopo hanno effetto sul figlio.
PROBE = """
import json, torch
gpus = []
for i in range(torch.cuda.device_count()):
    p = torch.cuda.get_device_properties(i)
    integrated = bool(getattr(p, "is_integrated", False)) or "Graphics" in p.name
    free = torch.cuda.mem_get_info(i)[0]  # giusto su Linux/WSL, non su Windows (vedi vram.py)
    gpus.append({"index": i, "name": p.name, "vram_gib": round(p.total_memory / 2**30, 1),
                 "free_gib": free / 2**30, "integrated": integrated})
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


TRAINING_LINE = re.compile(rb"^\[training\] (\d+)/(\d+)")
PHASE_LINE = re.compile(rb"^\[fase\] ")


class SpeedWatchdog:
    """Decide quando fermare un training lento: velocita' sulla finestra degli ultimi `window` secondi piu' lenta di
    `factor` volte la migliore vista, o nessun passo per `stall` secondi. I tempi arrivano da fuori (testabile)."""

    def __init__(self, window=900, factor=2.0, stall=1800):
        self.window, self.factor, self.stall = window, factor, stall
        self.samples, self.best, self.training = [], None, False

    def step(self, now, step):
        """Una riga "[training] step/..." letta all'istante `now`."""
        self.training = True
        if not self.samples or step != self.samples[-1][1]:
            self.samples.append((now, step))

    def phase(self):
        """Una riga "[fase] ...": il training e' finito o non e' ancora cominciato (merge, eval, caricamento)."""
        self.samples, self.training = [], False

    def check(self, now):
        """Motivo per fermare il training, o None."""
        if not self.training or not self.samples:
            return None
        last_time, last_step = self.samples[-1]
        if now - last_time >= self.stall:
            return f"nessun passo in {int((now - last_time) // 60)} min"
        old = [s for s in self.samples if last_time - s[0] >= self.window]
        if not old:
            return None
        start_time, start_step = old[-1]
        self.samples = [s for s in self.samples if s[0] >= start_time]
        rate = (last_time - start_time) / (last_step - start_step)
        if self.best is None or rate < self.best:
            self.best = rate
        if rate > self.factor * self.best:
            return f"{rate:.1f} s/passo negli ultimi {self.window // 60} min contro un migliore di {self.best:.1f}"
        return None


def run_watched(cmd, env, max_restarts=6, watchdog=SpeedWatchdog, clock=time.monotonic, poll=30):
    """Lancia cmd, copia il suo output e lo rilancia (al piu' max_restarts volte) quando il watchdog lo ferma."""
    for attempt in range(max_restarts + 1):
        with subprocess.Popen(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as proc:
            lines = queue.Queue()
            threading.Thread(target=lambda: [lines.put(l) for l in iter(proc.stdout.readline, b"")] + [lines.put(None)],
                             daemon=True).start()
            dog, reason = watchdog(), None
            while True:
                try:
                    line = lines.get(timeout=poll)
                except queue.Empty:
                    line = b""
                if line is None:
                    return proc.wait()
                if line:
                    sys.stdout.buffer.write(line)
                    sys.stdout.flush()
                    if match := TRAINING_LINE.match(line):
                        dog.step(clock(), int(match.group(1)))
                    elif PHASE_LINE.match(line):
                        dog.phase()
                reason = dog.check(clock())
                if reason and attempt < max_restarts:
                    break
            print(f"-- watchdog: {reason}; fermo train_lora.py e lo rilancio dall'ultimo checkpoint "
                  f"(riavvio {attempt + 1} di {max_restarts})", flush=True)
            proc.kill()
        time.sleep(20)  # con ROCm la VRAM del processo fermato si libera dopo qualche secondo


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true", help="mostra le scelte e il comando senza lanciare")
    ap.add_argument("--gpu", type=int, help="forza l'indice della GPU invece della scelta automatica")
    ap.add_argument("--max-vram-held", type=float, default=vram.DEFAULT_MAX_HELD_PCT, help=vram.HELP)
    ap.add_argument("--no-watchdog", action="store_true", help="non fermare e rilanciare un training rallentato")
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

    vram.check(gpu["vram_gib"], gpu["free_gib"], a.max_vram_held, f"GPU {gpu['index']} ({gpu['name']})")

    env = dict(os.environ)
    # Gli indici della sonda contano solo le GPU gia' visibili: con la variabile impostata (es. HIP_VISIBLE_DEVICES=1
    # per escludere l'iGPU) si passa al figlio la GPU fisica della lista, non l'indice, che riporterebbe sull'iGPU.
    visible_var = "CUDA_VISIBLE_DEVICES" if vendor == "NVIDIA" else "HIP_VISIBLE_DEVICES"
    visible = os.environ.get(visible_var, "").split(",")
    env[visible_var] = visible[gpu["index"]].strip() if gpu["index"] < len(visible) and visible[0] else str(gpu["index"])
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
        sys.exit(subprocess.call(cmd, env=env) if a.no_watchdog else run_watched(cmd, env))


if __name__ == "__main__":
    main()
