#!/usr/bin/env python3
"""Test di overture_rowgroups.py sulla logica pura: dai metadati dei row group alle righe del CSV che legge
seed-address-grid.py. Nessuna rete, nessun DuckDB (sostituito da un modulo vuoto: la query sul rilascio
la copre solo una prova manuale).

Uso: python test_overture_rowgroups.py
"""
import importlib.util
import sys
import tempfile
import types
import unittest
from pathlib import Path

sys.modules.setdefault("duckdb", types.ModuleType("duckdb"))
sys.path.insert(0, str(Path(__file__).parent))
import overture_rowgroups  # noqa: E402

_spec = importlib.util.spec_from_file_location("seed_address_grid", Path(__file__).parent / "seed-address-grid.py")
seed = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(seed)


def group(file_name, group_id, rows, xmin, xmax, ymin, ymax):
    """Le quattro righe di parquet_metadata (una per colonna bbox) di un row group."""
    return [
        (file_name, group_id, rows, "bbox, xmin", str(xmin), str(xmin + 0.5)),
        (file_name, group_id, rows, "bbox, xmax", str(xmax - 0.5), str(xmax)),
        (file_name, group_id, rows, "bbox, ymin", str(ymin), str(ymin + 0.5)),
        (file_name, group_id, rows, "bbox, ymax", str(ymax - 0.5), str(ymax)),
    ]


class RowgroupRowsTest(unittest.TestCase):
    def test_prende_minimo_di_xmin_e_massimo_di_xmax(self):
        rows = overture_rowgroups.rowgroup_rows(group("a.parquet", 0, 1000, 8.0, 9.0, 45.0, 46.0))
        self.assertEqual(
            [{"row_group_num_rows": 1000, "bbox, xmin_mn": 8.0, "bbox, xmax_mx": 9.0,
              "bbox, ymin_mn": 45.0, "bbox, ymax_mx": 46.0}],
            rows,
        )

    def test_ordina_per_file_e_row_group_e_ignora_altre_colonne(self):
        metadata = (group("b.parquet", 0, 5, 1, 2, 3, 4) + group("a.parquet", 1, 7, 1, 2, 3, 4)
                    + group("a.parquet", 0, 9, 1, 2, 3, 4) + [("a.parquet", 0, 9, "country", "AT", "AT")])
        self.assertEqual([9, 7, 5], [row["row_group_num_rows"] for row in overture_rowgroups.rowgroup_rows(metadata)])

    def test_scarta_row_group_incompleti_o_non_numerici(self):
        incomplete = group("a.parquet", 0, 10, 1, 2, 3, 4)[:3]
        no_stats = [("a.parquet", 1, 10, path, None, None) for path in overture_rowgroups._BBOX_FIELDS]
        self.assertEqual([], overture_rowgroups.rowgroup_rows(incomplete + no_stats))

    def test_csv_letto_da_seed_address_grid(self):
        rows = overture_rowgroups.rowgroup_rows(group("a.parquet", 0, 1000, 8.0, 8.0, 45.0, 45.0))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "rg.csv"
            overture_rowgroups.write_csv(rows, path)
            self.assertEqual(
                "row_group_num_rows,\"bbox, xmin_mn\",\"bbox, xmax_mx\",\"bbox, ymin_mn\",\"bbox, ymax_mx\"",
                path.read_text(encoding="utf-8").splitlines()[0],
            )
            per_cell = seed.overture_rows_per_cell(path, 7)
        self.assertEqual({(seed.lon_to_x(8.0, 7), seed.lat_to_y(45.0, 7)): 1000}, dict(per_cell))


if __name__ == "__main__":
    unittest.main()
