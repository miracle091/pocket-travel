#!/usr/bin/env python3
"""Test di apk-fingerprint.sh, compose-app-status.sh e update-app-status-models.jq (job di publish-apk.yml), senza rete:
bash, jq e sha256sum come sul runner."""

import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
FINGERPRINT = HERE / "apk-fingerprint.sh"
COMPOSE = HERE / "compose-app-status.sh"
UPDATE_MODELS = HERE / "update-app-status-models.jq"
BASH = shutil.which("bash") or "bash"
JQ = shutil.which("jq") or "jq"

MODEL = {"modelId": "qwen3.5-0.8b", "modelVersion": "Qwen3.5-0.8B-Q4_K_M.gguf", "sha256": "a" * 64, "sizeBytes": 559000000}


class ComposeAppStatusTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.apk = self.tmp / "app-release.apk"
        self.apk.write_bytes(b"PK\x03\x04 apk di prova" * 100)
        self.models = self.tmp / "ai-models.json"
        self.models.write_text(json.dumps([MODEL]), encoding="utf-8")
        self.fingerprint = self.tmp / "apk-fingerprint.json"
        self.out = self.tmp / "app-status.json"

    # Percorsi relativi alla cartella temporanea: Git Bash su Windows non legge i percorsi "C:\..." di Python.
    def run_script(self, *args):
        return subprocess.run([BASH, *map(str, args)], cwd=self.tmp, capture_output=True)

    def compose(self, version_code="15"):
        return self.run_script(COMPOSE, "0.15.0", version_code, self.models.name, self.fingerprint.name, self.out.name)

    def test_scrive_versione_impronta_e_dimensione_dell_apk_e_i_modelli(self):
        result = self.run_script(FINGERPRINT, self.apk.name, self.fingerprint.name)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        result = self.compose()
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        status = json.loads(self.out.read_text(encoding="utf-8"))
        data = self.apk.read_bytes()
        self.assertEqual(status["appVersion"], {
            "versionName": "0.15.0", "versionCode": 15,
            "apkSha256": hashlib.sha256(data).hexdigest(), "apkSizeBytes": len(data),
        })
        self.assertEqual(status["aiModels"], [MODEL])
        self.assertIsInstance(status["publishedAt"], int)

    def test_senza_impronta_scrive_solo_la_versione(self):
        self.fingerprint.write_text("{}", encoding="utf-8")
        result = self.compose()
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        status = json.loads(self.out.read_text(encoding="utf-8"))
        self.assertEqual(status["appVersion"], {"versionName": "0.15.0", "versionCode": 15})

    def test_si_ferma_con_un_impronta_incompleta_o_non_valida(self):
        for bad in ({"apkSha256": "a" * 64}, {"apkSha256": "a" * 63, "apkSizeBytes": 10},
                    {"apkSha256": "a" * 64, "apkSizeBytes": 0}, {"apkSha256": "a" * 64, "apkSizeBytes": 10, "url": "x"},
                    '{"versionCode": 999} {}', "", "[]"):
            self.fingerprint.write_text(bad if isinstance(bad, str) else json.dumps(bad), encoding="utf-8")
            self.assertNotEqual(self.compose().returncode, 0, bad)
            self.assertFalse(self.out.exists())

    def test_l_impronta_si_ferma_senza_apk_o_con_un_apk_vuoto(self):
        self.assertNotEqual(self.run_script(FINGERPRINT, "manca.apk", self.fingerprint.name).returncode, 0)
        (self.tmp / "vuoto.apk").write_bytes(b"")
        self.assertNotEqual(self.run_script(FINGERPRINT, "vuoto.apk", self.fingerprint.name).returncode, 0)

    def test_si_ferma_con_un_version_code_non_numerico(self):
        self.fingerprint.write_text("{}", encoding="utf-8")
        self.assertNotEqual(self.compose(version_code="15; rm -rf").returncode, 0)
        self.assertFalse(self.out.exists())


class UpdateAppStatusModelsTest(unittest.TestCase):
    def test_cambia_solo_le_impronte_dei_modelli_e_publishedAt(self):
        app_version = {"versionName": "0.15.0", "versionCode": 15, "apkSha256": "c" * 64, "apkSizeBytes": 19000000}
        other = dict(MODEL, modelId="qwen3.5-2b", sha256="d" * 64)
        online = {"publishedAt": 1, "appVersion": app_version, "aiModels": [MODEL, other]}
        updates = [{"modelId": "qwen3.5-0.8b", "sha256": "e" * 64, "sizeBytes": 560000000}]
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "online.json").write_text(json.dumps(online), encoding="utf-8")
            Path(tmp, "updates.json").write_text(json.dumps(updates), encoding="utf-8")
            shutil.copy(UPDATE_MODELS, Path(tmp, "filter.jq"))
            result = subprocess.run(
                [JQ, "--slurpfile", "updates", "updates.json", "--argjson", "publishedAt", "2",
                 "-f", "filter.jq", "online.json"],
                cwd=tmp, capture_output=True,
            )
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        status = json.loads(result.stdout)
        self.assertEqual(status["publishedAt"], 2)
        self.assertEqual(status["appVersion"], app_version)
        self.assertEqual(status["aiModels"], [dict(MODEL, sha256="e" * 64, sizeBytes=560000000), other])


if __name__ == "__main__":
    unittest.main()
