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

# main() prende il lucchetto della GPU e accende il monitor: nei test nessuno dei due (il lucchetto vero farebbe aspettare
# il test finche' un training vero tiene la GPU)
_GPU_JOB = mock.patch.object(train_auto.gpu_lock, "gpu_job")


def setUpModule():
    _GPU_JOB.start()


def tearDownModule():
    _GPU_JOB.stop()


IGPU ={"index": 0, "name": "AMD Radeon(TM) Graphics", "vram_gib": 0.5, "free_gib": 0.4, "integrated": True}
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
            mock.patch.object(train_auto, "run_watched", side_effect=fake_call), \
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

    def test_variabile_gia_impostata_resta_sulla_gpu_fisica(self):
        # Con HIP_VISIBLE_DEVICES=1 PyTorch vede la sola discreta come device 0: il figlio deve restare su 1, non 0 (iGPU).
        info = {"cuda": None, "hip": "6.2", "gpus": [dict(DGPU, index=0)]}
        with mock.patch.dict(os.environ, {"HIP_VISIBLE_DEVICES": "1"}):
            _, called, _, _ = run_main(info, [])
        self.assertEqual("1", called["env"]["HIP_VISIBLE_DEVICES"])

    def test_indice_forzato_tradotto_dalla_lista_gia_impostata(self):
        info = {"cuda": None, "hip": "6.2", "gpus": [dict(SMALL, index=0), dict(DGPU, index=1)]}
        with mock.patch.dict(os.environ, {"HIP_VISIBLE_DEVICES": "3,2"}):
            _, called, _, _ = run_main(info, ["--gpu", "1"])
        self.assertEqual("2", called["env"]["HIP_VISIBLE_DEVICES"])

    def test_nessuna_gpu_si_ferma(self):
        _, called, _, code = run_main({"cuda": None, "hip": None, "gpus": []}, [])
        self.assertIn("Nessuna GPU", code)
        self.assertNotIn("nvidia-smi", code)
        self.assertEqual({}, called)

    def test_pytorch_cuda_senza_driver_nvidia(self):
        with mock.patch("shutil.which", return_value=None):
            _, called, _, code = run_main({"cuda": "13.0", "hip": None, "gpus": []}, [])
        self.assertIn("driver NVIDIA assenti", code)
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

    def test_watchdog_acceso_di_default(self):
        info = {"cuda": None, "hip": "6.2", "gpus": [DGPU]}
        with mock.patch.object(train_auto, "probe", return_value=info), mock.patch.object(train_auto.vram, "check"), \
                mock.patch("importlib.util.find_spec", return_value=None), \
                mock.patch("subprocess.call", return_value=0) as call, \
                mock.patch.object(train_auto, "run_watched", return_value=0) as watched, \
                mock.patch.object(sys, "argv", ["train_auto.py", "--merge"]), redirect_stdout(io.StringIO()), \
                self.assertRaises(SystemExit):
            train_auto.main()
        watched.assert_called_once()
        call.assert_not_called()

    def test_no_watchdog_lancia_senza_controllo(self):
        info = {"cuda": None, "hip": "6.2", "gpus": [DGPU]}
        with mock.patch.object(train_auto, "probe", return_value=info), mock.patch.object(train_auto.vram, "check"), \
                mock.patch("importlib.util.find_spec", return_value=None), \
                mock.patch("subprocess.call", return_value=0) as call, \
                mock.patch.object(train_auto, "run_watched", return_value=0) as watched, \
                mock.patch.object(sys, "argv", ["train_auto.py", "--no-watchdog", "--merge"]), \
                redirect_stdout(io.StringIO()), self.assertRaises(SystemExit):
            train_auto.main()
        watched.assert_not_called()
        self.assertEqual("--merge", call.call_args.args[0][-1])

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


