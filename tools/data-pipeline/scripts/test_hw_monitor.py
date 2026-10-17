#!/usr/bin/env python3
"""Test di hw_monitor.py: letture di Linux da un /sys e /proc finti, LibreHardwareMonitor e nvidia-smi finti,
riga leggibile, CSV, avvisi e massimi del monitor. Nessun sensore vero.

Uso: python test_hw_monitor.py
"""
import csv
import io
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
        self.assertEqual(dict(gpu_c=61, gpu_hotspot_c=84, gpu_mem_c=78, gpu_w=195, gpu_fan_rpm=1800, gpu_busy_pct=97,
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
        self.assertEqual(68, hw_monitor.lhm_cpu_temp(fetch=lambda: TREE))

    def test_server_spento(self):
        def fetch():
            raise OSError("connection refused")
        self.assertIsNone(hw_monitor.lhm_cpu_temp(fetch=fetch))

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
                mock.patch("urllib.request.urlopen", urlopen):
            self.assertIsNone(hw_monitor.lhm_cpu_temp())
        self.assertEqual("Basic aW86c2VncmV0YQ==", seen["Authorization"])
        self.assertIn("pocket-travel-hw_monitor", seen["User-agent"])


class NvidiaTest(unittest.TestCase):
    def test_prima_gpu_e_processi(self):
        gpu = SimpleNamespace(stdout="65, 180.52, 99, 7000, 8192, 1900\n71, 20.0, 0, 10, 8192, 300\n")
        apps = SimpleNamespace(stdout="1234, 6000, /usr/bin/python3\n99, 300, Xorg\n")
        with mock.patch("shutil.which", return_value="nvidia-smi"), mock.patch("subprocess.run", side_effect=[gpu, apps]):
            self.assertEqual(dict(gpu_c=65, gpu_w=181, gpu_busy_pct=99, vram_used_mb=7000, vram_total_mb=8192,
                                  gpu_clock_mhz=1900, gpu_jobs=1, gpu_job_list="python3 (PID 1234, 5,9 GB)"),
                             hw_monitor.nvidia_sample())

    def test_assente_o_valori_non_numerici(self):
        with mock.patch("shutil.which", return_value=None):
            self.assertEqual({}, hw_monitor.nvidia_sample())
        out = SimpleNamespace(stdout="65, [N/A], 99, 7000, 8192, 1900\n")
        with mock.patch("shutil.which", return_value="nvidia-smi"), \
                mock.patch("subprocess.run", side_effect=[out, SimpleNamespace(stdout="")]):
            self.assertNotIn("gpu_w", hw_monitor.nvidia_sample())


class LineTest(unittest.TestCase):
    def test_completa(self):
        s = dict(gpu_c=46, gpu_hotspot_c=65, gpu_mem_c=64, gpu_w=200, gpu_busy_pct=99, vram_used_mb=6860,
                 vram_total_mb=12272, vram_shared_mb=300, cpu_pct=18, cpu_w=58, cpu_c=70, ram_used_mb=14540,
                 ram_total_mb=63078)
        self.assertEqual("GPU 46 °C (punto piu' caldo 65 °C, memoria 64 °C) 200 W 99% · VRAM 6,7/12,0 GB "
                         "(+0,3 GB condivisa) · CPU 18% 58 W 70 °C · RAM 14,2/61,6 GB", hw_monitor.line(s))

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
