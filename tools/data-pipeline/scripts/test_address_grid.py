#!/usr/bin/env python3
"""Test di .github/scripts/publish-regions/address-grid.sh con Pages (server HTTP locale), date, curl, gh, sleep,
Gradle e gli script di costruzione delle celle finti: quota del giorno (cksum % 30), celle scelte a mano non valide o
fuori dalla partizione, release delle celle (cksum % ADDRESS_CELL_RELEASES), indice pubblicato assente (solo il
seme) o non scaricabile, GH_TOKEN tolto ai programmi dei dati ma non a gh. Richiede bash.

Uso: python test_address_grid.py
"""
import tempfile
import unittest
from pathlib import Path

import shell_fixtures as fx

SCRIPT = ".github/scripts/publish-regions/address-grid.sh"
SEED = [f"8/{x}/0" for x in range(40)]

# date: `date -u +%s` vale FAKE_NOW, il resto passa al date vero (cerca nel PATH senza la cartella dei finti).
DATE = """\
if [ "$*" = "-u +%s" ] && [ -n "${FAKE_NOW-}" ]; then echo "$FAKE_NOW"; exit 0; fi
PATH="${PATH#*:}"
exec date "$@"
"""
CURL = """\
for a in "$@"; do
  case "$a" in *build.protomaps.com*) echo 200; exit 0 ;; esac
done
PATH="${PATH#*:}"
exec curl "$@"
"""
GH = 'echo "${GH_TOKEN-unset} $*" >> "$FAKE_BEHAVIOR/gh.log"\n'
# Argomenti: id, versione, sorgente, URL degli asset, cartella di uscita, indice pubblicato.
BUILD_CELL = """\
echo "${GH_TOKEN-unset}|$1|$4|$6" >> "$FAKE_BEHAVIOR/cells.log"
echo dati > "$5/cell-x--$2--addresses.pmtiles.xz"
"""
GRADLE = 'echo "${GH_TOKEN-unset} $*" > "$FAKE_BEHAVIOR/gradle.log"\n'
OVERTURE = """\
import os
open(os.path.join(os.environ["FAKE_BEHAVIOR"], "token-overture"), "w").write(os.environ.get("GH_TOKEN", "unset"))
print("2026-10-22.0")
"""


def cksum(text):
    """Il cksum di POSIX del testo (CRC con polinomio 0x04C11DB7, poi la lunghezza), come lo calcola lo script."""
    data = text.encode()
    crc = 0
    for byte in data + len(data).to_bytes((len(data).bit_length() + 7) // 8 or 1, "little"):
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ 0x04C11DB7 if crc & 0x80000000 else crc << 1) & 0xFFFFFFFF
    return ~crc & 0xFFFFFFFF


