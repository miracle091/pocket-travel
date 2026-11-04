#!/usr/bin/env python3
"""Test di build-address-cell.sh senza rete e senza Gradle (pmtiles e gradlew finti): in una divisione, una cella figlia
il cui generateAddresses fallisce fa fallire la cella (il genitore resta nell'indice), non la lascia senza voce; sotto
ADDRESS_CELL_MIN_ZOOM una cella si divide nelle sole figlie con civici. Richiede bash, jq, xz.

Uso: python test_build_address_cell.py
"""
import hashlib
import json
import lzma
import shutil
import tempfile
import unittest
from pathlib import Path

import shell_fixtures as fx
from test_seed_address_grid import directory, pmtiles
from address_cell_children import _seed

tile_id_to_zxy = _seed.tile_id_to_zxy
BASE_Z14 = (4 ** 14 - 1) // 3

SCRIPT = "tools/data-pipeline/scripts/build-address-cell.sh"
PMTILES = """#!/usr/bin/env bash
if [[ " $* " == *" --dry-run "* ]]; then echo "archive size of 1 MB"; exit 0; fi
printf 'z15' > "$3"
"""
# La cella genitore (12/1/1) genera un file che supera il tetto; le figlie falliscono.
GRADLE = r"""#!/usr/bin/env bash
args="$*"
if [[ "$args" == *'--cell "12/1/1"'* ]]; then
  out="$(printf '%s' "$args" | sed -n 's/^[^"]*"\([^"]*\)".*/\1/p')"
  head -c 4096 /dev/urandom > "$out"
  echo "indirizzi: 10 punti"
  exit 0
fi
exit 1
"""


@unittest.skipUnless(fx.BASH and shutil.which("jq") and shutil.which("xz"), "servono bash, jq e xz")
class BuildAddressCellTest(unittest.TestCase):
    def test_figlia_con_generate_addresses_fallito_fa_fallire_la_cella(self):
        with tempfile.TemporaryDirectory() as d:
            tree = Path(d)
            for f in (SCRIPT, "tools/data-pipeline/scripts/lib.sh", "tools/data-pipeline/scripts/portable.sh"):
                fx.copy(f, tree)
            fx.write(tree / "overture.tsv", "")
            fx.write(tree / "gradlew", GRADLE, executable=True)
            fx.shim(tree, "pmtiles", PMTILES.split("\n", 1)[1])
            env = fx.environment(tree, PMTILES_BIN="pmtiles", ADDRESS_CELL_MAX_BYTES=1, PYTHON_BIN="", ADDRESSES_ATTEMPTS=1)
            out = tree / "out"
            proc = fx.run_bash([tree / SCRIPT, "12/1/1", "2026.11.01.1.1", "http://127.0.0.1:9/x.pmtiles", "http://127.0.0.1:9/a", out], tree, env)
            self.assertEqual(1, proc.returncode, proc.stdout + proc.stderr)
            self.assertIn("figlia di una divisione", proc.stderr)
            self.assertEqual("", (out / "address-grid-entries.jsonl").read_text(encoding="utf-8"))


# Solo le celle z7 hanno civici, in un file piccolo sotto il tetto: le altre restano senza voce.
GRADLE_Z7_CELLS = r"""#!/usr/bin/env bash
args="$*"
if [[ "$args" != *'--cell "7/'* ]]; then echo "indirizzi: 0 punti"; exit 0; fi
out="$(printf '%s' "$args" | sed -n 's/^[^"]*"\([^"]*\)".*/\1/p')"
head -c 512 /dev/urandom > "$out"
echo "indirizzi: 10 punti"
"""
# Python vero per address_cell_children.py, ma senza duckdb: niente query Overture in rete.
PYTHON_WITHOUT_DUCKDB = """[ "$1" = "-c" ] && exit 1
exec python3 "$@"
"""
TWO_Z7_TILES = (BASE_Z14, BASE_Z14 + 4 ** 7)
PMTILES_TOO_BIG = 'echo "archive size of 900 MB"\n'
# Overpass (unica richiesta di rete in questi test): conteggio oltre ADDRESS_CELL_OVERPASS_MAX.
CURL_OVERPASS_TOO_MANY = """while [ "$#" -gt 0 ]; do [ "$1" = "-o" ] && out="$2"; shift; done
printf '{"elements":[{"tags":{"total":"999999"}}]}' > "$out"
"""


def cell_id(tile_id, z):
    _, x, y = tile_id_to_zxy(tile_id)
    return f"{z}/{x >> (14 - z)}/{y >> (14 - z)}"