class SpeedWatchdogTest(unittest.TestCase):
    def feed(self, dog, start, steps, seconds_per_step, t0=0.0):
        t = t0
        for step in range(start, start + steps):
            dog.step(t, step)
            t += seconds_per_step
        return t

    def test_velocita_costante_non_ferma(self):
        dog = train_auto.SpeedWatchdog(window=900)
        t = self.feed(dog, 1, 1000, 4.0)
        self.assertIsNone(dog.check(t))

    def test_velocita_raddoppiata_ferma(self):
        dog = train_auto.SpeedWatchdog(window=900)
        t = self.feed(dog, 1, 400, 4.0)
        self.assertIsNone(dog.check(t))
        t = self.feed(dog, 401, 200, 9.5, t0=t)
        self.assertIn("contro un migliore di 4.0", dog.check(t))

    def test_rallentamento_sotto_il_doppio_non_ferma(self):
        dog = train_auto.SpeedWatchdog(window=900)
        t = self.feed(dog, 1, 400, 4.0)
        dog.check(t)
        t = self.feed(dog, 401, 400, 7.0, t0=t)
        self.assertIsNone(dog.check(t))

    def test_nessun_passo_per_30_minuti_ferma(self):
        dog = train_auto.SpeedWatchdog(stall=1800)
        t = self.feed(dog, 1, 10, 4.0)
        self.assertIsNone(dog.check(t + 1700))
        self.assertIn("nessun passo", dog.check(t + 1810))

    def test_dopo_il_training_nessun_controllo(self):
        dog = train_auto.SpeedWatchdog(stall=1800)
        t = self.feed(dog, 1, 10, 4.0)
        dog.phase()  # merge, eval: lunghi e senza passi
        self.assertIsNone(dog.check(t + 7200))

    def test_prima_della_finestra_nessuna_misura(self):
        dog = train_auto.SpeedWatchdog(window=900)
        t = self.feed(dog, 1, 100, 30.0)
        self.assertIsNone(dog.check(t))


class RunWatchedTest(unittest.TestCase):
    CHILD = ("print('[fase] training', flush=True)\n"
             "for i in range(1, 4): print(f'[training] {i}/3', flush=True)\n"
             "print('fine', flush=True)\n")

    def run_watched(self, dog, max_restarts=2):
        out = io.BytesIO()
        fake_stdout = SimpleNamespace(buffer=out, flush=lambda: None)
        with mock.patch.object(sys, "stdout", fake_stdout), mock.patch("builtins.print"), \
                mock.patch.object(train_auto.time, "sleep"):
            code = train_auto.run_watched([sys.executable, "-c", self.CHILD], dict(os.environ),
                                          max_restarts=max_restarts, watchdog=dog, poll=5)
        return code, out.getvalue()

    def test_copia_l_output_e_restituisce_il_codice(self):
        code, out = self.run_watched(train_auto.SpeedWatchdog)
        self.assertEqual(0, code)
        self.assertIn(b"[training] 3/3", out)
        self.assertIn(b"fine", out)

    def test_rilancia_quando_il_watchdog_ferma(self):
        starts = []

        class StopAtFirstStep(train_auto.SpeedWatchdog):
            def __init__(self):
                super().__init__()
                starts.append(1)

            def check(self, now):
                return "lento" if self.samples else None

        code, out = self.run_watched(StopAtFirstStep, max_restarts=2)
        self.assertEqual(3, len(starts))  # primo lancio piu' due riavvii, poi lo lascia finire
        self.assertIn(b"fine", out)
        self.assertEqual(0, code)

    def test_la_migliore_velocita_passa_al_riavvio(self):
        bests = []

        class Slow(train_auto.SpeedWatchdog):
            def __init__(self):
                super().__init__()
                bests.append(None)

            def check(self, now):
                if len(bests) == 1:
                    self.best = 4.0  # misurata nel primo lancio
                    return "lento" if self.samples else None
                bests[-1] = self.best
                return None

        self.run_watched(Slow, max_restarts=1)
        self.assertEqual([None, 4.0], bests)


if __name__ == "__main__":
    unittest.main()
