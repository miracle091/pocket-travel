#!/usr/bin/env python3
"""Test di hw_monitor.py: letture di Linux da un /sys e /proc finti, LibreHardwareMonitor e nvidia-smi finti,
riga leggibile, CSV, avvisi e massimi del monitor. Nessun sensore vero.

Uso: python test_hw_monitor.py
"""
import csv
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import hw_monitor


def write(root, rel, text):
    path = Path(root) / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


class LinuxTest(unittest.TestCase):
    def test_amdgpu_k10temp_proc(self):
        with tempfile.TemporaryDirectory() as root:
            card = "sys/class/drm/card1/device"
            for rel, text in {
                f"{card}/mem_info_vram_total": str(12 * 2**30), f"{card}/mem_info_vram_used": str(6 * 2**30),
                f"{card}/mem_info_gtt_used": str(2**30), f"{card}/gpu_busy_percent": "97",
                f"{card}/hwmon/hwmon3/temp1_label": "edge", f"{card}/hwmon/hwmon3/temp1_input": "61000",
                f"{card}/hwmon/hwmon3/temp2_label": "junction", f"{card}/hwmon/hwmon3/temp2_input": "84000",
                f"{card}/hwmon/hwmon3/temp3_label": "mem", f"{card}/hwmon/hwmon3/temp3_input": "78000",
                f"{card}/hwmon/hwmon3/power1_average": "195000000", f"{card}/hwmon/hwmon3/fan1_input": "1800",
                f"{card}/hwmon/hwmon3/pwm1": "128",
                "sys/class/drm/card0/device/mem_info_vram_total": str(2**29),  # grafica integrata: scartata
                "sys/class/hwmon/hwmon1/name": "k10temp", "sys/class/hwmon/hwmon1/temp1_label": "Tctl",
                "sys/class/hwmon/hwmon1/temp1_input": "71250",
                "proc/meminfo": "MemTotal:       65536000 kB\nMemAvailable:   16384000 kB\n",
                "proc/stat": "cpu  100 0 100 800 0 0 0 0 0 0\n",
                # training: due descrittori dello stesso client drm, contati una volta
                "proc/100/comm": "python3\n", "proc/100/fdinfo/5": "drm-client-id: 7\ndrm-memory-vram: 6291456 KiB\n",
                "proc/100/fdinfo/6": "drm-client-id: 7\ndrm-memory-vram: 6291456 KiB\n",
                "proc/200/comm": "llama-server\n", "proc/200/fdinfo/3": "drm-client-id: 9\ndrm-memory-vram: 2097152 KiB\n",
                "proc/300/comm": "firefox\n", "proc/300/fdinfo/4": "drm-client-id: 11\ndrm-memory-vram: 204800 KiB\n",
                "proc/400/fdinfo/1": "pos: 0\nflags: 02\n",
            }.items():
                write(root, rel, text)
            reader = hw_monitor._Linux(root)
            reader.sample()
            write(root, "proc/stat", "cpu  200 0 200 900 0 0 0 0 0 0\n")  # 200 occupati su 300
            s = reader.sample()
        self.assertEqual(dict(gpu_c=61, gpu_hotspot_c=84, gpu_mem_c=78, gpu_w=195, gpu_fan_rpm=1800, gpu_fan_pct=50, gpu_busy_pct=97,
                              vram_used_mb=6144, vram_total_mb=12288, vram_shared_mb=1024, cpu_c=71, cpu_pct=67,
                              ram_used_mb=48000, ram_total_mb=64000, gpu_jobs=2,
                              gpu_job_list="python3 (PID 100, 6,0 GB), llama-server (PID 200, 2,0 GB)"),
                         {k: v for k, v in s.items() if v is not None})

    def test_senza_gpu_ne_sensori(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "proc/meminfo", "MemTotal: 1024 kB\nMemAvailable: 512 kB\n")
            s = hw_monitor._Linux(root).sample()
        self.assertEqual({"ram_used_mb": 0, "ram_total_mb": 1}, {k: v for k, v in s.items() if v is not None})


class GpuJobsTest(unittest.TestCase):
    def test_sopra_un_gib_senza_dwm(self):
        sizes = {1: 5 * 2**30, 2: 2**29, 3: 3 * 2**30, 4: 2 * 2**30}
        names = {1: "python", 3: "dwm", 4: "llama-server"}
        self.assertEqual((2, "python (PID 1, 5,0 GB), llama-server (PID 4, 2,0 GB)"), hw_monitor.gpu_jobs(sizes, names))


