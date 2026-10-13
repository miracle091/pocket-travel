#!/usr/bin/env python3
"""Test di train_auto.py: scelta della GPU, del backend e dell'ambiente del figlio, con la sonda di PyTorch,
vram e subprocess sostituiti da mock (nessuna GPU, nessun training).

Uso: python test_train_auto.py
"""
import io
import os
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import train_auto

IGPU = {"index": 0, "name": "AMD Radeon(TM) Graphics", "vram_gib": 0.5, "free_gib": 0.4, "integrated": True}
DGPU = {"index": 1, "name": "AMD Radeon RX 7800 XT", "vram_gib": 16.0, "free_gib": 15.0, "integrated": False}
SMALL = {"index": 2, "name": "NVIDIA GTX", "vram_gib": 6.0, "free_gib": 6.0, "integrated": False}


def run_main(info, argv, unsloth=False, system="Linux", msvc=None):
    """Esegue main() e restituisce (stdout, env e comando passati a subprocess.call, mock di vram.check)."""
    called = {}

    def fake_call(cmd, env):
        called["cmd"], called["env"] = cmd, env
        return 0

    out = io.StringIO()
    with mock.patch.object(train_auto, "probe", return_value=info), \
            mock.patch.object(train_auto.vram, "check") as check, \
            mock.patch("importlib.util.find_spec", return_value=object() if unsloth else None), \
            mock.patch("platform.system", return_value=system), \
            mock.patch.object(train_auto, "msvc_env", return_value=msvc), \
            mock.patch("subprocess.call", side_effect=fake_call), \
            mock.patch.object(sys, "argv", ["train_auto.py", *argv]), redirect_stdout(out):
        try:
            train_auto.main()
            code = None
        except SystemExit as e:
            code = e.code
    return out.getvalue(), called, check, code


class MainTest(unittest.TestCase):
    def test_sceglie_la_discreta_con_piu_vram_e_non_l_igpu(self):
        info = {"cuda": None, "hip": "6.2", "gpus": [IGPU, SMALL, DGPU]}
        out, called, check, _ = run_main(info, [])
        self.assertEqual("1", called["env"]["HIP_VISIBLE_DEVICES"])
        self.assertIn("GPU 1 = AMD Radeon RX 7800 XT", out)
        self.assertEqual(16.0, check.call_args.args[0])

    def test_nvidia_imposta_cuda_visible_devices(self):
        info = {"cuda": "12.4", "hip": None, "gpus": [SMALL]}
        out, called, _, _ = run_main(info, [])
        self.assertEqual("2", called["env"]["CUDA_VISIBLE_DEVICES"])
        self.assertIn("Hardware: NVIDIA", out)
        self.assertEqual("utf-8", called["env"]["PYTHONIOENCODING"])

    def test_solo_igpu_viene_usata_come_ripiego(self):
        info = {"cuda": None, "hip": "6.2", "gpus": [IGPU]}
        out, called, _, _ = run_main(info, [])
        self.assertEqual("0", called["env"]["HIP_VISIBLE_DEVICES"])

    def test_nessuna_gpu_si_ferma(self):
        _, called, _, code = run_main({"cuda": None, "hip": None, "gpus": []}, [])
        self.assertIn("Nessuna GPU", code)
        self.assertEqual({}, called)

    def test_indice_forzato_inesistente(self):
        info = {"cuda": None, "hip": "6.2", "gpus": [IGPU, DGPU]}
        _, called, _, code = run_main(info, ["--gpu", "5"])
        self.assertIn("GPU 5 non trovata", code)
        self.assertIn("[0, 1]", code)
        self.assertEqual({}, called)

    def test_indice_forzato_zero_non_e_ignorato(self):
        info = {"cuda": None, "hip": "6.2", "gpus": [IGPU, DGPU]}
        _, called, _, _ = run_main(info, ["--gpu", "0"])
        self.assertEqual("0", called["env"]["HIP_VISIBLE_DEVICES"])

    def test_dry_run_non_lancia_nulla(self):
        info = {"cuda": None, "hip": "6.2", "gpus": [DGPU]}
        out, called, _, _ = run_main(info, ["--dry-run"])
        self.assertEqual({}, called)
        self.assertIn("Comando:", out)

    def test_opzioni_sconosciute_passano_a_train_lora(self):
        info = {"cuda": None, "hip": "6.2", "gpus": [DGPU]}
        _, called, _, _ = run_main(info, ["--max-steps", "30", "--merge"])
        self.assertEqual([sys.executable, str(train_auto.HERE / "train_lora.py"), "--max-steps", "30", "--merge"], called["cmd"])

    def test_soglia_vram_passata_a_check(self):
        info = {"cuda": None, "hip": "6.2", "gpus": [DGPU]}
        _, _, check, _ = run_main(info, ["--max-vram-held", "25"])
        self.assertEqual(25, check.call_args.args[2])

    def test_unsloth_installato_su_linux(self):
        info = {"cuda": "12.4", "hip": None, "gpus": [SMALL]}
        out, called, _, _ = run_main(info, [], unsloth=True)
        self.assertIn("--unsloth", called["cmd"])
        self.assertIn("Backend: Unsloth", out)

    def test_unsloth_su_windows_senza_msvc_ripiega_su_peft(self):
        info = {"cuda": "12.4", "hip": None, "gpus": [SMALL]}
        out, called, _, _ = run_main(info, [], unsloth=True, system="Windows", msvc=None)
        self.assertNotIn("--unsloth", called["cmd"])
        self.assertIn("MSVC Build Tools non trovati", out)

    def test_unsloth_su_windows_con_msvc_aggiunge_l_ambiente(self):
        info = {"cuda": "12.4", "hip": None, "gpus": [SMALL]}
        _, called, _, _ = run_main(info, [], unsloth=True, system="Windows", msvc={"INCLUDE": "C:\\vc\\include"})
        self.assertIn("--unsloth", called["cmd"])
        self.assertEqual("C:\\vc\\include", called["env"]["INCLUDE"])

    def test_senza_unsloth_peft_puro(self):
        info = {"cuda": "12.4", "hip": None, "gpus": [SMALL]}
        out, called, _, _ = run_main(info, [], unsloth=False)
        self.assertNotIn("--unsloth", called["cmd"])
        self.assertIn("peft puro", out)


