#!/usr/bin/env python3
"""Test di gpu_lock.py: attesa del lucchetto tenuto da un altro processo, rilascio quando quel processo muore,
figli che non lo riprendono, `status`. Lucchetto in una cartella temporanea, nessuna GPU.

Uso: python test_gpu_lock.py
"""
import io
import os
import subprocess
import sys
import tempfile
import time
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import gpu_lock

HERE = Path(__file__).resolve().parent
# Prende il lucchetto, scrive "preso" e lo tiene finche' stdin non si chiude
HOLDER = ("import sys; sys.path.insert(0, sys.argv[1]); import gpu_lock; gpu_lock.GpuLock('prova').acquire(); "
          "print('preso', flush=True); sys.stdin.read()")


class GpuLockTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = mock.patch.dict(os.environ, {"POCKET_TRAVEL_GPU_LOCK_FILE": str(Path(self.tmp.name) / "gpu.lock")})
        self.env.start()
        os.environ.pop(gpu_lock.HELD_ENV, None)

    def tearDown(self):
        self.env.stop()
        self.tmp.cleanup()

    def holder(self):
        env = {k: v for k, v in os.environ.items() if k != gpu_lock.HELD_ENV}
        proc = subprocess.Popen([sys.executable, "-c", HOLDER, str(HERE)], stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, text=True, env=env)
        self.assertEqual("preso", proc.stdout.readline().strip())
        return proc

    def test_aspetta_chi_lo_tiene_e_parte_quando_si_libera(self):
        proc = self.holder()
        out, sleeps = io.StringIO(), []

        def sleep(_):
            sleeps.append(1)
            if len(sleeps) == 2:  # dopo due attese l'altro processo finisce
                proc.stdin.close()
                proc.wait(timeout=30)
            time.sleep(0.05)

        lock = gpu_lock.GpuLock("eval", out=out, sleep=sleep).acquire()
        try:
            self.assertGreaterEqual(len(sleeps), 2)
            self.assertIn(f"GPU occupata da prova (PID {proc.pid}", out.getvalue())
            self.assertIn("GPU libera: eval parte", out.getvalue())
            self.assertEqual(os.getpid(), gpu_lock.holder()["pid"])
            self.assertEqual(str(os.getpid()), os.environ[gpu_lock.HELD_ENV])
        finally:
            lock.release()
            proc.stdout.close()
        self.assertIsNone(gpu_lock.holder())
        self.assertNotIn(gpu_lock.HELD_ENV, os.environ)

    def test_processo_ucciso_libera_il_lucchetto(self):
        proc = self.holder()
        proc.kill()
        proc.wait(timeout=30)
        sleep = mock.Mock(side_effect=AssertionError("non doveva aspettare"))
        lock = gpu_lock.GpuLock("dopo", out=io.StringIO(), sleep=sleep).acquire()
        lock.release()
        proc.stdin.close()
        proc.stdout.close()

    def test_libero_senza_attesa_e_status(self):
        with redirect_stdout(io.StringIO()) as out:
            gpu_lock.main(["status"])
        self.assertEqual("GPU libera", out.getvalue().strip())
        proc = self.holder()
        try:
            with redirect_stdout(io.StringIO()) as out:
                gpu_lock.main(["status"])
            self.assertIn(f"GPU occupata da prova (PID {proc.pid}", out.getvalue())
        finally:
            proc.stdin.close()
            proc.wait(timeout=30)
            proc.stdout.close()
        with redirect_stdout(io.StringIO()) as out:
            gpu_lock.main(["status"])
        self.assertEqual("GPU libera", out.getvalue().strip())

    def test_figlio_di_chi_lo_tiene_non_lo_riprende(self):
        with mock.patch.dict(os.environ, {gpu_lock.HELD_ENV: "123"}), \
                mock.patch.object(gpu_lock, "GpuLock") as lock, mock.patch("hw_monitor.start") as monitor:
            gpu_lock.gpu_job("train_lora")
        lock.assert_not_called()
        monitor.assert_called_once_with("train_lora")

    def test_lavoro_senza_gpu_solo_monitor(self):
        with mock.patch.object(gpu_lock, "GpuLock") as lock, mock.patch("hw_monitor.start") as monitor:
            gpu_lock.gpu_job("convert_gguf", gpu=False)
        lock.assert_not_called()
        monitor.assert_called_once_with("convert_gguf")

    def fake_helper(self, *lines):
        """Al posto di powershell.exe: le righe di WINDOWS_HELPER, poi aspetta che stdin si chiuda."""
        win_lock = Path(self.tmp.name) / "windows" / "gpu.lock"
        win_lock.parent.mkdir(exist_ok=True)
        script = (f"import sys; print('PATH {win_lock.as_posix()}'); print('PID 4242'); "
                  + "".join(f"print('{line}'); " for line in lines) + "sys.stdout.flush(); sys.stdin.read()")
        proc = subprocess.Popen([sys.executable, "-c", script], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        self.addCleanup(proc.stdout.close)
        wslpath = mock.patch("gpu_lock.subprocess.run", return_value=mock.Mock(stdout=str(win_lock)))
        wslpath.start()
        self.addCleanup(wslpath.stop)
        return proc, win_lock.with_name("gpu-lock.json")

    def test_da_wsl_tiene_anche_il_lucchetto_di_windows(self):
        proc, win_info = self.fake_helper("WAIT", "LOCKED")
        out = io.StringIO()
        with mock.patch.object(gpu_lock, "windows_helper", return_value=proc):
            lock = gpu_lock.GpuLock("train_auto.py", out=out).acquire()
        self.assertIn("GPU occupata su Windows", out.getvalue())
        self.assertIn("GPU libera: train_auto.py parte", out.getvalue())
        # gpu-lock.json di Windows con il PID di powershell.exe, quello che l'hook session-guard controlla
        self.assertEqual((4242, os.getpid()), (gpu_lock.holder(win_info)["pid"], gpu_lock.holder(win_info)["wsl_pid"]))
        lock.release()
        self.assertFalse(win_info.exists())
        self.assertEqual(0, proc.wait(timeout=30))  # stdin chiuso: il blocco di Windows si scioglie

    def test_helper_uscito_senza_lucchetto(self):
        proc, _ = self.fake_helper()
        proc.stdin.close()
        with mock.patch.object(gpu_lock, "windows_helper", return_value=proc), self.assertRaises(SystemExit) as ctx:
            gpu_lock.GpuLock("eval", out=io.StringIO()).acquire()
        self.assertIn("senza il lucchetto di Windows", str(ctx.exception))

    def test_status_da_wsl_legge_il_lucchetto_di_windows(self):
        proc, win_info = self.fake_helper("BUSY")
        win_info.write_text('{"name": "eval_gguf.py", "pid": 77, "since": 0}', encoding="utf-8")
        proc.stdin.close()
        with mock.patch.object(gpu_lock, "windows_helper", return_value=proc), redirect_stdout(io.StringIO()) as out:
            gpu_lock.main(["status"])
        self.assertIn("GPU occupata da eval_gguf.py (PID 77", out.getvalue())

    def test_helper_con_pwsh_se_manca_powershell(self):
        found = {"powershell.exe": None, "pwsh.exe": "/mnt/c/Program Files/PowerShell/7/pwsh.exe"}
        os.environ.pop("POCKET_TRAVEL_GPU_LOCK_FILE")  # senza: il lucchetto e' quello vero, non quello dei test
        with mock.patch.object(gpu_lock, "in_wsl", return_value=True), mock.patch("shutil.which", found.get), \
                mock.patch("subprocess.Popen") as popen:
            gpu_lock.windows_helper("try")
        self.assertEqual(found["pwsh.exe"], popen.call_args.args[0][0])
        found["powershell.exe"] = "/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"
        with mock.patch.object(gpu_lock, "in_wsl", return_value=True), mock.patch("shutil.which", found.get), \
                mock.patch("subprocess.Popen") as popen:
            gpu_lock.windows_helper("try")
        self.assertEqual(found["powershell.exe"], popen.call_args.args[0][0])

    def test_describe(self):
        info = {"name": "train_auto.py", "pid": 42, "session": "abcdef1234", "since": 1000}
        self.assertEqual("train_auto.py (PID 42, sessione abcdef12, da 10 min)", gpu_lock.describe(info, now=1600))
        self.assertEqual("un altro processo", gpu_lock.describe(None))


if __name__ == "__main__":
    unittest.main()
