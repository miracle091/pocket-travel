#!/usr/bin/env python3
"""Temperature, consumi e memoria di GPU e CPU durante training, valutazioni e conversioni.

Ogni 10 secondi un campione in un CSV (~/.cache/pocket-travel/hw/<lavoro>-<data>-<pid>.csv, scritto e
sincronizzato su disco a ogni riga: dopo un blocco del PC le ultime righe dicono com'era la macchina), ogni
minuto una riga "[hw]" sullo stderr del lavoro, quindi nel suo log, e un avviso "[hw] ATTENZIONE" quando un
valore passa la soglia (ALERTS). All'uscita, i massimi.

Fonti, senza dipendenze nuove; un valore che una fonte non da' resta vuoto:
- Windows, GPU AMD: ADL del driver (atiadlxx.dll) per temperatura del chip, punto piu' caldo, memoria, VRM
  (il piu' caldo dei regolatori), consumo della scheda, attivita', ventola (giri e %) e frequenza; contatori di
  Windows per la VRAM usata e per quella condivisa, cioe' la VRAM che trabocca nella RAM. Sui Ryzen con grafica
  integrata ADL da' anche il consumo del processore.
- Linux, GPU AMD: hwmon di amdgpu in /sys (temperature, consumo, ventola in giri e in % dal comando pwm1, VRAM,
  GTT = memoria traboccata); CPU da /proc e dalla temperatura di k10temp o coretemp.
- GPU NVIDIA, su tutti e due: nvidia-smi (temperatura, consumo, attivita', VRAM, frequenza, ventola in %, e i
  rallentamenti decisi dal driver per temperatura o consumo, con un avviso per quelli da temperatura).
- Windows e WSL, LibreHardwareMonitor di Windows con il web server acceso (Options -> Remote Web Server; da WSL
  con curl.exe di Windows, perche' il firewall di Windows blocca le connessioni da WSL; le variabili sotto, se
  mancano in WSL, si leggono da quelle di Windows, dell'utente o di sistema, nel registro), letto da
  POCKET_TRAVEL_LHM_URL (default http://127.0.0.1:8085), con l'autenticazione del server in POCKET_TRAVEL_LHM_USER
  e POCKET_TRAVEL_LHM_PASSWORD: temperatura della CPU (Windows non la da' ai programmi senza un driver), ventola
  della CPU e del case dal chip della scheda madre, e i valori della GPU che le fonti sopra non danno (con le
  NVIDIA il punto piu' caldo, la memoria e i giri di ogni ventola, da NvAPI; con le AMD i VRM). Le fonti sopra hanno
  la precedenza. Le ventole della GPU una per una arrivano solo da qui: nvidia-smi e NVML danno la velocita' voluta,
  uguale per tutte, e amdgpu e ADL una sola ventola; con piu' ventole un avviso dice quando una e' ferma e le altre
  girano. Temperatura dei VRM: solo AMD (ADL o LHM); amdgpu su Linux e i driver NVIDIA non la danno.

Uso:
  python hw_monitor.py                    # una riga ogni 5 secondi, fino a Ctrl+C
  python hw_monitor.py --once             # un campione
  python hw_monitor.py run -- <comando>   # monitor intorno a un comando (per la GPU: gpu_lock.py run)
Negli script: hw_monitor.start("nome") (gpu_lock.gpu_job lo chiama gia'). I figli di un processo monitorato
ereditano POCKET_TRAVEL_HW_MONITOR e non ne aprono un altro.
"""
import base64
import csv
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

HELD_ENV = "POCKET_TRAVEL_HW_MONITOR"
FIELDS = ["time", "gpu_c", "gpu_hotspot_c", "gpu_mem_c", "gpu_vrm_c", "gpu_w", "gpu_busy_pct", "gpu_fan_rpm",
          "gpu_fan_pct", "gpu_fans_rpm", "gpu_clock_mhz", "gpu_throttle", "vram_used_mb", "vram_shared_mb",
          "vram_total_mb", "cpu_pct", "cpu_w", "cpu_c", "cpu_fan_rpm", "case_fan_rpm", "ram_used_mb", "ram_total_mb",
          "gpu_jobs"]
USER_AGENT = "pocket-travel-hw_monitor/1 (https://github.com/miracle091/pocket-travel)"
JOB_VRAM = 2**30  # un processo con almeno 1 GiB di VRAM e' un lavoro sulla GPU (il desktop e i browser ne usano meno)


def gpu_jobs(vram_by_pid, names):
    """(numero, elenco leggibile) dei processi con almeno JOB_VRAM byte di VRAM; dwm, il compositore di Windows, non conta."""
    jobs = sorted(((pid, size) for pid, size in vram_by_pid.items()
                   if size >= JOB_VRAM and names.get(pid, "").lower() not in ("dwm", "dwm.exe")), key=lambda j: -j[1])
    text = ", ".join(f"{names.get(pid) or '?'} (PID {pid}, {_gb(size / 2**20)} GB)" for pid, size in jobs)
    return len(jobs), text