@unittest.skipUnless(fx.BASH, "serve bash")
class AddressGridTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.tree = Path(self.dir.name)
        scripts = "tools/data-pipeline/scripts/"
        for f in (SCRIPT, ".github/scripts/env.sh", scripts + "portable.sh", scripts + "lib.sh", scripts + "address_seed_uncovered.py"):
            fx.copy(f, self.tree)
        pipeline = self.tree / "tools/data-pipeline"
        fx.write(pipeline / "address-grid-seed.tsv", "".join(f"{c}\n" for c in SEED))
        fx.write(pipeline / "overture-address-sources.tsv", "")
        fx.write(pipeline / "scripts/build-address-cell.sh", "#!/usr/bin/env bash\n" + BUILD_CELL, executable=True)
        fx.write(pipeline / "scripts/overture_addresses.py", OVERTURE)
        fx.write(self.tree / "gradlew", "#!/usr/bin/env bash\n" + GRADLE, executable=True)
        for name, body in (("date", DATE), ("curl", CURL), ("gh", GH), ("sleep", "exit 0\n")):
            fx.shim(self.tree, name, body)
        self.temp = self.tree / "runner-temp"
        self.temp.mkdir()
        self.behavior = self.tree / "behavior"
        self.behavior.mkdir()

    def run_job(self, files=None, base=None, **env):
        proc = self.run_bash_job(files, base, **env)
        log = self.behavior / "cells.log"
        cells = [line.split("|") for line in log.read_text(encoding="utf-8").splitlines()] if log.exists() else []
        return proc, cells

    def run_bash_job(self, files, base, **env):
        with fx.serve(files or {}) as server:
            environment = fx.environment(
                self.tree, GITHUB_ACTIONS="true", GITHUB_REPOSITORY="o/r", GITHUB_RUN_NUMBER=7, GITHUB_RUN_ATTEMPT=1,
                RUNNER_TEMP=self.temp.as_posix(), PAGES_BASE_URL=base or server.base, FAKE_BEHAVIOR=self.behavior.as_posix(),
                GH_TOKEN="segreto", **env)
            return fx.run_bash([SCRIPT], self.tree, environment)

    def test_quota_del_giorno_senza_indice_pubblicato(self):
        # 404 sull'indice: la partizione e' il solo seme; di oggi sono le celle col cksum % 30 dello slot.
        slot = cksum(SEED[0]) % 30
        proc, cells = self.run_job(FAKE_NOW=slot * 86400 + 100)
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn(f"{len(SEED)} celle nella partizione attuale", proc.stdout)
        expected = [c for c in SEED if cksum(c) % 30 == slot]
        self.assertIn(SEED[0], expected)
        self.assertEqual(expected, [c[1] for c in cells])
        self.assertIn(f"(slot {slot}/30): {len(expected)} celle", proc.stdout)
        self.assertTrue(all(c[3] == "" for c in cells), "senza indice pubblicato nessun indice da passare")

    def test_release_della_cella_e_cksum_modulo_release(self):
        slot = cksum(SEED[0]) % 30
        proc, cells = self.run_job(FAKE_NOW=slot * 86400, ADDRESS_CELL_RELEASES=5)
        self.assertEqual(0, proc.returncode, proc.stderr)
        for _, cell_id, base, _ in cells:
            self.assertTrue(base.endswith(f"/releases/download/address-cells-{cksum(cell_id) % 5}"), (cell_id, base))

    def test_celle_scelte_a_mano(self):
        proc, cells = self.run_job(ADDRESS_CELLS_INPUT=" 8/3/0, 8/5/0 ")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["8/3/0", "8/5/0"], [c[1] for c in cells])
        self.assertIn("celle scelte a mano: 2", proc.stdout)

    def test_input_non_valido_e_un_errore(self):
        proc, cells = self.run_job(ADDRESS_CELLS_INPUT="8/3/0,abc")
        self.assertEqual(1, proc.returncode)
        self.assertIn("address_cells non valido: 'abc'", proc.stderr)
        self.assertEqual([], cells)

    def test_cella_fuori_dalla_partizione_e_un_errore(self):
        proc, cells = self.run_job(ADDRESS_CELLS_INPUT="9/9/9")
        self.assertEqual(1, proc.returncode)
        self.assertIn("la cella 9/9/9 non e' nella partizione attuale", proc.stderr)
        self.assertEqual([], cells)

    def test_indice_pubblicato_si_somma_al_seme_non_coperto(self):
        # Partizione = celle dell'indice (8/0/0, 7/9/9) + righe del seme non coperte (tutte tranne 8/0/0).
        index = b'{"cells":[{"id":"8/0/0"},{"id":"7/9/9"}]}'
        proc, cells = self.run_job({"/address-grid.json": index}, ADDRESS_CELLS_INPUT="7/9/9,8/0/0,8/7/0")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn(f"{len(SEED) + 1} celle nella partizione attuale", proc.stdout)
        self.assertEqual(["7/9/9", "8/0/0", "8/7/0"], [c[1] for c in cells])
        self.assertTrue(all(c[3].endswith("published-index.json") for c in cells), cells)

    def test_indice_non_scaricabile_e_un_errore(self):
        proc, cells = self.run_job(base="http://127.0.0.1:9")
        self.assertEqual(1, proc.returncode)
        self.assertIn("ERRORE: indice civici non scaricabile", proc.stderr)
        self.assertIn("HTTP 000", proc.stderr)
        self.assertEqual([], cells)

    def test_gh_vede_gh_token_e_i_programmi_dei_dati_no(self):
        proc, cells = self.run_job(
            {"/address-grid.json": b'{"cells":[{"id":"8/0/0"}]}'}, ADDRESS_CELLS_INPUT="8/0/0", ADDRESS_GRID_SEED=self.tree / "nessuno")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["unset"], [c[0] for c in cells])
        self.assertEqual("unset", (self.behavior / "token-overture").read_text(encoding="utf-8").strip())
        self.assertTrue((self.behavior / "gradle.log").read_text(encoding="utf-8").startswith("unset "))
        gh = (self.behavior / "gh.log").read_text(encoding="utf-8").splitlines()
        self.assertTrue(any(line.startswith("segreto release upload address-cells-") for line in gh), gh)


if __name__ == "__main__":
    unittest.main()
