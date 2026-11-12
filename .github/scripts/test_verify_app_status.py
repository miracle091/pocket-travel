#!/usr/bin/env python3
"""Test di verify-app-status.sh (firma di app-status.json con la chiave ufficiale di SyncConfig.kt), senza rete: bash e
openssl come sul runner. Le chiavi sono generate qui, usa e getta; il SyncConfig.kt e' finto."""

import base64
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
VERIFY = HERE / "verify-app-status.sh"
BASH = shutil.which("bash")
OPENSSL = shutil.which("openssl")
CONFIG = "core/sync/src/main/kotlin/com/pockettravel/core/sync/SyncConfig.kt"


def openssl(*args, cwd):
    result = subprocess.run([OPENSSL, *args], cwd=cwd, capture_output=True)
    assert result.returncode == 0, result.stderr.decode()
    return result.stdout


@unittest.skipUnless(BASH and OPENSSL, "servono bash e openssl nel PATH, come sul runner")
class VerifyAppStatusTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        # Percorsi relativi alla cartella temporanea: Git Bash su Windows non legge i percorsi "C:\..." di Python.
        openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", "key.pem", cwd=self.tmp)
        der = openssl("ec", "-in", "key.pem", "-pubout", "-outform", "DER", cwd=self.tmp)
        self.write_config(base64.b64encode(der).decode())
        (self.tmp / "app-status.json").write_text('{"publishedAt": 1}', encoding="utf-8")
        self.sign()

    def write_config(self, key):
        path = self.tmp / CONFIG
        path.parent.mkdir(parents=True, exist_ok=True)
        # Stessa forma del sorgente vero: la costante e il valore su due righe.
        path.write_text(f'object SyncConfig {{\n    const val OFFICIAL_PUBLIC_KEY =\n        "{key}"\n}}\n',
                        encoding="utf-8")

    def sign(self, key="key.pem"):
        openssl("dgst", "-sha256", "-sign", key, "-out", "app-status.json.sig", "app-status.json", cwd=self.tmp)

    def verify(self):
        return subprocess.run([BASH, str(VERIFY), "app-status.json"], cwd=self.tmp, capture_output=True, text=True)

    def test_firma_valida(self):
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("::error::", result.stderr)

    def test_file_alterato(self):
        (self.tmp / "app-status.json").write_text('{"publishedAt": 2}', encoding="utf-8")
        result = self.verify()
        self.assertEqual(result.returncode, 1)
        self.assertIn("::error::firma di app-status.json mancante o non valida", result.stderr)

    def test_firma_mancante(self):
        (self.tmp / "app-status.json.sig").unlink()
        result = self.verify()
        self.assertEqual(result.returncode, 1)
        self.assertIn("::error::firma di app-status.json mancante o non valida", result.stderr)

    def test_firma_di_un_altra_chiave(self):
        openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", "altra.pem", cwd=self.tmp)
        self.sign("altra.pem")
        result = self.verify()
        self.assertEqual(result.returncode, 1)
        self.assertIn("::error::", result.stderr)

    def test_chiave_non_trovata_nel_sorgente(self):
        (self.tmp / CONFIG).write_text("object SyncConfig {\n    const val ALTRO = \"x\"\n}\n", encoding="utf-8")
        result = self.verify()
        self.assertEqual(result.returncode, 1)
        self.assertIn("::error::OFFICIAL_PUBLIC_KEY non trovata", result.stderr)

    def test_sorgente_mancante(self):
        (self.tmp / CONFIG).unlink()
        result = self.verify()
        self.assertEqual(result.returncode, 1)
        self.assertIn("::error::OFFICIAL_PUBLIC_KEY non trovata", result.stderr)


if __name__ == "__main__":
    unittest.main()