class ProbeTest(unittest.TestCase):
    def test_prende_l_ultima_riga_json_ignorando_il_rumore(self):
        stdout = 'avviso di torch\n{"cuda": null, "hip": "6.2", "gpus": []}\n'
        with mock.patch("subprocess.run", return_value=SimpleNamespace(stdout=stdout, stderr="", returncode=0)):
            self.assertEqual({"cuda": None, "hip": "6.2", "gpus": []}, train_auto.probe())

    def test_errore_di_torch_esce_con_la_coda_dello_stderr(self):
        with mock.patch("subprocess.run", return_value=SimpleNamespace(stdout="", stderr="x" * 1000 + "FINE", returncode=1)):
            with self.assertRaises(SystemExit) as ctx:
                train_auto.probe()
        self.assertIn("FINE", str(ctx.exception))
        self.assertLess(len(str(ctx.exception)), 700)

    def test_nessuna_riga_json_e_un_errore_anche_con_codice_zero(self):
        with mock.patch("subprocess.run", return_value=SimpleNamespace(stdout="solo testo\n", stderr="", returncode=0)):
            with self.assertRaises(SystemExit):
                train_auto.probe()


class MsvcEnvTest(unittest.TestCase):
    def test_senza_vswhere_restituisce_none(self):
        with mock.patch.dict(os.environ, {"ProgramFiles(x86)": "/non/esiste"}):
            self.assertIsNone(train_auto.msvc_env())

    def test_vswhere_senza_installazioni(self):
        with mock.patch.object(Path, "exists", return_value=True), \
                mock.patch("subprocess.run", return_value=SimpleNamespace(stdout="\n", stderr="", returncode=0)):
            self.assertIsNone(train_auto.msvc_env())

    def test_legge_le_variabili_dopo_vcvars(self):
        calls = []

        def fake_run(cmd, **kw):
            calls.append(cmd)
            if isinstance(cmd, list):
                return SimpleNamespace(stdout="C:\\VS\n")
            return SimpleNamespace(stdout="INCLUDE=C:\\inc\nLIB=C:\\lib;D:\\x=y\nrumore senza uguale\n")

        with mock.patch.object(Path, "exists", return_value=True), mock.patch("subprocess.run", side_effect=fake_run):
            env = train_auto.msvc_env()
        self.assertEqual({"INCLUDE": "C:\\inc", "LIB": "C:\\lib;D:\\x=y"}, env)
        self.assertIn("vcvars64.bat", calls[1])


if __name__ == "__main__":
    unittest.main()
