#!/usr/bin/env python3
"""Test di sign-files.sh (firma ECDSA P-256 del catalogo), senza rete: bash e openssl come sul runner. Le chiavi sono
generate qui, usa e getta; la chiave ufficiale non serve."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SIGN = HERE / "sign-files.sh"
BASH = shutil.which("bash")
OPENSSL = shutil.which("openssl")


def openssl(*args, cwd=None):
    result = subprocess.run([OPENSSL, *args], cwd=cwd, capture_output=True)
    assert result.returncode == 0, result.stderr.decode()
    return result.stdout


@unittest.skipUnless(BASH and OPENSSL, "servono bash e openssl nel PATH, come sul runner")
class SignFilesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as tmp:
            cls.ec_key = openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", cwd=tmp).decode()
            cls.other_curve_key = openssl("ecparam", "-name", "secp384r1", "-genkey", "-noout", cwd=tmp).decode()
            cls.rsa_key = openssl("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", cwd=tmp).decode()

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        # Percorsi relativi alla cartella temporanea: Git Bash su Windows non legge i percorsi "C:\..." di Python.
        (self.tmp / "runner-temp").mkdir()
        (self.tmp / "dati.json").write_text('{"publishedAt": 1}', encoding="utf-8")
        (self.tmp / "vuoto.json").write_bytes(b"")

    def run_script(self, *files, key=None, repository=None):
        env = {k: v for k, v in os.environ.items() if k not in ("MANIFEST_SIGNING_KEY", "GITHUB_REPOSITORY")}
        env["RUNNER_TEMP"] = "runner-temp"
        if key is not None:
            env["MANIFEST_SIGNING_KEY"] = key
        if repository is not None:
            env["GITHUB_REPOSITORY"] = repository
        return subprocess.run([BASH, str(SIGN), *files], cwd=self.tmp, env=env, capture_output=True)

    def assert_no_key_left(self):
        self.assertEqual(list((self.tmp / "runner-temp").iterdir()), [])

    def test_firma_verificabile_con_la_chiave_pubblica(self):
        result = self.run_script("dati.json", key=self.ec_key, repository="fork/pocket-travel")
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        (self.tmp / "key.pem").write_text(self.ec_key, encoding="utf-8")
        openssl("ec", "-in", "key.pem", "-pubout", "-out", "pub.pem", cwd=self.tmp)
        openssl("dgst", "-sha256", "-verify", "pub.pem", "-signature", "dati.json.sig", "dati.json", cwd=self.tmp)
        # Una firma non vale per un contenuto diverso.
        (self.tmp / "dati.json").write_text('{"publishedAt": 2}', encoding="utf-8")
        bad = subprocess.run([OPENSSL, "dgst", "-sha256", "-verify", "pub.pem", "-signature", "dati.json.sig",
                              "dati.json"], cwd=self.tmp, capture_output=True)
        self.assertNotEqual(bad.returncode, 0)
        self.assert_no_key_left()

    def test_piu_file_e_nessun_file(self):
        (self.tmp / "altro.json").write_text("{}", encoding="utf-8")
        result = self.run_script("dati.json", "altro.json", key=self.ec_key)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertTrue((self.tmp / "dati.json.sig").stat().st_size > 0)
        self.assertTrue((self.tmp / "altro.json.sig").stat().st_size > 0)
        self.assertNotEqual(self.run_script(key=self.ec_key).returncode, 0)

    def test_senza_chiave_fallisce_con_un_errore(self):
        for key in (None, ""):
            result = self.run_script("dati.json", key=key)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("::error::", result.stderr.decode())
            self.assertIn("MANIFEST_SIGNING_KEY", result.stderr.decode())
            self.assertFalse((self.tmp / "dati.json.sig").exists())

    def test_rifiuta_chiavi_che_non_sono_p256(self):
        for name, key in (("RSA", self.rsa_key), ("EC P-384", self.other_curve_key), ("testo", "non una chiave")):
            result = self.run_script("dati.json", key=key)
            self.assertNotEqual(result.returncode, 0, name)
            self.assertIn("::error::", result.stderr.decode(), name)
            self.assertIn("prime256v1", result.stderr.decode(), name)
            self.assertFalse((self.tmp / "dati.json.sig").exists(), name)
            self.assert_no_key_left()

    def test_file_mancante_o_vuoto_non_si_firma(self):
        for name in ("manca.json", "vuoto.json"):
            result = self.run_script(name, key=self.ec_key)
            self.assertNotEqual(result.returncode, 0, name)
            self.assertIn("::error::", result.stderr.decode(), name)
            self.assertIn("non trovato o vuoto", result.stderr.decode(), name)
            self.assertFalse((self.tmp / (name + ".sig")).exists(), name)
            self.assert_no_key_left()

    def test_nel_repository_ufficiale_rifiuta_una_chiave_diversa(self):
        result = self.run_script("dati.json", key=self.ec_key, repository="miracle091/pocket-travel")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("::error::", result.stderr.decode())
        self.assertIn("OFFICIAL_PUBLIC_KEY", result.stderr.decode())
        self.assertFalse((self.tmp / "dati.json.sig").exists())
        self.assert_no_key_left()


if __name__ == "__main__":
    unittest.main()
