#!/usr/bin/env python3
"""Test di seed-address-grid.py sulla logica pura (quadtree, proiezione, righe Overture): nessuna
rete, nessun PMTiles remoto (quello lo copre solo una prova manuale).

Uso: python test_seed_address_grid.py
"""
import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path

# Il nome del file (con i trattini) non e' un identificatore Python
# valido per "import seed_address_grid": si carica dal path, come clip_rd5.py fa per se stesso
# ma qui e' necessario perche' il nome del modulo non puo' avere trattini.
_spec = importlib.util.spec_from_file_location("seed_address_grid", Path(__file__).parent / "seed-address-grid.py")
_seed_address_grid = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_seed_address_grid)
lat_to_y = _seed_address_grid.lat_to_y
lon_to_x = _seed_address_grid.lon_to_x
overture_rows_per_cell = _seed_address_grid.overture_rows_per_cell
quadtree_seed = _seed_address_grid.quadtree_seed


class QuadtreeSeedTest(unittest.TestCase):
    def test_una_cella_sotto_il_tetto_non_si_divide(self):
        data = {(0, 0): 5_000_000.0}

        cells = quadtree_seed(data, zoom=12, cap_bytes=10_000_000)

        self.assertEqual([(0, 0, 0)], cells)

    def test_una_cella_sopra_il_tetto_si_divide_nei_quattro_figli_con_dati(self):
        # Chiavi = coordinate assolute alla risoluzione "zoom" (z1 qui, 2x2 celle): (0,0)=8, (1,0)=8,
        # (0,1)=0, (1,1)=8 (in MB). Il totale (24) supera il tetto (10): tre figli hanno dati e
        # restano a z1 (la funzione si ferma appena z == zoom), il quarto (vuoto) non compare.
        data = {(0, 0): 8e6, (1, 0): 8e6, (0, 1): 0.0, (1, 1): 8e6}

        cells = quadtree_seed(data, zoom=1, cap_bytes=10e6)

        self.assertEqual({(1, 0, 0), (1, 1, 0), (1, 1, 1)}, set(cells))

    def test_si_ferma_comunque_allo_zoom_richiesto(self):
        # Sopra il tetto ma gia' alla risoluzione massima del seme: resta una sola cella (oltre ci
        # pensa la pipeline quando costruisce davvero la cella, fino a z14 - vedi build-address-cell.sh).
        data = {(1, 1): 50e6}

        cells = quadtree_seed(data, zoom=2, cap_bytes=10e6)

        self.assertEqual([(2, 1, 1)], cells)

    def test_nessun_dato_niente_celle(self):
        self.assertEqual([], quadtree_seed({(0, 0): 0.0}, zoom=12, cap_bytes=10e6))


class ProjectionTest(unittest.TestCase):
    def test_lon_lat_a_tile_centro_del_mondo(self):
        self.assertEqual(1 << 11, lon_to_x(0.0, 12))
        self.assertEqual(1 << 11, lat_to_y(0.0, 12))

    def test_angoli_del_mondo(self):
        self.assertEqual(0, lon_to_x(-180.0, 8))
        self.assertEqual((1 << 8) - 1, lon_to_x(179.99, 8))


class OvertureRowsPerCellTest(unittest.TestCase):
    def test_distribuisce_le_righe_di_un_row_group_sulle_celle_del_suo_bbox(self):
        csv_path = Path(tempfile.mkdtemp()) / "rowgroups.csv"
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "file_name", "row_group_id", "row_group_num_rows",
                "bbox, xmax_mn", "bbox, xmax_mx", "bbox, xmin_mn", "bbox, xmin_mx",
                "bbox, ymax_mn", "bbox, ymax_mx", "bbox, ymin_mn", "bbox, ymin_mx",
                "country_mn", "country_mx",
            ])
            # bbox minuscolo lontano dai bordi delle tile (10.0,10.0)-(10.01,10.01): a zoom 2 cade
            # tutto in una sola cella.
            writer.writerow(["f.parquet", "0", "1000", "10.01", "10.01", "10.0", "10.0", "10.01", "10.01", "10.0", "10.0", "XX", "XX"])

        per_cell = overture_rows_per_cell(str(csv_path), zoom=2)

        self.assertEqual({(2, 1): 1000.0}, dict(per_cell))


if __name__ == "__main__":
    unittest.main()
