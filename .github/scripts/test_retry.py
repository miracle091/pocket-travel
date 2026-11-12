#!/usr/bin/env python3
"""Test di retry.sh (retry ed ensure_release), senza rete: bash come sul runner, gh e sleep sostituiti da finti
eseguibili nel PATH."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
RETRY = HERE / "retry.sh"
BASH = shutil.which("bash")

# Finto gh: registra gli argomenti (uno per campo, separati da tab); "release view" riesce se FAKE_VIEW_OK=1.
GH = """#!/usr/bin/env bash
printf '%s\\t' "$@" >> calls.log
echo >> calls.log
if [ "$1 $2" = "release view" ]; then [ "${FAKE_VIEW_OK:-}" = "1" ]; exit; fi
exit 0
"""
# Finto sleep: registra l'attesa richiesta senza aspettare.
SLEEP = """#!/usr/bin/env bash
echo "$@" >> sleeps.log
"""
# Comando che fallisce finche' non e' stato chiamato FAKE_SUCCEED_AT volte (0 = mai).
FLAKY = """flaky() {
  local n; n=$(cat count 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > count
  [ "$FAKE_SUCCEED_AT" -ne 0 ] && [ "$n" -ge "$FAKE_SUCCEED_AT" ]
}
"""


@unittest.skipUnless(BASH, "serve bash nel PATH, come sul runner")
class RetryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        bin_dir = self.tmp / "bin"
        bin_dir.mkdir()
        for name, text in (("gh", GH), ("sleep", SLEEP)):
            (bin_dir / name).write_bytes(text.encode())
            (bin_dir / name).chmod(0o755)

    # Percorsi relativi alla cartella temporanea: Git Bash su Windows non legge i percorsi "C:\..." di Python.
    def run_bash(self, body, **extra):
        env = dict(os.environ, GITHUB_REPOSITORY="fork/pocket-travel", **extra)
        env["PATH"] = str(self.tmp / "bin") + os.pathsep + env["PATH"]
        script = f'source "{RETRY.as_posix()}"\n{FLAKY}\n{body}\n'
        return subprocess.run([BASH, "-c", script], cwd=self.tmp, env=env, capture_output=True, text=True)

    def read(self, name):
        path = self.tmp / name
        return path.read_text(encoding="utf-8") if path.exists() else ""

    def test_riesce_al_terzo_tentativo_con_attese_crescenti(self):
        result = self.run_bash("retry flaky", FAKE_SUCCEED_AT="3")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read("count").strip(), "3")
        self.assertEqual(self.read("sleeps.log").split(), ["20", "40"])
        self.assertEqual(result.stderr.count("::warning::"), 2)

    def test_se_riesce_subito_non_aspetta(self):
        result = self.run_bash("retry flaky", FAKE_SUCCEED_AT="1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read("count").strip(), "1")
        self.assertEqual(self.read("sleeps.log"), "")

    def test_se_fallisce_sempre_tenta_tre_volte_ed_esce_con_errore(self):
        result = self.run_bash("retry flaky", FAKE_SUCCEED_AT="0")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.read("count").strip(), "3")
        # Dopo l'ultimo tentativo non si aspetta.
        self.assertEqual(self.read("sleeps.log").split(), ["20", "40"])

    def test_n_imposta_il_numero_di_tentativi(self):
        result = self.run_bash("retry -n 5 flaky", FAKE_SUCCEED_AT="0")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.read("count").strip(), "5")
        self.assertEqual(self.read("sleeps.log").split(), ["20", "40", "60", "80"])
        result = self.run_bash("retry -n 1 flaky", FAKE_SUCCEED_AT="0")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.read("count").strip(), "6")

    def test_ensure_release_non_crea_una_release_esistente(self):
        result = self.run_bash('ensure_release v1 "Titolo" "Note"', FAKE_VIEW_OK="1", FAKE_SUCCEED_AT="0")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.read("calls.log").splitlines()
        self.assertEqual(len(calls), 1)
        self.assertTrue(calls[0].startswith("release\tview\tv1\t"), calls[0])
        self.assertNotIn("create", self.read("calls.log"))

    def test_ensure_release_crea_la_release_mancante_senza_il_badge_latest(self):
        result = self.run_bash('ensure_release v1 "Titolo della release" "Note della release"', FAKE_SUCCEED_AT="0")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [line.split("\t")[:-1] for line in self.read("calls.log").splitlines()]
        self.assertEqual(calls[0][:3], ["release", "view", "v1"])
        self.assertEqual(calls[1], ["release", "create", "v1", "--repo", "fork/pocket-travel", "--title",
                                    "Titolo della release", "--notes", "Note della release", "--latest=false"])
        self.assertEqual(len(calls), 2)


if __name__ == "__main__":
    unittest.main()