def _vram_spill(s):
    """VRAM piena e almeno 1 GB traboccato nella RAM (Windows: memoria condivisa; Linux: GTT)."""
    total, used, shared = s.get("vram_total_mb"), s.get("vram_used_mb"), s.get("vram_shared_mb")
    return bool(total and used and shared) and used >= 0.9 * total and shared >= 1024


# (chiave, condizione, messaggio). Soglie sotto i limiti tipici dei chip (GPU AMD e NVIDIA recenti: 105-110 °C al
# punto piu' caldo; CPU desktop: 95-100 °C), per accorgersene prima che il driver o il firmware rallentino tutto.
ALERTS = [
    ("gpu_hotspot_c", lambda s: (s.get("gpu_hotspot_c") or 0) >= 100, "punto piu' caldo della GPU a {gpu_hotspot_c} °C"),
    ("gpu_c", lambda s: (s.get("gpu_c") or 0) >= 90, "GPU a {gpu_c} °C"),
    ("gpu_mem_c", lambda s: (s.get("gpu_mem_c") or 0) >= 100, "memoria della GPU a {gpu_mem_c} °C"),
    # VRM: limiti tipici dei regolatori di tensione 110-125 °C
    ("gpu_vrm_c", lambda s: (s.get("gpu_vrm_c") or 0) >= 105, "VRM della GPU a {gpu_vrm_c} °C"),
    ("gpu_fans_rpm", lambda s: _fan_stopped(s), "una ventola della GPU e' ferma mentre le altre girano "
                                               "({gpu_fans_rpm} RPM): guasta o staccata"),
    ("cpu_c", lambda s: (s.get("cpu_c") or 0) >= 90, "CPU a {cpu_c} °C"),
    ("gpu_throttle", lambda s: "temperatura" in (s.get("gpu_throttle") or ""),
     "GPU rallentata dal driver per la temperatura ({gpu_c} °C): raffreddamento insufficiente o ventole ferme"),
    ("vram_spill", _vram_spill, "VRAM piena, {vram_shared_mb} MB traboccati nella RAM: il lavoro rallenta molto e "
                                "con due lavori sulla GPU il PC puo' bloccarsi (un altro processo usa la GPU?)"),
    ("ram", lambda s: bool(s.get("ram_total_mb")) and (s.get("ram_used_mb") or 0) >= 0.95 * s["ram_total_mb"],
     "RAM al 95% ({ram_used_mb} MB di {ram_total_mb})"),
    # Anche quando un lavoro e' partito senza il lucchetto di gpu_lock.py (comando a mano, script di prova)
    ("gpu_jobs", lambda s: (s.get("gpu_jobs") or 0) >= 2,
     "{gpu_jobs} lavori insieme sulla GPU: {gpu_job_list}. Con la VRAM piena il PC puo' bloccarsi: va fermato quello "
     "partito senza gpu_lock.py"),
]


# ---------------------------------------------------------------- Windows

