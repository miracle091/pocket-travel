#!/usr/bin/env python3
"""Controllo della VRAM gia' occupata da altri processi prima di usare una GPU (train_auto.py,
eval_behavior.py, eval_gguf.py e convert_gguf.py con --gpu-layers): se ne tengono troppa il lavoro
finisce in memoria condivisa (Windows, molte volte piu' lento) o va in out of memory (Linux).

- Windows: contatori "GPU Process Memory" per processo. PyTorch non vede la memoria degli altri
  processi (mem_get_info da' la GPU quasi libera) e il driver ROCm puo' trattenere quella di processi
  gia' terminati (visto: un training finito e rimasto appeso in uscita, 5,8 GB).
- Linux e WSL: memoria libera riportata dal runtime (torch.cuda.mem_get_info, llama-server
  --list-devices), che li' conta anche gli altri processi; nvidia-smi, se c'e', per dire quali.
"""
import os
import platform
import re
import shutil
import subprocess
import sys

DEFAULT_MAX_HELD_PCT = 10
HELP = "%% massima di VRAM gia' occupata da altri processi per partire (default %(default)s)"


def held_processes():
    """[(pid, GiB, nome)] degli altri processi che occupano memoria dedicata delle GPU (oltre 100 MiB).
    Nome vuoto = processo terminato con memoria ancora trattenuta dal driver (solo Windows)."""
    if platform.system() == "Windows":
        # Somma su tutte le GPU: la grafica integrata ha poca memoria dedicata
        ps = ("(Get-Counter '\\GPU Process Memory(*)\\Dedicated Usage' -ErrorAction SilentlyContinue).CounterSamples"
              " | Where-Object { $_.CookedValue -gt 100MB } | ForEach-Object {"
              " $id = [int]($_.InstanceName -replace '^pid_(\\d+)_.*$', '$1');"
              " \"$id $([long]$_.CookedValue) $((Get-Process -Id $id -ErrorAction SilentlyContinue).ProcessName)\" }")
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True).stdout
        rows = [(int(pid), int(size) / 2**30, " ".join(name)) for pid, size, *name in map(str.split, out.splitlines())]
    elif shutil.which("nvidia-smi"):  # su WSL l'elenco dei processi di solito non e' disponibile: resta vuoto
        out = subprocess.run(["nvidia-smi", "--query-compute-apps=pid,used_memory,process_name",
                              "--format=csv,noheader,nounits"], capture_output=True, text=True).stdout
        rows = [(int(p), int(m) / 1024, n.strip()) for p, m, n in
                (l.split(",", 2) for l in out.splitlines() if re.match(r"\s*\d+,\s*\d+,", l))]
    else:
        rows = []
    # dwm (compositore del desktop) c'e' sempre sulla GPU collegata al monitor (tipico con una sola NVIDIA):
    # non si puo' chiudere, quindi non conta come VRAM "occupata da altri"
    return [r for r in rows if r[0] != os.getpid() and r[2] != "dwm"]


def check(total_gib, free_gib, max_pct, label="GPU"):
    """Esce con un messaggio se gli altri processi occupano piu' di max_pct% della VRAM (total_gib).
    free_gib (dal runtime) conta solo fuori da Windows, dove PyTorch/llama.cpp lo riportano giusto."""
    held = held_processes()
    used = sum(gib for _, gib, _ in held) if platform.system() == "Windows" else total_gib - free_gib
    pct = 100 * used / total_gib
    if pct <= max_pct:
        return
    sys.exit(f"VRAM ({label}) gia' occupata al {pct:.0f}% (limite {max_pct:.0f}%, --max-vram-held): "
             "il lavoro finirebbe in memoria condivisa o in out of memory.\n"
             + "".join(f"  PID {pid}: {gib:.1f} GiB, {name or 'processo terminato, memoria trattenuta dal driver'}\n"
                       for pid, gib, name in sorted(held, key=lambda h: -h[1]))
             + (WSL_HINT if "microsoft" in platform.release().lower() else
                "Chiudi quei processi; per quelli terminati va riavviato il driver della GPU (Windows: Gestione "
                "dispositivi, disabilita e riabilita la scheda, o pnputil /restart-device da amministratore) o il PC."))


# Su WSL la memoria usata dai programmi di Windows (desktop, browser) conta come occupata ma quei processi
# non compaiono in nvidia-smi: con la GPU collegata al monitor il solo desktop supera il 10% (visto 16% su 6 GB)
WSL_HINT = ("Su WSL la VRAM usata da Windows (desktop, browser, giochi) non compare tra i processi: se la GPU e' "
            "anche quella del monitor alza il limite (es. --max-vram-held 25) o chiudi i programmi di Windows che la usano.")


def check_torch(max_pct):
    """check() per la GPU corrente di PyTorch (dopo CUDA_/HIP_VISIBLE_DEVICES)."""
    import torch
    free, total = torch.cuda.mem_get_info()
    check(total / 2**30, free / 2**30, max_pct, torch.cuda.get_device_name())


def check_llama(exe, env, device, max_pct):
    """check() per il device di llama.cpp (come in `llama-server --list-devices`; il primo se None)."""
    out = subprocess.run([exe, "--list-devices"], capture_output=True, text=True, env=env).stdout
    devices = re.findall(r"^\s*(\S+): (.+?) \((\d+) MiB, (\d+) MiB free\)", out, re.M)
    match = [d for d in devices if device in (None, d[0])]
    if not match:
        sys.exit(f"device {device or 'GPU'} non trovato tra quelli di llama.cpp: {', '.join(d[0] for d in devices) or 'nessuno'}")
    name, desc, total, free = match[0]
    check(int(total) / 1024, int(free) / 1024, max_pct, f"{name} ({desc})")
