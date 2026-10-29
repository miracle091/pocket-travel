#!/usr/bin/env python3
"""Test delle funzioni shell di lib.sh usate da build-region.sh: without_empty_tiles (tile .rd5 vuote registrate in
routing.emptyTiles, escluse dal confronto con il catalogo pubblicato, cosi' una regione invariata non si rigenera a
ogni run). Richiede bash e jq.

Uso: python test_build_shell_helpers.py
"""
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import shell_fixtures as fx

LIB = (fx.REPO / "tools/data-pipeline/scripts/lib.sh").as_posix()
CLIP = "-8.9,-18.95,-7.3,-18.2 0.1"


def tile(name, source_size):
    return {"name": name, "sourceKey": f"{source_size} {CLIP}"}


@unittest.skipUnless(fx.BASH and shutil.which("jq"), "servono bash e jq")
class WithoutEmptyTilesTest(unittest.TestCase):
    def filtered(self, expected, published_regions, region_id="r1"):
        with tempfile.TemporaryDirectory() as d:
            manifest = Path(d) / "manifest.json"
            manifest.write_text(json.dumps({"regions": published_regions}), encoding="utf-8")
            proc = subprocess.run([fx.BASH, "-c", f'source "{LIB}"; without_empty_tiles "{manifest.as_posix()}" "$1"', "_", region_id],
                                  input=json.dumps(expected), capture_output=True, text=True)
        self.assertEqual(0, proc.returncode, proc.stderr)
        return json.loads(proc.stdout)

    def test_tile_vuota_con_la_stessa_sorgente_esce_dal_confronto(self):
        published = [{"regionId": "r1", "routing": {"files": [], "emptyTiles": [tile("E5_N0.rd5", 100)]}}]
        self.assertEqual([tile("E0_N0.rd5", 50)], self.filtered([tile("E0_N0.rd5", 50), tile("E5_N0.rd5", 100)], published))

    def test_tile_vuota_con_la_sorgente_cambiata_resta(self):
        published = [{"regionId": "r1", "routing": {"emptyTiles": [tile("E5_N0.rd5", 100)]}}]
        expected = [tile("E5_N0.rd5", 101)]
        self.assertEqual(expected, self.filtered(expected, published))

    def test_tile_vuota_con_un_altro_ritaglio_resta(self):
        published = [{"regionId": "r1", "routing": {"emptyTiles": [{"name": "E5_N0.rd5", "sourceKey": "100 0,0,1,1 0.1"}]}}]
        expected = [tile("E5_N0.rd5", 100)]
        self.assertEqual(expected, self.filtered(expected, published))

    def test_senza_tile_vuote_pubblicate_o_di_un_altra_regione_non_cambia(self):
        expected = [tile("E0_N0.rd5", 50)]
        self.assertEqual(expected, self.filtered(expected, [{"regionId": "r1", "routing": {"files": []}}]))
        self.assertEqual(expected, self.filtered(expected, [{"regionId": "r2", "routing": {"emptyTiles": [tile("E0_N0.rd5", 50)]}}]))
        self.assertEqual(expected, self.filtered(expected, []))


if __name__ == "__main__":
    unittest.main()
