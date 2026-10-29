#!/usr/bin/env python3
"""Lucchetto della GPU discreta condiviso da tutti i processi del PC: un solo lavoro alla volta sulla GPU.

Due lavori insieme sulla stessa GPU (un training e un llama-server di un'altra sessione) riempiono la VRAM:
con ROCm su Windows il lavoro finisce in memoria condivisa, molte volte piu' lento, e il PC puo' bloccarsi
del tutto (schermi spenti, nessun dump), come con un training DPO e un llama-server da 9,1 GB sulla stessa GPU.

Il lucchetto e' un blocco del sistema operativo su ~/.cache/pocket-travel/gpu.lock (msvcrt su Windows,
flock su Linux): lo libera il sistema quando il processo finisce, anche se muore o il PC si blocca, quindi
non resta mai appeso. Chi lo tiene e' scritto in gpu-lock.json, accanto, per i messaggi di attesa e per
l'hook session-guard. Un processo che non lo trova libero aspetta e lo dice ogni 10 minuti.

Vale anche tra WSL e Windows: un lavoro in WSL prende anche il lucchetto di Windows (%USERPROFILE%\\.cache\\
pocket-travel\\gpu.lock) con un PowerShell figlio (powershell.exe, o pwsh.exe 7+ se manca), che blocca lo stesso
byte di msvcrt (LockFile), scrive il gpu-lock.json di Windows con il proprio PID (quello che l'hook session-guard
controlla) e lo tiene finche' il suo input resta aperto: quando il processo di WSL esce o muore, l'input si chiude e
il blocco si scioglie. Senza nessuno dei due (interop di WSL spenta) resta il solo lucchetto di Linux, con un avviso.

I figli di chi lo tiene (train_auto.py -> train_lora.py -> run_eval.py, eval_gguf.py -> llama-server)
ereditano POCKET_TRAVEL_GPU_LOCK e non lo riprendono.

Negli script della pipeline: gpu_job("nome", gpu=True) dopo gli argomenti, prima di toccare la GPU (prende il
lucchetto e accende il monitor di hw_monitor.py fino all'uscita del processo); negli script con torch, subito
dopo pick_discrete_gpu() e solo poi l'import di torch.
Da riga di comando, per un comando qualsiasi che usa la GPU (llama-server, script di prova):
  python gpu_lock.py run -- <comando> [argomenti]
  python gpu_lock.py status
Variabili per i test: POCKET_TRAVEL_GPU_LOCK_FILE (percorso del lucchetto).
"""
import atexit
import json
import os
import platform
import shutil
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