TREE = {"Text": "Sensor", "Children": [{"Text": "PC", "Children": [
    {"Text": "AMD Ryzen", "Children": [{"Text": "Temperatures", "Children": [
        {"Text": "Core (Tctl/Tdie)", "Value": "67,5 °C", "SensorId": "/amdcpu/0/temperature/2"},
        {"Text": "CCD1 (Tdie)", "Value": "72,0 °C", "SensorId": "/amdcpu/0/temperature/11"}]}]},
    {"Text": "AMD Radeon", "Children": [
        {"Text": "GPU Hot Spot", "Value": "90.0 °C", "SensorId": "/gpu-amd/0/temperature/2"}]}]}]}


class LhmTest(unittest.TestCase):
    def test_preferisce_tctl_e_ignora_la_gpu(self):
        self.assertEqual(dict(cpu_c=68, gpu_hotspot_c=90), hw_monitor.lhm_sample(fetch=lambda: TREE))

    def test_ventole_vrm_e_nvidia(self):
        def sensor(sid, text, value):
            return {"SensorId": sid, "Text": text, "Value": value}

        tree = {"Children": [
            sensor("/lpc/it8689e/0/fan/0", "CPU Fan", "529 RPM"), sensor("/lpc/it8689e/0/fan/1", "System Fan #1", "739 RPM"),
            sensor("/lpc/it8689e/0/fan/2", "System Fan #2", "0 RPM"),
            sensor("/gpu-amd/0/temperature/0", "GPU Core", "40,0 °C"),  # grafica integrata, senza ventole
            sensor("/gpu-nvidia/0/temperature/2", "GPU Hot Spot", "50,2 °C"),
            sensor("/gpu-nvidia/0/temperature/3", "GPU Memory Junction", "62,0 °C"),
            sensor("/gpu-nvidia/0/fan/1", "GPU Fan 1", "1200 RPM"), sensor("/gpu-nvidia/0/fan/2", "GPU Fan 2", "0 RPM"),
            sensor("/gpu-nvidia/0/control/1", "GPU Fan 1", "30,0 %"),
            sensor("/gpu-amd/1/temperature/4", "GPU VR SoC", "71,0 °C"),
            sensor("/gpu-amd/1/temperature/2", "GPU VR VDDC", "78,0 °C")]}
        self.assertEqual(dict(cpu_fan_rpm=529, case_fan_rpm=739, gpu_hotspot_c=50, gpu_mem_c=62, gpu_vrm_c=78,
                              gpu_fan_rpm=1200, gpu_fan_pct=30, gpu_fans_rpm="1200/0"),
                         hw_monitor.lhm_sample(fetch=lambda: tree))

    def test_server_spento(self):
        def fetch():
            raise OSError("connection refused")
        self.assertIsNone(hw_monitor.lhm_sample(fetch=fetch))

    def test_autenticazione_dalle_variabili(self):
        seen = {}

        class Response(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        def urlopen(request, timeout):
            seen.update(request.headers)
            return Response(b'{"Children": []}')

        with mock.patch.dict(os.environ, {"POCKET_TRAVEL_LHM_USER": "io", "POCKET_TRAVEL_LHM_PASSWORD": "segreta"}), \
                mock.patch.object(hw_monitor, "in_wsl", return_value=False), mock.patch("urllib.request.urlopen", urlopen):
            self.assertEqual({}, hw_monitor.lhm_sample())
        self.assertEqual("Basic aW86c2VncmV0YQ==", seen["Authorization"])
        self.assertIn("pocket-travel-hw_monitor", seen["User-agent"])

    def test_da_wsl_con_curl_exe_e_password_su_stdin(self):
        done = SimpleNamespace(returncode=0, stdout=json.dumps(TREE))
        with mock.patch.dict(os.environ, {"POCKET_TRAVEL_LHM_USER": "io", "POCKET_TRAVEL_LHM_PASSWORD": "segreta"}), \
                mock.patch.object(hw_monitor, "in_wsl", return_value=True), \
                mock.patch("shutil.which", return_value="/mnt/c/Windows/System32/curl.exe"), \
                mock.patch("subprocess.run", return_value=done) as run:
            self.assertEqual(68, hw_monitor.lhm_sample()["cpu_c"])
        args, kwargs = run.call_args
        self.assertEqual("/mnt/c/Windows/System32/curl.exe", args[0][0])
        self.assertEqual("http://127.0.0.1:8085/data.json", args[0][-1])
        self.assertNotIn("segreta", " ".join(args[0]))
        self.assertEqual('user = "io:segreta"\n', kwargs["input"])
        with mock.patch.object(hw_monitor, "in_wsl", return_value=True), mock.patch("shutil.which", return_value="curl.exe"), \
                mock.patch("subprocess.run", return_value=SimpleNamespace(returncode=7, stdout="")):
            self.assertIsNone(hw_monitor.lhm_sample())  # server spento: curl.exe esce con 7


class NvidiaTest(unittest.TestCase):
    def test_prima_gpu_e_processi(self):
        gpu = SimpleNamespace(stdout="65.50, 180.52, 99, 7000, 8192, 1900, 45\n71, 20.0, 0, 10, 8192, 300, 30\n")
        throttle = SimpleNamespace(stdout="Active, Not Active, Not Active, Active\nNot Active, Not Active, Not Active, Not Active\n")
        apps = SimpleNamespace(stdout="1234, 6000, /usr/bin/python3\n99, 300, Xorg\n")
        with mock.patch("shutil.which", return_value="nvidia-smi"), \
                mock.patch("subprocess.run", side_effect=[gpu, throttle, apps]):
            self.assertEqual(dict(gpu_c=66, gpu_w=181, gpu_busy_pct=99, vram_used_mb=7000, vram_total_mb=8192,
                                  gpu_clock_mhz=1900, gpu_fan_pct=45, gpu_throttle="temperatura+consumo", gpu_jobs=1,
                                  gpu_job_list="python3 (PID 1234, 5,9 GB)"),
                             hw_monitor.nvidia_sample())

    def test_assente_o_valori_non_numerici(self):
        with mock.patch("shutil.which", return_value=None):
            self.assertEqual({}, hw_monitor.nvidia_sample())
        out = SimpleNamespace(stdout="65, [N/A], 99, 7000, 8192, 1900, [N/A]\n")
        # driver vecchio: i campi clocks_event_reasons non esistono e nvidia-smi non risponde con i valori
        old = SimpleNamespace(stdout='Field "clocks_event_reasons.hw_thermal_slowdown" is not a valid field to query.\n')
        with mock.patch("shutil.which", return_value="nvidia-smi"), \
                mock.patch("subprocess.run", side_effect=[out, old, SimpleNamespace(stdout="")]):
            s = hw_monitor.nvidia_sample()
        self.assertNotIn("gpu_w", s)
        self.assertNotIn("gpu_fan_pct", s)
        self.assertNotIn("gpu_throttle", s)


class LineTest(unittest.TestCase):
    def test_completa(self):
        s = dict(gpu_c=46, gpu_hotspot_c=65, gpu_mem_c=64, gpu_w=200, gpu_busy_pct=99, vram_used_mb=6860,
                 vram_total_mb=12272, vram_shared_mb=300, cpu_pct=18, cpu_w=58, cpu_c=70, ram_used_mb=14540,
                 ram_total_mb=63078)
        self.assertEqual("GPU 46 °C (punto piu' caldo 65 °C, memoria 64 °C) 200 W 99% · VRAM 6,7/12,0 GB "
                         "(+0,3 GB condivisa) · CPU 18% 58 W 70 °C · RAM 14,2/61,6 GB", hw_monitor.line(s))

    def test_ventole_vrm_e_rallentamento(self):
        s = dict(gpu_c=80, gpu_vrm_c=95, gpu_w=120, gpu_busy_pct=100, gpu_fan_rpm=1200, gpu_fans_rpm="1200/1180",
                 gpu_fan_pct=60, gpu_throttle="temperatura+consumo", cpu_pct=20, cpu_c=60, cpu_fan_rpm=900,
                 case_fan_rpm=739)
        self.assertEqual("GPU 80 °C (VRM 95 °C) 120 W 100% ventole 1200/1180 RPM (60%) rallentata (temperatura, consumo)"
                         " · CPU 20% 60 °C ventola 900 RPM · case ventola 739 RPM", hw_monitor.line(s))
        self.assertEqual("GPU 40 °C ventola 30%", hw_monitor.line(dict(gpu_c=40, gpu_fan_pct=30)))

    def test_parziale_e_vuota(self):
        self.assertEqual("CPU 5% · RAM 1,0/2,0 GB", hw_monitor.line(dict(cpu_pct=5, ram_used_mb=1024, ram_total_mb=2048)))
        self.assertEqual("nessun sensore disponibile", hw_monitor.line({}))


class FakeReader:
    def __init__(self, samples):
        self.samples = iter(samples)

    def sample(self):
        s = dict.fromkeys(hw_monitor.FIELDS)
        s.update(next(self.samples))
        return s


class MonitorTest(unittest.TestCase):
    def test_csv_avvisi_righe_e_massimi(self):
        samples = [dict(gpu_hotspot_c=80, vram_used_mb=6000, vram_total_mb=12000),
                   dict(gpu_hotspot_c=101, vram_used_mb=11500, vram_total_mb=12000, vram_shared_mb=2048),
                   dict(gpu_hotspot_c=102, vram_used_mb=11500, vram_total_mb=12000, vram_shared_mb=2048),
                   dict(gpu_hotspot_c=90, vram_used_mb=6000, vram_total_mb=12000),
                   dict(gpu_hotspot_c=103, vram_used_mb=6000, vram_total_mb=12000, gpu_jobs=2,
                        gpu_job_list="python (PID 1, 6,0 GB), llama-server (PID 2, 9,1 GB)")]
        with tempfile.TemporaryDirectory() as tmp:
            out = io.StringIO()
            monitor = hw_monitor.Monitor("prova", path=Path(tmp) / "hw.csv", out=out, reader=FakeReader(samples))
            for now in (0, 10, 20, 30, 70):
                monitor.tick(now)
            with open(monitor.path, encoding="utf-8") as f:
                rows = list(csv.DictReader(f))
        text = out.getvalue()
        self.assertEqual(5, len(rows))
        self.assertEqual(["80", "101", "102", "90", "103"], [r["gpu_hotspot_c"] for r in rows])
        # un avviso per episodio: 101 e 102 sono lo stesso, 103 dopo il ritorno a 90 e' nuovo
        self.assertEqual(2, text.count("punto piu' caldo della GPU a"))
        self.assertEqual(1, text.count("2048 MB traboccati nella RAM"))
        self.assertIn("2 lavori insieme sulla GPU: python (PID 1, 6,0 GB), llama-server (PID 2, 9,1 GB)", text)
        self.assertEqual("2", rows[-1]["gpu_jobs"])
        # una riga a 0 e una a 70 (report_every 60)
        self.assertEqual(2, sum(1 for l in text.splitlines() if l.startswith("[hw] VRAM")))
        self.assertEqual(103, monitor.max["gpu_hotspot_c"])

    def test_avvisi_di_ventole_vrm_e_rallentamento(self):
        samples = [dict(gpu_fans_rpm="1200/0", gpu_vrm_c=106, gpu_c=88, gpu_throttle="temperatura"),
                   dict(gpu_fans_rpm="0/0", gpu_vrm_c=90, gpu_throttle="consumo")]  # fermo a bassa temperatura: tutte
        with tempfile.TemporaryDirectory() as tmp:
            out = io.StringIO()
            monitor = hw_monitor.Monitor("prova", path=Path(tmp) / "hw.csv", out=out, reader=FakeReader(samples))
            monitor.tick(0)
            monitor.tick(10)
        text = out.getvalue()
        self.assertEqual(1, text.count("una ventola della GPU e' ferma mentre le altre girano (1200/0 RPM)"))
        self.assertEqual(1, text.count("VRM della GPU a 106 °C"))
        self.assertEqual(1, text.count("GPU rallentata dal driver per la temperatura (88 °C)"))

    def test_figlio_di_un_processo_monitorato(self):
        with mock.patch.dict(os.environ, {hw_monitor.HELD_ENV: "1"}), mock.patch.object(hw_monitor, "Monitor") as m:
            self.assertIsNone(hw_monitor.start("train_lora"))
        m.assert_not_called()

    def test_fonte_rotta_non_ferma_il_campione(self):
        class Broken:
            def sample(self):
                raise RuntimeError("ADL")

        reader = hw_monitor.Reader.__new__(hw_monitor.Reader)
        reader.os_reader, reader.lhm = Broken(), False
        with mock.patch.object(hw_monitor, "nvidia_sample", return_value={}):
            s = reader.sample()
        self.assertIsNotNone(s["time"])
        self.assertIsNone(s["gpu_c"])


if __name__ == "__main__":
    unittest.main()
