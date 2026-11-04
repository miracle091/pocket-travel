#!/usr/bin/env python3
"""Test di address_cell_children.py: figlie allo zoom minimo con almeno una tile z14, tile fuori dalla
cella ignorate, zoom minimo non valido, lettura di un PMTiles locale. Nessuna rete.

Uso: python test_address_cell_children.py
"""
import contextlib
import io
import tempfile
import unittest
from pathlib import Path

import address_cell_children as children
from test_seed_address_grid import directory, pmtiles

BASE_Z14 = (4 ** 14 - 1) // 3
# Sulla curva di Hilbert il primo e l'ultimo quarto degli id z14 cadono in quadranti z1 diversi.
OTHER_QUADRANT = BASE_Z14 + 3 * 4 ** 13


def zxy(tile_id):
    return children._seed.tile_id_to_zxy(tile_id)


def parent(tile_id, z):
    _, x, y = zxy(tile_id)
    return z, x >> (14 - z), y >> (14 - z)


class ChildrenAtMinZoomTest(unittest.TestCase):
    def test_una_figlia_per_gruppo_di_tile_vicine_e_tile_di_altre_celle_ignorate(self):
        tiles = [BASE_Z14, BASE_Z14 + 1, BASE_Z14 + 4 ** 7, OTHER_QUADRANT]
        z, x, y = parent(BASE_Z14, 2)

        got = children.children_at_min_zoom(tiles, z, x, y, 7)

        expected = sorted({parent(t, 7) for t in tiles[:3]})
        self.assertEqual(expected, got)
        self.assertEqual(2, len(got))  # le prime due tile sono vicine, la terza e' in un'altra z7
        self.assertNotIn(parent(OTHER_QUADRANT, 7), got)

    def test_tile_non_z14_ignorate(self):
        self.assertEqual([], children.children_at_min_zoom([0, 5], 0, 0, 0, 7))

    def test_zoom_minimo_non_sotto_la_cella_e_un_errore(self):
        for z, min_zoom in ((7, 7), (8, 7), (2, 15)):
            with self.assertRaises(ValueError):
                children.children_at_min_zoom([BASE_Z14], z, 0, 0, min_zoom)


class MainTest(unittest.TestCase):
    def test_legge_un_pmtiles_locale_e_stampa_le_figlie(self):
        # Un run di due tile a cavallo di due z7: la seconda figlia c'e' solo se il run si legge per intero.
        first = BASE_Z14 + 4 ** 7 - 1
        blob = pmtiles(directory([(first, 2, 100, 0), (OTHER_QUADRANT, 1, 50, 200)]))
        z, x, y = parent(first, 3)
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "addresses.pmtiles"
            path.write_bytes(blob)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = children.main(["address_cell_children.py", str(path), f"{z}/{x}/{y}", "7"])
        self.assertEqual(0, code)
        expected = sorted({parent(t, 7) for t in (first, first + 1)})
        self.assertEqual(2, len(expected))
        self.assertEqual([f"{a}/{b}/{c}" for a, b, c in expected], out.getvalue().split())

    def test_argomenti_mancanti(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, children.main(["address_cell_children.py"]))


if __name__ == "__main__":
    unittest.main()