def holder(path=None):
    """Chi tiene il lucchetto secondo gpu-lock.json (dict), o None; puo' essere vecchio se il lucchetto e' libero."""
    try:
        return json.loads((path or info_path()).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


# Lucchetto di Windows tenuto da WSL. Righe in uscita: "PATH <gpu.lock>", "PID <pid>", poi "WAIT" a ogni
# messaggio di attesa (subito e ogni NOTICE_EVERY secondi), "LOCKED" quando lo tiene, "BUSY" in modalita' try.
WINDOWS_HELPER = r"""
$p = Join-Path $env:USERPROFILE '.cache\pocket-travel\gpu.lock'
New-Item -ItemType Directory -Force (Split-Path $p) | Out-Null
$f = [IO.File]::Open($p, 'OpenOrCreate', 'ReadWrite', 'ReadWrite')
if ($f.Length -eq 0) { $f.WriteByte(0); $f.Flush() }
"PATH $p"; "PID $PID"
$n = 0
while ($true) {
    try { $f.Lock(0, 1); break } catch { }
    if ('MODE' -eq 'try') { 'BUSY'; exit }
    if ($n % (NOTICE / 5) -eq 0) { 'WAIT' }
    $n++; Start-Sleep 5
}
'LOCKED'
if ('MODE' -eq 'try') { exit }
[Console]::In.ReadToEnd() | Out-Null
$f.Close()
"""


def in_wsl():
    return "microsoft" in platform.release().lower()


def windows_helper(mode):
    """PowerShell di Windows con WINDOWS_HELPER ("wait" o "try"): powershell.exe (5.1, incluso in Windows) o, se manca,
    pwsh.exe (PowerShell 7 o successivo). None fuori da WSL, senza interop o nei test (POCKET_TRAVEL_GPU_LOCK_FILE: il
    lucchetto dei test non e' quello di Windows)."""
    exe = shutil.which("powershell.exe") or shutil.which("pwsh.exe")
    if not in_wsl() or os.environ.get("POCKET_TRAVEL_GPU_LOCK_FILE") or not exe:
        return None
    script = WINDOWS_HELPER.replace("MODE", mode).replace("NOTICE", str(NOTICE_EVERY))
    return subprocess.Popen([exe, "-NoProfile", "-NonInteractive", "-Command", script], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")


def read_header(proc):
    """(percorso di gpu-lock.json di Windows visto da WSL, PID di PowerShell) dalle prime due righe."""
    win_lock = proc.stdout.readline().strip().removeprefix("PATH ")
    pid = int(proc.stdout.readline().strip().removeprefix("PID ") or 0)
    linux_lock = subprocess.run(["wslpath", "-u", win_lock], capture_output=True, text=True).stdout.strip()
    return Path(linux_lock).with_name("gpu-lock.json"), pid


def write_info(path, info):
    tmp = path.with_suffix(f".{os.getpid()}.tmp")
    tmp.write_text(json.dumps(info), encoding="utf-8")
    os.replace(tmp, path)


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
        self.fd = self.win = None

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
        write_info(info_path(), info)
        win_waited = self.acquire_windows(info)
        waited = win_waited if waited is None else waited
        os.environ[HELD_ENV] = str(os.getpid())
        if waited is not None:
            print(f"[gpu] GPU libera: {self.name} parte dopo {int((self.clock() - waited) // 60)} min di attesa",
                  file=self.out, flush=True)
        return self

    def acquire_windows(self, info):
        """Da WSL, anche il lucchetto di Windows (WINDOWS_HELPER); ritorna l'istante d'inizio dell'attesa, o None."""
        self.win = windows_helper("wait")
        if self.win is None:
            if in_wsl() and not os.environ.get("POCKET_TRAVEL_GPU_LOCK_FILE"):
                print("[gpu] ne' powershell.exe ne' pwsh.exe trovati (interop di WSL spenta?): solo il lucchetto di WSL, "
                      "i lavori di Windows sulla stessa GPU non lo vedono", file=self.out, flush=True)
            return None
        self.win_info, self.win_pid = read_header(self.win)
        waited = None
        for line in self.win.stdout:
            if line.strip() == "LOCKED":
                break
            if line.strip() == "WAIT":
                waited = self.clock() if waited is None else waited
                print(f"[gpu] GPU occupata su Windows da {describe(holder(self.win_info))}: {self.name} aspetta che si "
                      f"liberi (in attesa da {int((self.clock() - waited) // 60)} min)", file=self.out, flush=True)
        else:
            code = self.win.wait()
            self.release()
            sys.exit(f"[gpu] PowerShell uscito senza il lucchetto di Windows (codice {code})")
        write_info(self.win_info, {**info, "pid": self.win_pid, "wsl_pid": os.getpid()})
        return waited

    def release(self):
        if self.fd is None:
            return
        if (holder() or {}).get("pid") == os.getpid():
            info_path().unlink(missing_ok=True)
        if self.win:
            if (holder(self.win_info) or {}).get("pid") == self.win_pid:
                self.win_info.unlink(missing_ok=True)
            self.win.stdin.close()  # PowerShell esce e il blocco di Windows si scioglie
            self.win.wait(timeout=30)
            self.win.stdout.close()
            self.win = None
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


def pick_discrete_gpu(tag):
    """Rende visibile a torch solo la GPU con piu' unita' di calcolo (multi_processor_count, non la memoria: la grafica
    integrata dichiara come sua la RAM di sistema). Va chiamata dopo gpu_job e prima di importare torch o Unsloth: con
    ROCm la grafica integrata compare spesso come device 0 e non esegue i kernel (hipErrorInvalidImage, 0xC0000005 su
    Windows); sceglierla dopo con set_device non basta, generate() crea comunque tensori sul device 0.
    La scelta la fa un processo a parte; HIP_VISIBLE_DEVICES o CUDA_VISIBLE_DEVICES gia' impostati (train_auto.py)
    vengono rispettati, e con torch gia' importato non cambia nulla. tag apre il messaggio d'errore."""
    if "torch" in sys.modules or any(v in os.environ for v in ("HIP_VISIBLE_DEVICES", "CUDA_VISIBLE_DEVICES")):
        return
    probe = ("import torch\n"
             "n = torch.cuda.device_count() if torch.cuda.is_available() else 0\n"
             "print(max(range(n), key=lambda i: torch.cuda.get_device_properties(i).multi_processor_count) if n else -1)")
    try:
        best = int(subprocess.run([sys.executable, "-c", probe], capture_output=True, text=True, timeout=300).stdout.strip())
    except (ValueError, subprocess.SubprocessError) as e:
        print(f"[{tag}] scelta della GPU non riuscita ({e}): imposta HIP_VISIBLE_DEVICES/CUDA_VISIBLE_DEVICES", file=sys.stderr)
        return
    if best >= 0:
        os.environ["HIP_VISIBLE_DEVICES"] = os.environ["CUDA_VISIBLE_DEVICES"] = str(best)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] == ["status"]:
        win = windows_helper("try")
        if win:  # da WSL conta il lucchetto di Windows, preso da tutti i lavori del PC
            win_info, _ = read_header(win)
            busy = win.stdout.readline().strip() == "BUSY"
            win.stdin.close()
            win.wait()
            win.stdout.close()
            print(f"GPU occupata da {describe(holder(win_info))}" if busy else "GPU libera")
            return 0
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
