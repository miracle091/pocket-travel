#!/usr/bin/env python3
"""Test di bench_result.py (benchmark pp/tg nello schema comune con l'app) e dell'opzione --bench di eval_gguf.py:
il programma di benchmark (subprocess.run) e' sostituito da un mock, nessun modello e nessun processo.

Uso: python test_bench_result.py
"""
import json
import runpy
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

import bench_result

HERE = Path(__file__).resolve().parent


def rows(pp, tg):
    base = {"build_number": 7000, "build_commit": "abc1234", "cpu_info": "AMD Ryzen", "n_threads": 8}
    return [{**base, "n_prompt": 512, "n_gen": 0, "samples_ts": pp}, {**base, "n_prompt": 0, "n_gen": 128, "samples_ts": tg}]


def completed(data):
    return mock.Mock(returncode=0, stdout=json.dumps(data).encode("utf-8"))


class BenchResultTest(unittest.TestCase):
    def test_cv_e_mediana(self):
        self.assertAlmostEqual(10.0, bench_result.cv_percent([9.0, 10.0, 11.0]))
        self.assertEqual(0.0, bench_result.cv_percent([5.0]))
        self.assertEqual(3.0, bench_result.stable_measure([5.0, 1.0, 3.0])["median_tps"])

    def test_record_nello_schema_comune(self):
        record = bench_result.build_record("/x/m.gguf", "a" * 64, rows([100.0] * 5, [10.0] * 5), 1)
        self.assertEqual(1, record["schema"])
        self.assertEqual("eval_gguf", record["source"])
        self.assertEqual("m.gguf", record["model"]["file"])
        self.assertEqual("b7000-abc1234", record["llama_cpp"])
        self.assertEqual("aaaaaaaaaaaa/b7000-abc1234/AMD Ryzen/t8/pp512tg128", record["comparison_key"])
        self.assertEqual(100.0, record["pp"]["median_tps"])
        self.assertFalse(record["unstable"])

    def test_con_strati_sulla_gpu_la_chiave_e_della_gpu(self):
        gpu = [{**r, "n_gpu_layers": 99, "gpu_info": "AMD Radeon RX"} for r in rows([100.0] * 5, [10.0] * 5)]
        self.assertEqual("aaaaaaaaaaaa/b7000-abc1234/AMD Radeon RX/t8/pp512tg128",
                         bench_result.build_record("/x/m.gguf", "a" * 64, gpu, 1)["comparison_key"])
        cpu = [{**r, "n_gpu_layers": 0, "gpu_info": "AMD Radeon RX"} for r in rows([100.0] * 5, [10.0] * 5)]
        self.assertEqual("AMD Ryzen", bench_result.build_record("/x/m.gguf", "a" * 64, cpu, 1)["device"])

    def test_llama_bench_fallito_mostra_lo_stderr(self):
        failed = mock.Mock(returncode=1, stdout=b"", stderr=b"device Vulkan9 not found")
        with mock.patch.object(bench_result, "find_llama_bin", return_value="llama-bench"), \
                mock.patch.object(bench_result.subprocess, "run", return_value=failed):
            with self.assertRaises(SystemExit) as error:
                bench_result.run_llama_bench("/x/m.gguf", "/bin")
        self.assertIn("device Vulkan9 not found", str(error.exception))

    def test_cv_oltre_il_10_per_cento_e_instabile(self):
        record = bench_result.build_record("m.gguf", "a" * 64, rows([10.0, 20.0, 10.0, 20.0, 10.0], [10.0] * 5), 1)
        self.assertTrue(record["pp"]["unstable"])
        self.assertTrue(record["unstable"])

    def test_cv_oltre_il_5_per_cento_aggiunge_altre_cinque_ripetizioni(self):
        noisy = rows([10.0, 11.0, 10.0, 11.0, 10.0], [10.0] * 5)
        with mock.patch("bench_result.find_llama_bin", return_value="bench-exe"), \
                mock.patch("bench_result.file_sha256", return_value="b" * 64), \
                mock.patch("subprocess.run", side_effect=[completed(noisy), completed(rows([10.0] * 5, [10.0] * 5))]) as run:
            record = bench_result.bench("m.gguf", HERE)
        self.assertEqual(2, run.call_count)
        self.assertEqual("5", run.call_args.args[0][run.call_args.args[0].index("-r") + 1])
        self.assertEqual(10, len(record["pp"]["samples_tps"]))

    def test_eval_gguf_bench_scrive_il_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "bench.json"
            argv = ["eval_gguf.py", "m.gguf", "--llama-cpp", str(HERE), "--bench", str(out)]
            with mock.patch.object(sys, "argv", argv), mock.patch("gpu_lock.gpu_job"), \
                    mock.patch.dict(sys.modules, {"gguf": types.SimpleNamespace(GGUFReader=None)}), \
                    mock.patch("bench_result.bench", return_value={"schema": 1}):
                with self.assertRaises(SystemExit) as ctx:
                    runpy.run_path(str(HERE / "eval_gguf.py"), run_name="__main__")
            self.assertEqual(0, ctx.exception.code)
            self.assertEqual({"schema": 1}, json.loads(out.read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
