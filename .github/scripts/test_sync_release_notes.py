#!/usr/bin/env python3
"""Test di sync-release-notes.sh (note delle release dal CHANGELOG.md), senza rete: bash come sul runner, gh sostituito
da un finto eseguibile nel PATH che serve le release da file e registra le modifiche."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
BASH = shutil.which("bash")
URL = "https://github.com/fork/pocket-travel/blob/main/CHANGELOG.md"

# Finto gh: "release list" stampa fake/tags; "release view" stampa fake/body-<tag> (errore se manca);
# "release edit" copia le note in fake/edit-<tag> e conta le chiamate in fake/edits.log.
GH = """#!/usr/bin/env bash
case "$1 $2" in
  "release list") cat fake/tags ;;
  "release view") cat "fake/body-$3" ;;
  "release edit")
    tag="$3"
    echo "$tag" >> fake/edits.log
    while [ $# -gt 0 ]; do
      [ "$1" = "--notes-file" ] && cp "$2" "fake/edit-$tag.md"
      shift
    done ;;
esac
"""
CHANGELOG = """# Changelog

## [0.3.0] - 2026-03-01

### Aggiunto

- terza

## [0.2.0] - 2026-02-01

### Corretto

- seconda
- seconda bis

## [0.1.0]

- prima
"""
SECTION_020 = "\n### Corretto\n\n- seconda\n- seconda bis\n"
NOTES_020 = f"Changelog completo: {URL}\n\n{SECTION_020}"
NOTES_010 = f"Changelog completo: {URL}\n\n\n- prima\n"


@unittest.skipUnless(BASH, "serve bash nel PATH, come sul runner")
class SyncReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        # Copia minima del repository: lo script si lancia dalla radice e carica env.sh e retry.sh.
        for rel in (".github/scripts/env.sh", ".github/scripts/retry.sh", ".github/scripts/sync-release-notes.sh",
                    "tools/data-pipeline/scripts/portable.sh"):
            (self.tmp / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / rel, self.tmp / rel)
        (self.tmp / "bin").mkdir()
        (self.tmp / "bin" / "gh").write_bytes(GH.encode())
        (self.tmp / "bin" / "gh").chmod(0o755)
        (self.tmp / "fake").mkdir()
        self.write("CHANGELOG.md", CHANGELOG)

    def write(self, name, text):
        # newline="": il contenuto resta quello dato (anche CRLF), su Windows come su Linux.
        with open(self.tmp / name, "w", encoding="utf-8", newline="") as f:
            f.write(text)

    def read(self, name):
        path = self.tmp / name
        return path.read_bytes().decode("utf-8") if path.exists() else None

    def run_script(self, tags):
        self.write("fake/tags", "".join(t + "\n" for t in tags))
        env = dict(os.environ, GITHUB_ACTIONS="true", GITHUB_REPOSITORY="fork/pocket-travel")
        env["PATH"] = str(self.tmp / "bin") + os.pathsep + env["PATH"]
        return subprocess.run([BASH, ".github/scripts/sync-release-notes.sh"], cwd=self.tmp, env=env,
                              capture_output=True, text=True)

    def test_la_release_prende_la_sua_sezione_del_changelog(self):
        self.write("fake/body-v0.2.0", "vecchie note")
        result = self.run_script(["v0.2.0"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read("fake/edits.log"), "v0.2.0\n")
        self.assertEqual(self.read("fake/edit-v0.2.0.md"), NOTES_020)
        self.assertIn("v0.2.0: note aggiornate dal changelog", result.stdout)

    def test_piu_release_ognuna_con_la_propria_sezione(self):
        self.write("fake/body-v0.2.0", "x")
        self.write("fake/body-v0.1.0", "y")
        result = self.run_script(["v0.2.0", "v0.1.0"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read("fake/edits.log"), "v0.2.0\nv0.1.0\n")
        self.assertEqual(self.read("fake/edit-v0.2.0.md"), NOTES_020)
        # Anche l'intestazione senza data e l'ultima sezione del file.
        self.assertEqual(self.read("fake/edit-v0.1.0.md"), NOTES_010)

    def test_note_gia_uguali_non_si_modificano_anche_con_righe_vuote_diverse(self):
        self.write("fake/body-v0.2.0", NOTES_020.rstrip("\n") + "\n\n\n")
        result = self.run_script(["v0.2.0"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNone(self.read("fake/edits.log"))
        self.assertIn("v0.2.0: note gia' allineate", result.stdout)

    def test_note_della_release_con_crlf_contano_come_uguali(self):
        self.write("fake/body-v0.2.0", NOTES_020.replace("\n", "\r\n"))
        result = self.run_script(["v0.2.0"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNone(self.read("fake/edits.log"))

    def test_changelog_con_crlf_da_la_stessa_sezione(self):
        self.write("CHANGELOG.md", CHANGELOG.replace("\n", "\r\n"))
        self.write("fake/body-v0.2.0", NOTES_020)
        self.write("fake/body-v0.1.0", NOTES_010)
        result = self.run_script(["v0.2.0", "v0.1.0"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNone(self.read("fake/edits.log"), result.stdout)

    def test_release_senza_sezione_resta_com_e(self):
        self.write("fake/body-v9.9.9", "note scritte a mano")
        result = self.run_script(["v9.9.9"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNone(self.read("fake/edits.log"))
        self.assertIn("v9.9.9: nessuna sezione in CHANGELOG.md, note invariate", result.stdout)

    def test_la_versione_non_si_confonde_con_un_prefisso(self):
        # Il changelog ha 0.3.0 ma non 0.3: v0.3.0 non deve prendere altre sezioni, e v0.30.0 non ha sezione.
        self.write("fake/body-v0.30.0", "x")
        result = self.run_script(["v0.30.0"])
        self.assertIsNone(self.read("fake/edits.log"))
        self.assertIn("nessuna sezione", result.stdout)


if __name__ == "__main__":
    unittest.main()
