#!/usr/bin/env python3
"""Test di compose-app-status.sh e di update-app-status-models.jq (job di publish-apk.yml), senza rete: bash, jq e
sha256sum come sul runner."""

import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
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
        self.out = self.tmp / "app-status.json"

    def compose(self, version_code="15", apk=None):
        # Percorsi relativi alla cartella temporanea: Git Bash su Windows non legge i percorsi "C:\..." di Python.
        return subprocess.run(
            [BASH, str(COMPOSE), "0.15.0", version_code, self.models.name, (apk or self.apk).name, self.out.name],
            cwd=self.tmp, capture_output=True,
        )

    def test_scrive_versione_impronta_e_dimensione_dell_apk_e_i_modelli(self):
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

    def test_si_ferma_senza_apk_o_con_un_apk_vuoto(self):
        self.assertNotEqual(self.compose(apk=self.tmp / "manca.apk").returncode, 0)
        empty = self.tmp / "vuoto.apk"
        empty.write_bytes(b"")
        self.assertNotEqual(self.compose(apk=empty).returncode, 0)
        self.assertFalse(self.out.exists())

    def test_si_ferma_con_un_version_code_non_numerico(self):
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
