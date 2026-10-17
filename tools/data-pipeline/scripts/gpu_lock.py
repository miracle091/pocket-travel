#!/usr/bin/env python3
"""Lucchetto della GPU discreta condiviso da tutti i processi del PC: un solo lavoro alla volta sulla GPU.

Due lavori insieme sulla stessa GPU (un training e un llama-server di un'altra sessione) riempiono la VRAM:
con ROCm su Windows il lavoro finisce in memoria condivisa, molte volte piu' lento, e il 2026-10-17 il PC
si e' bloccato del tutto (schermi spenti, nessun dump) mentre una sessione addestrava con DPO e un'altra
teneva sulla stessa GPU un llama-server da 9,1 GB.

Il lucchetto e' un blocco del sistema operativo su ~/.cache/pocket-travel/gpu.lock (msvcrt su Windows,
flock su Linux): lo libera il sistema quando il processo finisce, anche se muore o il PC si blocca, quindi
non resta mai appeso. Chi lo tiene e' scritto in gpu-lock.json, accanto, per i messaggi di attesa e per
l'hook session-guard. Un processo che non lo trova libero aspetta e lo dice ogni 10 minuti.

Vale tra i processi dello stesso sistema: un lavoro in WSL e uno di Windows sulla stessa GPU non si vedono (il
blocco di Linux e quello di Windows non si parlano), quindi su un PC si lanciano tutti da Windows o tutti da WSL.

I figli di chi lo tiene (train_auto.py -> train_lora.py -> run_eval.py, eval_gguf.py -> llama-server)
ereditano POCKET_TRAVEL_GPU_LOCK e non lo riprendono.

Negli script della pipeline: gpu_job("nome", gpu=True) dopo gli argomenti, prima di toccare la GPU (prende il
lucchetto e accende il monitor di hw_monitor.py fino all'uscita del processo).
Da riga di comando, per un comando qualsiasi che usa la GPU (llama-server, script di prova):
  python gpu_lock.py run -- <comando> [argomenti]
  python gpu_lock.py status
Variabili per i test: POCKET_TRAVEL_GPU_LOCK_FILE (percorso del lucchetto).
"""
import atexit
import json
import os
import subprocess
import sys
import time
from pathlib import Path

import hw_monitor

HELD_ENV = "POCKET_TRAVEL_GPU_LOCK"
NOTICE_EVERY = 600  # secondi tra un messaggio di attesa e il successivo


def lock_path():
    return Path(os.environ.get("POCKET_TRAVEL_GPU_LOCK_FILE")
                or Path.home() / ".cache" / "pocket-travel" / "gpu.lock")


def info_path():
    return lock_path().with_name(lock_path().stem + "-lock.json")


def _try_lock(fd):
    """Blocco esclusivo non bloccante sul primo byte; False se lo tiene un altro processo."""
    try:
        if os.name == "nt":
            import msvcrt
            os.lseek(fd, 0, os.SEEK_SET)
            msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return True
    except OSError:
        return False


def holder():
    """Chi tiene il lucchetto secondo gpu-lock.json (dict), o None; puo' essere vecchio se il lucchetto e' libero."""
    try:
        return json.loads(info_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def describe(info, now=None):
    if not info:
        return "un altro processo"
    minutes = int(((now or time.time()) - info.get("since", 0)) // 60)
    session = f", sessione {info['session'][:8]}" if info.get("session") else ""
    return f"{info.get('name', '?')} (PID {info.get('pid', '?')}{session}, da {minutes} min)"


class GpuLock:
    """Tiene il lucchetto finche' l'oggetto vive (o fino a release()); acquire() aspetta se e' occupato."""

    def __init__(self, name, out=sys.stderr, sleep=time.sleep, clock=time.monotonic):
        self.name, self.out, self.sleep, self.clock = name, out, sleep, clock
        self.fd = None

    def acquire(self, poll=5):
        path = lock_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        self.fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
        if os.fstat(self.fd).st_size == 0:
            os.write(self.fd, b"\0")  # msvcrt blocca byte esistenti
        waited, next_notice = None, 0.0
        while not _try_lock(self.fd):
            now = self.clock()
            if waited is None:
                waited = now
            if now >= next_notice:
                print(f"[gpu] GPU occupata da {describe(holder())}: {self.name} aspetta che si liberi "
                      f"(in attesa da {int((now - waited) // 60)} min)", file=self.out, flush=True)
                next_notice = now + NOTICE_EVERY
            self.sleep(poll)
        info = {"name": self.name, "pid": os.getpid(), "since": time.time(),
                "session": os.environ.get("CLAUDE_CODE_SESSION_ID", ""), "command": " ".join(sys.argv)[:300]}
        tmp = info_path().with_suffix(f".{os.getpid()}.tmp")
        tmp.write_text(json.dumps(info), encoding="utf-8")
        os.replace(tmp, info_path())
        os.environ[HELD_ENV] = str(os.getpid())
        if waited is not None:
            print(f"[gpu] GPU libera: {self.name} parte dopo {int((self.clock() - waited) // 60)} min di attesa",
                  file=self.out, flush=True)
        return self

    def release(self):
        if self.fd is None:
            return
        if (holder() or {}).get("pid") == os.getpid():
            info_path().unlink(missing_ok=True)
        os.close(self.fd)  # chiudere il file scioglie il blocco
        self.fd = None
        if os.environ.get(HELD_ENV) == str(os.getpid()):
            del os.environ[HELD_ENV]


def gpu_job(name, gpu=True):
    """Lucchetto (se gpu e se un processo padre non lo tiene gia') e monitor fino all'uscita del processo."""
    if gpu and not os.environ.get(HELD_ENV):
        lock = GpuLock(name).acquire()
        atexit.register(lock.release)
    hw_monitor.start(name)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] == ["status"]:
        path = lock_path()
        if not path.exists():
            print("GPU libera")
            return 0
        fd = os.open(path, os.O_RDWR)
        try:
            free = _try_lock(fd)
        finally:
            os.close(fd)  # se era libero, chiudere lo scioglie subito
        print("GPU libera" if free else f"GPU occupata da {describe(holder())}")
        return 0
    if argv[:1] == ["run"] and "--" in argv and argv.index("--") + 1 < len(argv):
        cmd = argv[argv.index("--") + 1:]
        gpu_job(Path(cmd[0]).name)
        return subprocess.call(cmd)
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