class _Windows:
    def __init__(self):
        import ctypes as C
        self.C = C
        self.k32 = C.windll.kernel32
        self.last_times = None
        self.adl = self._adl()
        self.pdh = self._pdh()

    def _adl(self):
        C = self.C
        try:
            adl = C.WinDLL("atiadlxx.dll")
        except OSError:
            return None
        malloc = C.cdll.msvcrt.malloc
        malloc.restype = C.c_void_p
        self._malloc = C.WINFUNCTYPE(C.c_void_p, C.c_int)(lambda n: malloc(n))  # tenuto vivo: ADL lo richiama
        ctx = C.c_void_p()
        if adl.ADL2_Main_Control_Create(self._malloc, 1, C.byref(ctx)) != 0:
            return None

        class Info(C.Structure):
            _fields_ = [("size", C.c_int), ("index", C.c_int), ("udid", C.c_char * 256), ("bus", C.c_int),
                        ("device", C.c_int), ("function", C.c_int), ("vendor", C.c_int), ("name", C.c_char * 256),
                        ("display", C.c_char * 256), ("present", C.c_int), ("exist", C.c_int),
                        ("driver", C.c_char * 256), ("driver_ext", C.c_char * 256), ("pnp", C.c_char * 256),
                        ("os_display", C.c_int)]

        class Mem(C.Structure):
            _fields_ = [("size", C.c_longlong), ("type", C.c_char * 256), ("bandwidth", C.c_longlong),
                        ("hyper", C.c_longlong), ("invisible", C.c_longlong), ("visible", C.c_longlong)]

        n = C.c_int()
        adl.ADL2_Adapter_NumberOfAdapters_Get(ctx, C.byref(n))
        infos = (Info * n.value)()
        for i in infos:
            i.size = C.sizeof(Info)
        adl.ADL2_Adapter_AdapterInfo_Get(ctx, infos, C.sizeof(infos))
        self.adl_ctx, self.dgpu, self.apu, self.vram_total = ctx, None, None, None
        seen = set()
        for info in infos:  # una voce per ogni uscita video: una per scheda basta
            if info.bus in seen:
                continue
            seen.add(info.bus)
            sensors = self._pmlog(adl, info.index)
            if sensors is None:
                continue
            if 73 in sensors or 27 in sensors:  # consumo della scheda o punto piu' caldo: GPU discreta
                if self.dgpu is None:
                    self.dgpu = info.index
                    mem = Mem()
                    if adl.ADL2_Adapter_MemoryInfo2_Get(ctx, info.index, C.byref(mem)) == 0:
                        self.vram_total = round(mem.size / 2**20)
            elif 23 in sensors:  # grafica integrata dei Ryzen: consumo del processore
                self.apu = info.index
        return adl

    def _pmlog(self, adl, index):
        """{indice ADL_PMLOG_*: valore} dei sensori supportati, o None."""
        C = self.C

        class Sensor(C.Structure):
            _fields_ = [("supported", C.c_int), ("value", C.c_int)]

        class Log(C.Structure):
            _fields_ = [("size", C.c_int), ("sensors", Sensor * 256)]

        log = Log()
        log.size = C.sizeof(Log)
        if adl.ADL2_New_QueryPMLogData_Get(self.adl_ctx, index, C.byref(log)) != 0:
            return None
        return {i: s.value for i, s in enumerate(log.sensors) if s.supported}

    def _pdh(self):
        C = self.C
        try:
            pdh = C.WinDLL("pdh.dll")
        except OSError:
            return None
        query = C.c_void_p()
        if pdh.PdhOpenQueryW(None, 0, C.byref(query)) != 0:
            return None
        self.counters = {}
        for key, path in (("dedicated", r"\GPU Adapter Memory(*)\Dedicated Usage"),
                          ("shared", r"\GPU Adapter Memory(*)\Shared Usage"),
                          ("process", r"\GPU Process Memory(*)\Dedicated Usage")):
            counter = C.c_void_p()
            if pdh.PdhAddEnglishCounterW(query, path, 0, C.byref(counter)) == 0:
                self.counters[key] = counter
        self.query = query
        return pdh if self.counters else None

    def _pdh_values(self, counter):
        """{istanza (luid della scheda): byte}."""
        C = self.C

        class Item(C.Structure):
            _fields_ = [("name", C.c_wchar_p), ("status", C.c_ulong), ("value", C.c_longlong)]

        size, count = C.c_ulong(0), C.c_ulong(0)
        self.pdh.PdhGetFormattedCounterArrayW(counter, 0x400, C.byref(size), C.byref(count), None)  # PDH_FMT_LARGE
        if not size.value:
            return {}
        buf = (C.c_byte * size.value)()
        if self.pdh.PdhGetFormattedCounterArrayW(counter, 0x400, C.byref(size), C.byref(count), buf) != 0:
            return {}
        items = C.cast(buf, C.POINTER(Item * count.value)).contents
        return {i.name: i.value for i in items}

    def sample(self):
        s = {}
        if self.adl and self.dgpu is not None:
            g = self._pmlog(self.adl, self.dgpu) or {}
            # VRM: PMLOG_TEMPERATURE_VRVDDC, VRMVDD, VRSOC, VRMVDD0, VRMVDD1 (adl_defines.h), il piu' caldo
            vrm = [g[i] for i in (10, 11, 24, 25, 26) if g.get(i)]
            s.update(gpu_c=g.get(8), gpu_hotspot_c=g.get(27), gpu_mem_c=g.get(9), gpu_vrm_c=max(vrm, default=None),
                     gpu_w=g.get(73),
                     gpu_busy_pct=g.get(19), gpu_fan_rpm=g.get(14), gpu_fan_pct=g.get(15), gpu_clock_mhz=g.get(1),
                     vram_total_mb=self.vram_total)
        if self.adl and self.apu is not None:
            s["cpu_w"] = (self._pmlog(self.adl, self.apu) or {}).get(23)
        if self.pdh:
            self.pdh.PdhCollectQueryData(self.query)
            dedicated = self._pdh_values(self.counters["dedicated"])
            shared = self._pdh_values(self.counters.get("shared")) if "shared" in self.counters else {}
            if dedicated:
                # La GPU discreta e' quella con piu' memoria dedicata in uso (la grafica integrata ne ha poca)
                luid = max(dedicated, key=dedicated.get)
                s["vram_used_mb"] = round(dedicated[luid] / 2**20)
                if luid in shared:
                    s["vram_shared_mb"] = round(shared[luid] / 2**20)
            if "process" in self.counters:
                by_pid = {}
                for name, size in self._pdh_values(self.counters["process"]).items():  # istanze "pid_<pid>_luid_..."
                    if match := re.match(r"pid_(\d+)_", name or ""):
                        by_pid[int(match.group(1))] = by_pid.get(int(match.group(1)), 0) + size
                big = [pid for pid, size in by_pid.items() if size >= JOB_VRAM]
                s["gpu_jobs"], s["gpu_job_list"] = gpu_jobs(by_pid, {pid: self._process_name(pid) for pid in big})
        s.update(self._cpu_ram())
        return s

    def _process_name(self, pid):
        C = self.C
        handle = self.k32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return ""
        try:
            buf, size = C.create_unicode_buffer(1024), C.c_ulong(1024)
            if self.k32.QueryFullProcessImageNameW(handle, 0, buf, C.byref(size)):
                return Path(buf.value).stem
            return ""
        finally:
            self.k32.CloseHandle(handle)

    def _cpu_ram(self):
        C = self.C
        idle, kernel, user = C.c_ulonglong(), C.c_ulonglong(), C.c_ulonglong()
        out = {}
        if self.k32.GetSystemTimes(C.byref(idle), C.byref(kernel), C.byref(user)):
            now = (idle.value, kernel.value + user.value)  # il tempo kernel comprende quello inattivo
            if self.last_times:
                d_idle, d_total = now[0] - self.last_times[0], now[1] - self.last_times[1]
                if d_total > 0:
                    out["cpu_pct"] = round(100 * (d_total - d_idle) / d_total)
            self.last_times = now

        class MemStatus(C.Structure):
            _fields_ = [("length", C.c_ulong), ("load", C.c_ulong), ("total", C.c_ulonglong), ("avail", C.c_ulonglong),
                        ("page_total", C.c_ulonglong), ("page_avail", C.c_ulonglong), ("virt_total", C.c_ulonglong),
                        ("virt_avail", C.c_ulonglong), ("ext_avail", C.c_ulonglong)]

        mem = MemStatus()
        mem.length = C.sizeof(MemStatus)
        if self.k32.GlobalMemoryStatusEx(C.byref(mem)):
            out.update(ram_used_mb=round((mem.total - mem.avail) / 2**20), ram_total_mb=round(mem.total / 2**20))
        return out


