#!/usr/bin/env python3
"""Test di get_llama_tools.py: verifica dello sha256 prima dell'estrazione, cartelle ROCm e scelta dei pacchetti
per backend, con urllib sostituito da un mock (nessun download).

Uso: python test_get_llama_tools.py
"""
import hashlib
import io
import os
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import get_llama_tools as g

NAMES = ("llama-server", "llama-imatrix", "llama-quantize", "llama-completion", "llama-bench")


def make_zip(names):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for n in names:
            z.writestr(n, "x")
    return buf.getvalue()


class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class DownloadTest(unittest.TestCase):
    def test_estrae_solo_se_lo_sha256_coincide(self):
        data = make_zip(["llama-server.exe"])
        url = f"{g.RELEASE}/pacchetto-finto.zip"
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.dict(g.SHA256, {"pacchetto-finto.zip": hashlib.sha256(data).hexdigest()}), \
                mock.patch("urllib.request.urlopen", return_value=FakeResponse(data)) as urlopen, \
                mock.patch.object(sys, "stderr", io.StringIO()):
            g.download(url, Path(tmp) / "dest")
            self.assertTrue((Path(tmp) / "dest" / "llama-server.exe").exists())
        request = urlopen.call_args.args[0]
        self.assertIn("PocketTravel", request.get_header("User-agent"))

    def test_sha256_diverso_non_estrae(self):
        data = make_zip(["llama-server.exe"])
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.dict(g.SHA256, {"pacchetto-finto.zip": "0" * 64}), \
                mock.patch("urllib.request.urlopen", return_value=FakeResponse(data)), \
                mock.patch.object(sys, "stderr", io.StringIO()):
            with self.assertRaises(SystemExit) as ctx:
                g.download(f"{g.RELEASE}/pacchetto-finto.zip", Path(tmp) / "dest")
            self.assertIn("non estraggo", str(ctx.exception))
            self.assertFalse((Path(tmp) / "dest").exists())


class CatalogTest(unittest.TestCase):
    def test_sha256_ben_formati_e_coerenti_con_la_build(self):
        for name, digest in g.SHA256.items():
            self.assertRegex(digest, r"^[0-9a-f]{64}$", name)
            self.assertTrue(name.endswith(".zip"))
        self.assertTrue(all(g.LLAMA_BUILD in n for n in g.SHA256 if not n.startswith("cudart")))

    def test_ogni_backend_ha_un_pacchetto_con_sha256(self):
        """main() costruisce il nome del pacchetto come llama-<build>-bin-win-<asset>-x64.zip."""
        for backend in ("cpu", "vulkan", "rocm", "cuda-12.4", "cuda-13.4"):
            asset = "rocm-10.0" if backend == "rocm" else backend
            self.assertIn(f"llama-{g.LLAMA_BUILD}-bin-win-{asset}-x64.zip", g.SHA256)
        for cuda in ("cuda-12.4", "cuda-13.4"):
            self.assertIn(f"cudart-llama-bin-win-{cuda}-x64.zip", g.SHA256)


class RocmLibDirsTest(unittest.TestCase):
    def test_hip_path_ha_la_precedenza(self):
        with mock.patch.dict(os.environ, {"HIP_PATH": "/opt/hip"}):
            self.assertEqual([str(Path("/opt/hip") / "bin")], g.rocm_lib_dirs())

    def test_wheel_rocm_di_pytorch(self):
        with tempfile.TemporaryDirectory() as tmp:
            for sub in ("_rocm_sdk_libraries_gfx110X/bin", "_rocm_sdk_core/bin", "altro/bin"):
                (Path(tmp) / sub).mkdir(parents=True)
            env = {k: v for k, v in os.environ.items() if k != "HIP_PATH"}
            with mock.patch.dict(os.environ, env, clear=True), mock.patch("site.getsitepackages", return_value=[tmp]):
                dirs = g.rocm_lib_dirs()
        self.assertEqual(2, len(dirs))
        self.assertTrue(any("_rocm_sdk_libraries_" in d for d in dirs))
        self.assertTrue(any("_rocm_sdk_core" in d for d in dirs))

    def test_nessuna_cartella(self):
        env = {k: v for k, v in os.environ.items() if k != "HIP_PATH"}
        with mock.patch.dict(os.environ, env, clear=True), mock.patch("site.getsitepackages", return_value=["/non/esiste"]):
            self.assertEqual([], g.rocm_lib_dirs())


def run_main(argv, dest, files=NAMES):
    """main() con download simulato che crea i file indicati; restituisce (stdout, URL scaricati)."""
    urls = []

    def fake_download(url, target):
        urls.append(url)
        Path(target).mkdir(parents=True, exist_ok=True)
        for n in files:
            (Path(target) / f"{n}.exe").write_text("", encoding="utf-8")

    out = io.StringIO()
    with mock.patch.object(g, "download", side_effect=fake_download), \
            mock.patch.object(sys, "argv", ["get_llama_tools.py", *argv, "--dest", str(dest)]), redirect_stdout(out):
        g.main()
    return out.getvalue(), urls


class MainTest(unittest.TestCase):
    def test_cpu_scarica_un_pacchetto_e_stampa_la_cartella(self):
        with tempfile.TemporaryDirectory() as tmp:
            out, urls = run_main(["cpu"], tmp)
        self.assertEqual([f"{g.RELEASE}/llama-{g.LLAMA_BUILD}-bin-win-cpu-x64.zip"], urls)
        self.assertEqual(str(Path(tmp) / f"{g.LLAMA_BUILD}-cpu"), out.strip())

    def test_cuda_scarica_anche_il_runtime(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, urls = run_main(["cuda-12.4"], tmp)
        self.assertEqual(2, len(urls))
        self.assertTrue(urls[1].endswith("cudart-llama-bin-win-cuda-12.4-x64.zip"))

    def test_download_gia_fatto_non_si_ripete(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp) / f"{g.LLAMA_BUILD}-vulkan"
            dest.mkdir()
            for n in NAMES:
                (dest / f"{n}.exe").write_text("", encoding="utf-8")
            _, urls = run_main(["vulkan"], tmp)
        self.assertEqual([], urls)

    def test_manca_un_eseguibile(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(SystemExit) as ctx:
                run_main(["cpu"], tmp, files=("llama-server", "llama-imatrix"))
        self.assertIn("llama-quantize, llama-completion, llama-bench", str(ctx.exception))

    def test_rocm_senza_hipblas_esce(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(g, "rocm_lib_dirs", return_value=[tmp]):
            with self.assertRaises(SystemExit) as ctx:
                run_main(["rocm"], tmp)
        self.assertIn("hipblas.dll non trovata", str(ctx.exception))

    def test_rocm_scrive_extra_path_e_usa_il_pacchetto_hip(self):
        with tempfile.TemporaryDirectory() as tmp:
            libs = Path(tmp) / "libs"
            libs.mkdir()
            (libs / "hipblas.dll").write_text("", encoding="utf-8")
            with mock.patch.object(g, "rocm_lib_dirs", return_value=[str(libs)]):
                out, urls = run_main(["rocm"], Path(tmp) / "out")
            extra = Path(out.strip()) / "extra-path.txt"
            self.assertEqual(str(libs), extra.read_text(encoding="utf-8"))
        self.assertIn("rocm-10.0", urls[0])


if __name__ == "__main__":
    unittest.main()
