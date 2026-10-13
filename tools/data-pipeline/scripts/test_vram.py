#!/usr/bin/env python3
"""Test di vram.py: elenco dei processi che occupano VRAM e soglia di partenza, con subprocess, platform e
shutil sostituiti da mock (nessuna GPU e nessun comando di sistema).

Uso: python test_vram.py
"""
import os
import unittest
from types import SimpleNamespace
from unittest import mock

import vram


def completed(stdout):
    return SimpleNamespace(stdout=stdout, returncode=0, stderr="")


class HeldProcessesTest(unittest.TestCase):
    def test_windows_somma_i_contatori_e_scarta_dwm_e_se_stesso(self):
        out = "\n".join([
            "1234 2147483648 python",
            "55 1073741824 ",            # processo terminato: nome vuoto
            "77 3221225472 dwm",         # compositore del desktop: non conta
            f"{os.getpid()} 4294967296 python",  # il processo stesso: non conta
            "88 1610612736 Unsloth Studio",      # nome con spazio
        ])
        with mock.patch("platform.system", return_value="Windows"), \
                mock.patch("subprocess.run", return_value=completed(out)):
            rows = vram.held_processes()
        self.assertEqual([(1234, 2.0, "python"), (55, 1.0, ""), (88, 1.5, "Unsloth Studio")], rows)

    def test_windows_senza_output(self):
        with mock.patch("platform.system", return_value="Windows"), \
                mock.patch("subprocess.run", return_value=completed("")):
            self.assertEqual([], vram.held_processes())

    def test_linux_con_nvidia_smi_ignora_le_righe_non_numeriche(self):
        out = "1234, 2048, python3\nNo running processes found\n 4321, 512, /opt/app, con virgola\n"
        with mock.patch("platform.system", return_value="Linux"), \
                mock.patch("shutil.which", return_value="/usr/bin/nvidia-smi"), \
                mock.patch("subprocess.run", return_value=completed(out)):
            rows = vram.held_processes()
        self.assertEqual([(1234, 2.0, "python3"), (4321, 0.5, "/opt/app, con virgola")], rows)

    def test_linux_senza_nvidia_smi_elenco_vuoto(self):
        with mock.patch("platform.system", return_value="Linux"), mock.patch("shutil.which", return_value=None), \
                mock.patch("subprocess.run") as run:
            self.assertEqual([], vram.held_processes())
        run.assert_not_called()


class CheckTest(unittest.TestCase):
    def test_linux_sotto_soglia_non_esce(self):
        with mock.patch("platform.system", return_value="Linux"), mock.patch.object(vram, "held_processes", return_value=[]):
            self.assertIsNone(vram.check(8.0, 7.5, 10))  # occupata il 6,25%

    def test_linux_esattamente_alla_soglia_non_esce(self):
        with mock.patch("platform.system", return_value="Linux"), mock.patch.object(vram, "held_processes", return_value=[]):
            self.assertIsNone(vram.check(10.0, 9.0, 10))

    def test_linux_oltre_soglia_elenca_i_processi_dal_piu_grande(self):
        held = [(1, 0.5, "small"), (2, 3.0, "big"), (3, 1.0, "")]
        with mock.patch("platform.system", return_value="Linux"), mock.patch("platform.release", return_value="6.1.0"), \
                mock.patch.object(vram, "held_processes", return_value=held):
            with self.assertRaises(SystemExit) as ctx:
                vram.check(8.0, 4.0, 10, "GPU 1 (RX)")
        msg = str(ctx.exception)
        self.assertIn("VRAM (GPU 1 (RX)) gia' occupata al 50%", msg)
        self.assertIn("limite 10%", msg)
        self.assertLess(msg.index("PID 2"), msg.index("PID 3"))
        self.assertLess(msg.index("PID 3"), msg.index("PID 1"))
        self.assertIn("processo terminato", msg)
        self.assertNotIn("WSL", msg)

    def test_wsl_aggiunge_il_suggerimento(self):
        with mock.patch("platform.system", return_value="Linux"), \
                mock.patch("platform.release", return_value="5.15.0-microsoft-standard-WSL2"), \
                mock.patch.object(vram, "held_processes", return_value=[]):
            with self.assertRaises(SystemExit) as ctx:
                vram.check(6.0, 3.0, 10)
        self.assertIn(vram.WSL_HINT, str(ctx.exception))

    def test_windows_usa_i_processi_e_ignora_la_memoria_libera(self):
        # free_gib dice "tutta libera" (PyTorch non vede gli altri processi), ma i contatori dicono 4 GiB su 8
        with mock.patch("platform.system", return_value="Windows"), mock.patch("platform.release", return_value="11"), \
                mock.patch.object(vram, "held_processes", return_value=[(9, 4.0, "game")]):
            with self.assertRaises(SystemExit) as ctx:
                vram.check(8.0, 8.0, 10)
        self.assertIn("al 50%", str(ctx.exception))
        self.assertIn("pnputil", str(ctx.exception))

    def test_windows_senza_processi_parte(self):
        with mock.patch("platform.system", return_value="Windows"), mock.patch.object(vram, "held_processes", return_value=[]):
            self.assertIsNone(vram.check(8.0, 0.0, 10))


LIST_DEVICES = """Available devices:
  Vulkan0: AMD Radeon(TM) Graphics (2048 MiB, 1900 MiB free)
  Vulkan1: AMD Radeon RX 7800 XT (16368 MiB, 15000 MiB free)
"""


class CheckLlamaTest(unittest.TestCase):
    def test_primo_device_se_non_specificato(self):
        with mock.patch("subprocess.run", return_value=completed(LIST_DEVICES)), \
                mock.patch.object(vram, "check") as check:
            vram.check_llama("llama-server", {}, None, 10)
        check.assert_called_once_with(2.0, 1900 / 1024, 10, "Vulkan0 (AMD Radeon(TM) Graphics)")

    def test_device_scelto_per_nome(self):
        with mock.patch("subprocess.run", return_value=completed(LIST_DEVICES)), \
                mock.patch.object(vram, "check") as check:
            vram.check_llama("llama-server", {}, "Vulkan1", 25)
        total, free, pct, label = check.call_args.args
        self.assertEqual((16368 / 1024, 15000 / 1024, 25), (total, free, pct))
        self.assertIn("Vulkan1", label)

    def test_device_inesistente_elenca_quelli_presenti(self):
        with mock.patch("subprocess.run", return_value=completed(LIST_DEVICES)):
            with self.assertRaises(SystemExit) as ctx:
                vram.check_llama("llama-server", {}, "ROCm0", 10)
        self.assertIn("ROCm0 non trovato", str(ctx.exception))
        self.assertIn("Vulkan0, Vulkan1", str(ctx.exception))

    def test_nessun_device(self):
        with mock.patch("subprocess.run", return_value=completed("")):
            with self.assertRaises(SystemExit) as ctx:
                vram.check_llama("llama-server", {}, None, 10)
        self.assertIn("GPU non trovato", str(ctx.exception))
        self.assertIn("nessuno", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
