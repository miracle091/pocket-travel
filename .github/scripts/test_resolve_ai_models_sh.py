#!/usr/bin/env python3
"""Test di resolve-ai-models.sh (impronte dei modelli IA per app-status.json), senza rete: bash e openssl come sul
runner; gh, sleep e python3 sono finti eseguibili nel PATH (la logica di resolve_ai_models.py ha il suo test)."""

import base64
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
BASH = shutil.which("bash")
OPENSSL = shutil.which("openssl")
CONFIG = "core/sync/src/main/kotlin/com/pockettravel/core/sync/SyncConfig.kt"

# Finto gh: "release download" fallisce se fake/dl-fail esiste, altrimenti copia fake/online/* nella cartella --dir.
GH = """#!/usr/bin/env bash
echo "$1 $2" >> fake/gh.log
[ "$1 $2" = "release download" ] || exit 0
[ ! -e fake/dl-fail ] || exit 1
while [ $# -gt 0 ]; do
  [ "$1" = "--dir" ] && cp fake/online/* "$2"/
  shift
done
"""
SLEEP = "#!/usr/bin/env bash\n"
# Finto python3: registra gli argomenti dello script (non quelli di "-c", usato da portable.sh); fallisce se
# fake/py-fail esiste; altrimenti scrive un elenco di prova.
PYTHON3 = """#!/usr/bin/env bash
[ "$1" != "-c" ] || exit 0
echo "$*" >> fake/python.log
[ ! -e fake/py-fail ] || exit 1
echo '[]'
"""


def openssl(*args, cwd):
    result = subprocess.run([OPENSSL, *args], cwd=cwd, capture_output=True)
    assert result.returncode == 0, result.stderr.decode()
    return result.stdout


@unittest.skipUnless(BASH and OPENSSL, "servono bash e openssl nel PATH, come sul runner")
class ResolveAiModelsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        # Copia minima del repository: gli script si lanciano dalla radice e si caricano a vicenda.
        for rel in (".github/scripts/env.sh", ".github/scripts/retry.sh", ".github/scripts/verify-app-status.sh",
                    ".github/scripts/resolve-ai-models.sh", "tools/data-pipeline/scripts/portable.sh"):
            (self.tmp / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / rel, self.tmp / rel)
        (self.tmp / "bin").mkdir()
        for name, text in (("gh", GH), ("sleep", SLEEP), ("python3", PYTHON3)):
            (self.tmp / "bin" / name).write_bytes(text.encode())
            (self.tmp / "bin" / name).chmod(0o755)
        (self.tmp / "fake" / "online").mkdir(parents=True)
        (self.tmp / "Catalogo.kt").write_text("listOf()", encoding="utf-8")
        # Percorsi relativi alla cartella temporanea: Git Bash su Windows non legge i percorsi "C:\..." di Python.
        openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", "key.pem", cwd=self.tmp)
        der = openssl("ec", "-in", "key.pem", "-pubout", "-outform", "DER", cwd=self.tmp)
        config = self.tmp / CONFIG
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text('const val OFFICIAL_PUBLIC_KEY =\n    "' + base64.b64encode(der).decode() + '"\n',
                          encoding="utf-8")

    def publish_online(self, signed=True):
        online = self.tmp / "fake" / "online"
        (online / "app-status.json").write_text('{"publishedAt": 1}', encoding="utf-8")
        if signed:
            openssl("dgst", "-sha256", "-sign", "key.pem", "-out", "fake/online/app-status.json.sig",
                    "fake/online/app-status.json", cwd=self.tmp)

    def run_script(self):
        env = dict(os.environ, GITHUB_ACTIONS="true", GITHUB_REPOSITORY="fork/pocket-travel")
        env["PATH"] = str(self.tmp / "bin") + os.pathsep + env["PATH"]
        return subprocess.run([BASH, ".github/scripts/resolve-ai-models.sh", "Catalogo.kt", "ai-models.json"],
                              cwd=self.tmp, env=env, capture_output=True, text=True)

    def python_calls(self):
        path = self.tmp / "fake" / "python.log"
        return path.read_text(encoding="utf-8").splitlines() if path.exists() else []

    def test_download_fallito_usa_solo_il_catalogo(self):
        (self.tmp / "fake" / "dl-fail").write_text("", encoding="utf-8")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("::warning::app-status.json online non scaricabile", result.stderr)
        self.assertEqual(self.python_calls(), ["tools/data-pipeline/scripts/resolve_ai_models.py Catalogo.kt"])
        self.assertEqual((self.tmp / "ai-models.json").read_text(encoding="utf-8").strip(), "[]")
        # Tre tentativi di download prima di rinunciare.
        self.assertEqual((self.tmp / "fake" / "gh.log").read_text(encoding="utf-8").count("release download"), 3)

    def test_firma_non_verificata_usa_solo_il_catalogo(self):
        self.publish_online(signed=False)
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("::error::firma di", result.stderr)
        self.assertIn("::warning::app-status.json online non verificato", result.stderr)
        self.assertEqual(self.python_calls(), ["tools/data-pipeline/scripts/resolve_ai_models.py Catalogo.kt"])

    def test_firma_valida_passa_anche_l_app_status_online(self):
        self.publish_online()
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("::warning::", result.stderr)
        calls = self.python_calls()
        self.assertEqual(len(calls), 1)
        self.assertRegex(calls[0], r"resolve_ai_models\.py Catalogo\.kt \S+app-status\.json$")
        self.assertEqual((self.tmp / "ai-models.json").read_text(encoding="utf-8").strip(), "[]")

    def test_se_nessuna_impronta_coincide_lo_script_fallisce(self):
        (self.tmp / "fake" / "py-fail").write_text("", encoding="utf-8")
        (self.tmp / "fake" / "dl-fail").write_text("", encoding="utf-8")
        self.assertNotEqual(self.run_script().returncode, 0)


if __name__ == "__main__":
    unittest.main()