# ---------------------------------------------------------------- Linux

def _read(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return None


def _num(text, scale=1):
    try:
        return round(int(text) / scale)
    except (TypeError, ValueError):
        return None


class _Linux:
    def __init__(self, root="/"):
        self.root = Path(root)
        self.last_times = None
        cards = [d for d in (self.root / "sys/class/drm").glob("card*/device") if (d / "mem_info_vram_total").exists()]
        self.card = max(cards, key=lambda d: _num(_read(d / "mem_info_vram_total")) or 0, default=None)
        self.gpu_hwmon = next(iter((self.card / "hwmon").glob("hwmon*")), None) if self.card else None
        self.cpu_hwmon = next((h for h in (self.root / "sys/class/hwmon").glob("hwmon*")
                               if _read(h / "name") in ("k10temp", "coretemp")), None)

    def _labelled(self, hwmon, labels):
        """Primo temp*_input il cui temp*_label e' in labels (in ordine di preferenza), in °C."""
        found = {_read(f): f.with_name(f.name.replace("_label", "_input")) for f in hwmon.glob("temp*_label")}
        for label in labels:
            if label in found:
                return _num(_read(found[label]), 1000)
        return None

    def sample(self):
        s = {}
        if self.card:
            s.update(gpu_busy_pct=_num(_read(self.card / "gpu_busy_percent")),
                     vram_used_mb=_num(_read(self.card / "mem_info_vram_used"), 2**20),
                     vram_total_mb=_num(_read(self.card / "mem_info_vram_total"), 2**20),
                     vram_shared_mb=_num(_read(self.card / "mem_info_gtt_used"), 2**20))
        if self.gpu_hwmon:
            h = self.gpu_hwmon
            power = _read(h / "power1_average") or _read(h / "power1_input")
            s.update(gpu_c=self._labelled(h, ["edge"]), gpu_hotspot_c=self._labelled(h, ["junction"]),
                     gpu_mem_c=self._labelled(h, ["mem"]), gpu_w=_num(power, 10**6),
                     gpu_fan_rpm=_num(_read(h / "fan1_input")), gpu_clock_mhz=_num(_read(h / "freq1_input"), 10**6))
            pwm = _num(_read(h / "pwm1"))  # comando della ventola, 0-255
            s["gpu_fan_pct"] = None if pwm is None else round(pwm * 100 / 255)
        if self.cpu_hwmon:
            s["cpu_c"] = self._labelled(self.cpu_hwmon, ["Tctl", "Tdie", "Package id 0"])
        stat = (_read(self.root / "proc/stat") or "").splitlines()
        if stat and stat[0].startswith("cpu "):
            values = [int(v) for v in stat[0].split()[1:]]
            now = (values[3] + values[4], sum(values))  # idle + iowait, totale
            if self.last_times and now[1] > self.last_times[1]:
                s["cpu_pct"] = round(100 * (1 - (now[0] - self.last_times[0]) / (now[1] - self.last_times[1])))
            self.last_times = now
        if self.card:
            s["gpu_jobs"], s["gpu_job_list"] = self._gpu_jobs()
        meminfo = dict(re.findall(r"^(\w+):\s+(\d+)", _read(self.root / "proc/meminfo") or "", re.M))
        if "MemTotal" in meminfo and "MemAvailable" in meminfo:
            total, avail = int(meminfo["MemTotal"]), int(meminfo["MemAvailable"])
            s.update(ram_used_mb=round((total - avail) / 1024), ram_total_mb=round(total / 1024))
        return s

    def _gpu_jobs(self):
        """VRAM per processo dalle righe drm-memory-vram di /proc/<pid>/fdinfo (amdgpu), un client drm contato una volta."""
        by_pid, names, seen = {}, {}, set()
        for info in (self.root / "proc").glob("[0-9]*/fdinfo/*"):
            text = _read(info) or ""
            vram = re.search(r"^drm-memory-vram:\s+(\d+)\s*KiB", text, re.M)
            client = re.search(r"^drm-client-id:\s+(\d+)", text, re.M)
            if not vram:
                continue
            pid = int(info.parent.parent.name)
            if client and (pid, client.group(1)) in seen:
                continue
            seen.add((pid, client.group(1) if client else info.name))
            by_pid[pid] = by_pid.get(pid, 0) + int(vram.group(1)) * 1024
            names[pid] = _read(info.parent.parent / "comm") or ""
        return gpu_jobs(by_pid, names)


# ---------------------------------------------------------------- fonti comuni

# Motivi di rallentamento di nvidia-smi che dicono qualcosa sul raffreddamento o sull'alimentazione (non l'inattivita')
NVIDIA_THROTTLE = [("hw_thermal_slowdown", "temperatura"), ("sw_thermal_slowdown", "temperatura"),
                   ("hw_power_brake_slowdown", "consumo"), ("sw_power_cap", "consumo")]


def nvidia_sample():
    """Valori di nvidia-smi per la prima GPU NVIDIA, o {}."""
    exe = shutil.which("nvidia-smi")
    if not exe:
        return {}
    keys = ["gpu_c", "gpu_w", "gpu_busy_pct", "vram_used_mb", "vram_total_mb", "gpu_clock_mhz", "gpu_fan_pct"]
    try:
        out = subprocess.run([exe, "--query-gpu=temperature.gpu,power.draw,utilization.gpu,memory.used,memory.total,"
                              "clocks.gr,fan.speed", "--format=csv,noheader,nounits"],
                             capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return {}
    first = out.splitlines()[0].split(",") if out.strip() else []
    s = {k: round(float(v)) for k, v in zip(keys, first) if re.fullmatch(r"\s*[\d.]+\s*", v)}
    # Rallentamenti decisi dal driver, in una richiesta a parte: i driver vecchi chiamano questi campi
    # clocks_throttle_reasons e rifiutano tutta la richiesta se ne manca uno
    try:
        out = subprocess.run([exe, "--query-gpu=" + ",".join(f"clocks_event_reasons.{r}" for r, _ in NVIDIA_THROTTLE),
                              "--format=csv,noheader"], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        out = ""
    flags = out.splitlines()[0].split(",") if out.strip() else []
    if len(flags) == len(NVIDIA_THROTTLE):
        causes = dict.fromkeys(cause for (_, cause), flag in zip(NVIDIA_THROTTLE, flags) if flag.strip() == "Active")
        s["gpu_throttle"] = "+".join(causes)
    try:
        apps = subprocess.run([exe, "--query-compute-apps=pid,used_memory,process_name", "--format=csv,noheader,nounits"],
                              capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return s
    by_pid, names = {}, {}
    for row in apps.splitlines():
        if match := re.match(r"\s*(\d+),\s*(\d+),\s*(.*)", row):
            pid = int(match.group(1))
            by_pid[pid] = by_pid.get(pid, 0) + int(match.group(2)) * 2**20
            names[pid] = Path(match.group(3).strip()).stem
    if s:
        s["gpu_jobs"], s["gpu_job_list"] = gpu_jobs(by_pid, names)
    return s


def in_wsl():
    return "microsoft" in platform.release().lower()


WINDOWS_ENV_KEYS = [r"HKCU\Environment", r"HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment"]
_windows_env = {}  # letta una volta per processo


def windows_env(name):
    """Variabile d'ambiente di Windows (dell'utente, poi di sistema) letta dal registro con reg.exe, o None.
    Da WSL: WSL non eredita le variabili di Windows, salvo quelle elencate in WSLENV."""
    reg = shutil.which("reg.exe")
    if not reg:
        return None
    for key in WINDOWS_ENV_KEYS:
        try:
            out = subprocess.run([reg, "query", key, "/v", name], capture_output=True, text=True, errors="replace",
                                 timeout=10).stdout
        except (OSError, subprocess.SubprocessError):
            return None
        if match := re.search(rf"^\s+{re.escape(name)}\s+REG_(?:EXPAND_)?SZ\s+(.*?)\s*$", out, re.M | re.I):
            return match.group(1)
    return None


def lhm_setting(name):
    """Impostazione di LHM dall'ambiente; da WSL, se manca, quella impostata su Windows (windows_env)."""
    value = os.environ.get(name)
    if value is None and in_wsl():
        if name not in _windows_env:
            _windows_env[name] = windows_env(name)
        value = _windows_env[name]
    return value


def lhm_sample(fetch=None):
    """Valori dal web server di LibreHardwareMonitor (dict, vuoto se non ha i sensori), o None se non risponde:
    temperatura e ventola della CPU, ventole del case (la piu' veloce), e della GPU punto piu' caldo, memoria, VRM
    (AMD) e ventole, anche una per una (NVIDIA: sensori NvAPI di LHM, che nvidia-smi non da')."""
    url = (lhm_setting("POCKET_TRAVEL_LHM_URL") or "http://127.0.0.1:8085").rstrip("/") + "/data.json"
    curl = shutil.which("curl.exe") if fetch is None and in_wsl() else None
    if curl:
        # Da WSL il firewall di Windows blocca le connessioni verso Windows (anche al gateway), e 127.0.0.1 di WSL non
        # e' quello di Windows: curl.exe e' un processo di Windows e lo raggiunge. Utente e password nella
        # configurazione su stdin (-K -), non tra gli argomenti, che gli altri processi possono leggere.
        def fetch():
            user, password = lhm_setting("POCKET_TRAVEL_LHM_USER"), lhm_setting("POCKET_TRAVEL_LHM_PASSWORD")
            quoted = f"{user}:{password or ''}".replace("\\", "\\\\").replace('"', '\\"')
            config = f'user = "{quoted}"\n' if user else ""
            out = subprocess.run([curl, "-s", "-f", "-m", "3", "-A", USER_AGENT, "-K", "-", url], input=config,
                                 capture_output=True, text=True, encoding="utf-8", timeout=10)
            if out.returncode != 0:
                raise OSError(f"curl.exe: codice {out.returncode}")
            return json.loads(out.stdout)
    elif fetch is None:
        def fetch():
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            user, password = lhm_setting("POCKET_TRAVEL_LHM_USER"), lhm_setting("POCKET_TRAVEL_LHM_PASSWORD")
            if user:
                token = base64.b64encode(f"{user}:{password or ''}".encode()).decode()
                request.add_header("Authorization", f"Basic {token}")
            with urllib.request.urlopen(request, timeout=3) as r:
                return json.load(r)
    try:
        tree = fetch()
    except (OSError, ValueError, subprocess.SubprocessError):
        return None
    sensors = []  # (SensorId, Text, valore)

    def walk(node):
        match = re.match(r"\s*(-?\d+(?:[.,]\d+)?)", str(node.get("Value", "")))
        if node.get("SensorId") and match:
            sensors.append((str(node["SensorId"]), str(node.get("Text", "")), float(match.group(1).replace(",", "."))))
        for child in node.get("Children", []):
            walk(child)

    walk(tree)

    def pick(id_pattern, text=""):
        return [v for sid, name, v in sensors if re.match(id_pattern, sid) and text in name]

    s = {}
    cpu = r"/(amd|intel)cpu/\d+/temperature/"
    # Tctl e' la temperatura con cui le CPU AMD si regolano (quella che mostrano le schede madri); poi il
    # pacchetto degli Intel, poi le singole Tdie dei CCD
    temps = next((t for t in (pick(cpu, p) for p in ("Tctl", "Package", "Tdie", "")) if t), [])
    board_fans = r"/lpc/[^/]+/\d+/fan/"  # chip della scheda madre
    gpu = r"/gpu-(nvidia|amd|intel)/\d+/"
    # Ventole della GPU una per una: con le NVIDIA LHM le legge da NvAPI ("GPU Fan 1", "GPU Fan 2"...), giri misurati;
    # nvidia-smi da' solo la velocita' voluta, uguale per tutte. Conta la scheda con piu' ventole (l'iGPU non ne ha).
    by_card = {}
    for sid, _, v in sensors:
        if match := re.match(gpu + "fan/", sid):
            by_card.setdefault(sid[:match.end() - len("fan/")], []).append(round(v))
    gpu_fans = max(by_card.values(), key=len, default=[])
    for key, values in (("cpu_c", temps), ("cpu_fan_rpm", pick(board_fans, "CPU")),
                        ("case_fan_rpm", [v for sid, name, v in sensors if re.match(board_fans, sid) and "CPU" not in name]),
                        ("gpu_hotspot_c", pick(gpu + "temperature/", "Hot Spot")),
                        ("gpu_mem_c", pick(gpu + "temperature/", "Memory")),
                        ("gpu_vrm_c", pick(gpu + "temperature/", "GPU VR ")),  # AMD: "GPU VR VDDC", "GPU VR SoC"...
                        ("gpu_fan_rpm", gpu_fans), ("gpu_fan_pct", pick(gpu + "control/", "Fan"))):
        if values:
            s[key] = round(max(values))
    if len(gpu_fans) > 1:
        s["gpu_fans_rpm"] = "/".join(map(str, gpu_fans))
    return s


class Reader:
    """Un campione (dict con le chiavi di FIELDS, None dove manca il valore) da tutte le fonti disponibili."""

    def __init__(self):
        self.os_reader = None
        try:
            self.os_reader = _Windows() if os.name == "nt" else _Linux()
        except Exception:  # una fonte rotta non deve fermare il lavoro monitorato
            self.os_reader = None
        self.lhm = os.name == "nt" or in_wsl()  # da WSL, LHM di Windows con curl.exe (lhm_sample)

    def sample(self):
        s = dict.fromkeys(FIELDS)
        s["time"] = time.strftime("%Y-%m-%d %H:%M:%S")
        try:
            s.update({k: v for k, v in (self.os_reader.sample() if self.os_reader else {}).items() if v is not None})
        except Exception:
            pass
        if s["gpu_c"] is None:
            s.update({k: v for k, v in nvidia_sample().items() if v is not None})
        if self.lhm:
            extra = lhm_sample()
            self.lhm = extra is not None  # server spento: non lo si richiede a ogni campione
            s.update({k: v for k, v in (extra or {}).items() if s.get(k) is None})  # le altre fonti hanno la precedenza
        return s


def _gb(mb):
    return f"{mb / 1024:.1f}".replace(".", ",")


def fan(rpm, pct=None):
    """"fan 1200 RPM 30%" (con piu' ventole "fan 1200/1180 RPM 30%"), con le parti che ci sono; "" senza valori."""
    parts = ([f"{rpm} RPM"] if rpm is not None else []) + ([f"{pct}%"] if pct is not None else [])
    return "fan " + " ".join(parts) if parts else ""


def _fan_stopped(s):
    """Una ventola della GPU ferma mentre le altre girano: guasta o staccata (con il fermo a bassa temperatura si
    fermano tutte insieme)."""
    fans = [int(f) for f in str(s.get("gpu_fans_rpm") or "").split("/") if f]
    return len(fans) > 1 and min(fans) == 0 and max(fans) > 0


def line(s):
    """Riga compatta di un campione, gruppi separati da " | "; le parti senza valore non compaiono. Esempio:
    GPU 38/49 °C mem 62 °C VRM 78 °C 120 W 99% fan 1200/1180 RPM 60% | VRAM 5,8/6,0 GB +0,3 in RAM |
    CPU 20% 58 °C fan 722 RPM | case fan 813 RPM | RAM 4,8/11,7 GB
    (38/49 °C: temperatura della GPU e del punto piu' caldo)."""
    parts = []
    if s.get("gpu_c") is not None or s.get("gpu_busy_pct") is not None:
        gpu = ["GPU"]
        if s.get("gpu_c") is not None:
            hot = f"/{s['gpu_hotspot_c']}" if s.get("gpu_hotspot_c") is not None else ""
            gpu.append(f"{s['gpu_c']}{hot} °C")
        gpu += [f"{name} {s[k]} °C" for name, k in (("mem", "gpu_mem_c"), ("VRM", "gpu_vrm_c")) if s.get(k) is not None]
        gpu += [f"{s['gpu_w']} W"] if s.get("gpu_w") is not None else []
        gpu += [f"{s['gpu_busy_pct']}%"] if s.get("gpu_busy_pct") is not None else []
        gpu += [f for f in [fan(s.get("gpu_fans_rpm") or s.get("gpu_fan_rpm"), s.get("gpu_fan_pct"))] if f]
        gpu += [f"rallentata: {s['gpu_throttle']}"] if s.get("gpu_throttle") else []
        parts.append(" ".join(gpu))
    if s.get("vram_used_mb") is not None:
        vram = f"VRAM {_gb(s['vram_used_mb'])}" + (f"/{_gb(s['vram_total_mb'])} GB" if s.get("vram_total_mb") else " GB")
        if _gb(s.get("vram_shared_mb") or 0) != "0,0":
            vram += f" +{_gb(s['vram_shared_mb'])} in RAM"
        parts.append(vram)
    cpu = [f"{s[k]}{unit}" for k, unit in (("cpu_pct", "%"), ("cpu_w", " W"), ("cpu_c", " °C")) if s.get(k) is not None]
    cpu += [fan(s.get("cpu_fan_rpm"))] if s.get("cpu_fan_rpm") is not None else []
    if cpu:
        parts.append("CPU " + " ".join(cpu))
    if s.get("case_fan_rpm") is not None:
        parts.append(f"case {fan(s['case_fan_rpm'])}")
    if s.get("ram_used_mb") is not None and s.get("ram_total_mb"):
        parts.append(f"RAM {_gb(s['ram_used_mb'])}/{_gb(s['ram_total_mb'])} GB")
    return " | ".join(parts) or "nessun sensore disponibile"


class Monitor:
    """Thread che campiona ogni `interval` secondi, scrive il CSV e stampa le righe e gli avvisi."""

    def __init__(self, name, path=None, interval=10, report_every=60, out=sys.stderr, reader=None):
        self.name, self.interval, self.report_every, self.out = name, interval, report_every, out
        self.reader = reader or Reader()
        hw_dir = Path(os.environ.get("POCKET_TRAVEL_HW_DIR") or Path.home() / ".cache" / "pocket-travel" / "hw")
        self.path = Path(path) if path else hw_dir / f"{re.sub(r'[^\w.-]', '_', name)}-{time.strftime('%Y%m%d-%H%M%S')}-{os.getpid()}.csv"
        self.max, self.active, self.last_report = {}, set(), None
        self.stop_event, self.thread = threading.Event(), None

    def tick(self, now=None):
        """Un campione: CSV, massimi, avvisi, e la riga se e' passato report_every."""
        s = self.reader.sample()
        with open(self.path, "a", newline="", encoding="utf-8") as f:
            if f.tell() == 0:
                f.write(",".join(FIELDS) + "\n")
            csv.DictWriter(f, FIELDS, extrasaction="ignore").writerow(s)  # gpu_job_list solo negli avvisi
            f.flush()
            os.fsync(f.fileno())
        for k, v in s.items():
            if isinstance(v, (int, float)) and (self.max.get(k) is None or v > self.max[k]):
                self.max[k] = v
        for key, cond, message in ALERTS:
            if cond(s) and key not in self.active:
                self.active.add(key)
                print(f"[hw] ATTENZIONE: {message.format(**{k: (v if v is not None else '?') for k, v in s.items()})}",
                      file=self.out, flush=True)
            elif not cond(s):
                self.active.discard(key)
        now = time.monotonic() if now is None else now
        if self.last_report is None or now - self.last_report >= self.report_every:
            self.last_report = now
            print(f"[hw] {line(s)}", file=self.out, flush=True)
        return s

    def _run(self):
        while not self.stop_event.is_set():
            try:
                self.tick()
            except Exception as e:  # il monitor non deve mai fermare il lavoro
                print(f"[hw] monitor fermo: {e}", file=self.out, flush=True)
                return
            self.stop_event.wait(self.interval)

    def start(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        print(f"[hw] monitor di {self.name}: una riga al minuto, campioni ogni {self.interval} s in {self.path}",
              file=self.out, flush=True)
        self.thread = threading.Thread(target=self._run, daemon=True, name="hw_monitor")
        self.thread.start()
        return self

    def stop(self):
        if self.thread is None:
            return
        self.stop_event.set()
        self.thread.join(timeout=30)
        self.thread = None
        if self.max:
            try:
                print(f"[hw] massimi di {self.name}: {line(self.max)} · campioni in {self.path}", file=self.out, flush=True)
            except (OSError, ValueError):  # all'uscita lo stderr puo' essere gia' chiuso
                pass


def start(name):
    """Monitor fino all'uscita del processo, se un processo padre non ne ha gia' uno."""
    if os.environ.get(HELD_ENV):
        return None
    import atexit
    monitor = Monitor(name).start()
    os.environ[HELD_ENV] = str(os.getpid())
    atexit.register(monitor.stop)
    return monitor


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] == ["run"] and "--" in argv and argv.index("--") + 1 < len(argv):
        cmd = argv[argv.index("--") + 1:]
        start(Path(cmd[0]).name)
        return subprocess.call(cmd)
    reader = Reader()
    if argv[:1] == ["--once"]:
        reader.sample()  # la CPU in % serve due letture
        time.sleep(1)
        print(line(reader.sample()))
        return 0
    if argv:
        print(__doc__, file=sys.stderr)
        return 2
    reader.sample()
    try:
        while True:
            time.sleep(5)
            print(f"{time.strftime('%H:%M:%S')} {line(reader.sample())}", flush=True)
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