@unittest.skipUnless(fx.BASH and shutil.which("jq") and shutil.which("xz"), "servono bash, jq e xz")
class MinZoomTest(unittest.TestCase):
    def run_cell(self, tree, cell, index, pmtiles_body=PMTILES.split("\n", 1)[1], curl_body=None, **env_extra):
        for f in (SCRIPT, "tools/data-pipeline/scripts/lib.sh", "tools/data-pipeline/scripts/portable.sh",
                  "tools/data-pipeline/scripts/address_cell_children.py", "tools/data-pipeline/scripts/seed-address-grid.py"):
            fx.copy(f, tree)
        fx.write(tree / "gradlew", GRADLE_Z7_CELLS, executable=True)
        fx.shim(tree, "pmtiles", pmtiles_body)
        if curl_body is not None:
            fx.shim(tree, "curl", curl_body)
        fx.shim(tree, "python-no-duckdb", PYTHON_WITHOUT_DUCKDB)
        fx.write(tree / "index.json", json.dumps(index))
        env = fx.environment(tree, PMTILES_BIN="pmtiles", PYTHON_BIN="python-no-duckdb", ADDRESSES_ATTEMPTS=1,
                             **{"ADDRESS_CELL_MIN_ZOOM": 7, **env_extra})
        out = tree / "out"
        proc = fx.run_bash([tree / SCRIPT, cell, "2026.11.04.1.1", "http://127.0.0.1:9/x.pmtiles", "http://127.0.0.1:9/a", out,
                            tree / "index.json"], tree, env)
        entries = out / "address-grid-entries.jsonl"
        ids = [json.loads(line)["id"] for line in entries.read_text(encoding="utf-8").splitlines()] if entries.exists() else []
        return proc, ids

    def test_cella_pubblicata_sotto_lo_zoom_minimo_si_divide_nelle_figlie_con_civici(self):
        parent_pmtiles = pmtiles(directory([(t, 1, 10, 10 * k) for k, t in enumerate(TWO_Z7_TILES)]))
        parent_xz = lzma.compress(parent_pmtiles)
        parent = cell_id(BASE_Z14, 2)
        with tempfile.TemporaryDirectory() as d, fx.serve({"/parent.xz": parent_xz}) as server:
            index = {"cells": [{"id": parent, "version": "2026.11.03.1.1", "search": {},
                                "fileXz": {"url": f"{server.base}/parent.xz", "sha256": hashlib.sha256(parent_xz).hexdigest()}}]}
            proc, ids = self.run_cell(Path(d), parent, index)
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        self.assertEqual(sorted(cell_id(t, 7) for t in TWO_Z7_TILES), sorted(ids))

    def test_file_pubblicato_con_sha256_diverso_lascia_la_cella(self):
        parent_xz = lzma.compress(pmtiles(directory([(BASE_Z14, 1, 10, 0)])))
        parent = cell_id(BASE_Z14, 2)
        with tempfile.TemporaryDirectory() as d, fx.serve({"/parent.xz": parent_xz}) as server:
            index = {"cells": [{"id": parent, "version": "2026.11.03.1.1", "search": {},
                                "fileXz": {"url": f"{server.base}/parent.xz", "sha256": "0" * 64}}]}
            proc, ids = self.run_cell(Path(d), parent, index)
        self.assertEqual(1, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("la cella resta", proc.stderr)
        self.assertEqual([], ids)

    def test_cella_gia_divisa_passa_alle_antenate_z7_delle_celle_pubblicate(self):
        # Sotto la z3 non pubblicata: una z9 (la sua z7 non e' pubblicata) e una z7 pubblicate di recente.
        z9, z7 = cell_id(BASE_Z14, 9), cell_id(BASE_Z14 + 4 ** 7, 7)
        index = {"cells": [{"id": i, "version": "2026.11.03.1.1", "search": {}, "fileXz": {"url": "http://127.0.0.1:9/none", "sha256": ""}}
                           for i in (z9, z7)]}
        with tempfile.TemporaryDirectory() as d:
            proc, ids = self.run_cell(Path(d), cell_id(BASE_Z14, 3), index, ADDRESS_CELL_MAX_AGE_DAYS=100000)
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn(f"-- {cell_id(BASE_Z14, 7)}: gia' divisa nell'indice pubblicato, passo alle 4 figlie", proc.stdout)
        self.assertEqual(sorted([z9, z7]), sorted(ids))

    def test_cella_nuova_sotto_lo_zoom_minimo_senza_civici_osm_passa_alle_4_figlie(self):
        # z15 troppo grandi e Overpass con troppi civici: nessun civico OSM, nessun errore.
        with tempfile.TemporaryDirectory() as d:
            proc, ids = self.run_cell(Path(d), "6/33/22", {"cells": []}, pmtiles_body=PMTILES_TOO_BIG, curl_body=CURL_OVERPASS_TOO_MANY)
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("-- 6/33/22: sotto lo zoom minimo senza civici OSM, divido in 4 celle figlie", proc.stdout)
        for child in ("7/66/44", "7/67/44", "7/66/45", "7/67/45"):
            self.assertIn(f"-- {child}: nessun civico", proc.stdout)
        self.assertEqual([], ids)

    def test_cella_nuova_sotto_lo_zoom_minimo_con_una_fonte_in_errore_resta_senza_voce(self):
        with tempfile.TemporaryDirectory() as d:
            proc, ids = self.run_cell(Path(d), "6/33/22", {"cells": []}, pmtiles_body=PMTILES_TOO_BIG, curl_body="exit 7\n")
        self.assertEqual(1, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("ERRORE: 6/33/22: fonte fallita (OSM=true", proc.stderr)
        self.assertEqual([], ids)

    def test_zoom_minimo_non_valido(self):
        with tempfile.TemporaryDirectory() as d:
            proc, _ = self.run_cell(Path(d), "2/1/1", {"cells": []}, ADDRESS_CELL_MIN_ZOOM=15)
        self.assertEqual(1, proc.returncode)
        self.assertIn("ADDRESS_CELL_MIN_ZOOM", proc.stderr)


if __name__ == "__main__":
    unittest.main()
