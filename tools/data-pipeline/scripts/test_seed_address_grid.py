#!/usr/bin/env python3
"""Test di seed-address-grid.py sulla logica pura (quadtree, proiezione, righe Overture): nessuna
rete, nessun PMTiles remoto (quello lo copre solo una prova manuale).

Uso: python test_seed_address_grid.py
"""
import csv
import gzip
import importlib.util
import io
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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


ROWGROUP_HEADER = [
    "file_name", "row_group_id", "row_group_num_rows",
    "bbox, xmax_mn", "bbox, xmax_mx", "bbox, xmin_mn", "bbox, xmin_mx",
    "bbox, ymax_mn", "bbox, ymax_mx", "bbox, ymin_mn", "bbox, ymin_mx",
    "country_mn", "country_mx",
]


def write_rowgroups(path, groups):
    """groups: [(righe, xmin, ymin, xmax, ymax)]."""
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(ROWGROUP_HEADER)
        for i, (rows, xmin, ymin, xmax, ymax) in enumerate(groups):
            writer.writerow(["f.parquet", i, rows, xmax, xmax, xmin, xmin, ymax, ymax, ymin, ymin, "XX", "XX"])


class OvertureRowsEdgeTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.path = Path(tmp.name) / "rowgroups.csv"

    def test_righe_divise_in_parti_uguali_tra_le_celle_toccate(self):
        # a zoom 1 il bbox attraversa il meridiano 0: due celle in orizzontale, una riga di celle
        write_rowgroups(self.path, [(1000, -10.0, 10.0, 10.0, 11.0)])
        per_cell = overture_rows_per_cell(str(self.path), zoom=1)
        self.assertEqual({(0, 0): 500.0, (1, 0): 500.0}, dict(per_cell))

    def test_row_group_di_piu_file_si_sommano(self):
        write_rowgroups(self.path, [(100, 10.0, 10.0, 10.01, 10.01), (50, 10.0, 10.0, 10.01, 10.01)])
        self.assertEqual({(2, 1): 150.0}, dict(overture_rows_per_cell(str(self.path), zoom=2)))

    def test_riquadro_anomalo_va_al_centro_non_su_migliaia_di_celle(self):
        write_rowgroups(self.path, [(900, -179.0, -80.0, 179.0, 80.0)])
        per_cell = overture_rows_per_cell(str(self.path), zoom=12)
        self.assertEqual(1, len(per_cell))
        self.assertEqual(900.0, sum(per_cell.values()))

    def test_file_con_la_sola_intestazione(self):
        write_rowgroups(self.path, [])
        self.assertEqual({}, dict(overture_rows_per_cell(str(self.path), zoom=12)))


class ProjectionEdgeTest(unittest.TestCase):
    def test_latitudini_oltre_il_limite_mercator_si_fermano_ai_bordi(self):
        self.assertEqual(lat_to_y(85.05, 10), lat_to_y(89.9, 10))
        self.assertEqual(lat_to_y(-85.05, 10), lat_to_y(-90, 10))
        self.assertEqual(0, lat_to_y(89.9, 10))
        self.assertEqual((1 << 10) - 1, lat_to_y(-89.9, 10))

    def test_il_nord_ha_y_minore(self):
        self.assertLess(lat_to_y(60.0, 12), lat_to_y(40.0, 12))

    def test_istanbul_a_zoom_12(self):
        self.assertEqual((2377, 1535), (lon_to_x(28.98, 12), lat_to_y(41.01, 12)))


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def directory(entries):
    """Directory PMTiles v3 non compressa: entries = [(tile_id, run_length, length, offset)]."""
    out = bytearray(varint(len(entries)))
    last = 0
    for tile_id, *_ in entries:
        out += varint(tile_id - last)
        last = tile_id
    for _, run, _, _ in entries:
        out += varint(run)
    for _, _, length, _ in entries:
        out += varint(length)
    for _, _, _, offset in entries:
        out += varint(offset + 1)
    return bytes(out)


def pmtiles(root, leaf=b"", compression=1):
    """File PMTiles v3 finto: intestazione di 127 byte, directory radice e foglie."""
    header = bytearray(127)
    header[:7] = b"PMTiles"
    header[7] = 3
    root_off, leaf_off = 127, 127 + len(root)
    header[8:72] = struct.pack("<8Q", root_off, len(root), 0, 0, leaf_off, len(leaf), 0, 0)
    header[97] = compression
    return bytes(header) + root + leaf


class PmtilesTest(unittest.TestCase):
    def test_varint_a_piu_byte(self):
        for value in (0, 1, 127, 128, 300, 16384, 2 ** 40):
            self.assertEqual((value, len(varint(value))), _seed_address_grid._varint(varint(value) + b"\xff", 0))

    def test_directory_con_offset_contigui(self):
        entries = [(5, 1, 100, 0), (6, 3, 50, 100), (9, 1, 20, 150)]
        self.assertEqual(entries, _seed_address_grid._parse_directory(directory(entries)))

    def test_offset_zero_vuol_dire_subito_dopo_la_tile_precedente(self):
        encoded = bytearray(varint(2))
        encoded += varint(1) + varint(1)          # id 1 e 2
        encoded += varint(1) + varint(1)          # run length
        encoded += varint(40) + varint(60)        # lunghezze
        encoded += varint(11) + varint(0)         # primo offset 10; secondo 0 = contiguo
        parsed = _seed_address_grid._parse_directory(bytes(encoded))
        self.assertEqual([(1, 1, 40, 10), (2, 1, 60, 50)], parsed)

    def test_id_tile_noti(self):
        zxy = _seed_address_grid.tile_id_to_zxy
        self.assertEqual([(0, 0, 0), (1, 0, 0), (1, 0, 1), (1, 1, 1), (1, 1, 0), (2, 0, 0)], [zxy(i) for i in range(6)])

    def test_curva_di_hilbert_continua_e_biiettiva(self):
        zxy = _seed_address_grid.tile_id_to_zxy
        first = (4 ** 3 - 1) // 3
        points = [zxy(i) for i in range(first, first + 64)]
        self.assertEqual({(3, x, y) for x in range(8) for y in range(8)}, set(points))
        for (_, x1, y1), (_, x2, y2) in zip(points, points[1:]):
            self.assertEqual(1, abs(x1 - x2) + abs(y1 - y2))

    def test_lunghezze_z14_con_run_e_directory_foglia(self):
        base = (4 ** 14 - 1) // 3
        leaf = directory([(base + 10, 2, 300, 0)])
        root = directory([(base, 1, 100, 0), (base + 10, 0, len(leaf), 0)])
        blob = pmtiles(root, leaf)
        fake = lambda url, start, length: blob[start:start + length]
        with mock.patch.object(_seed_address_grid, "_http_range", side_effect=fake):
            lengths = _seed_address_grid.pmtiles_z14_lengths("https://example.org/a.pmtiles")
        self.assertEqual({base: 100, base + 10: 300, base + 11: 300}, lengths)

    def test_directory_compressa_con_gzip(self):
        base = (4 ** 14 - 1) // 3
        root = gzip.compress(directory([(base, 1, 77, 0)]))
        blob = pmtiles(root, compression=2)
        with mock.patch.object(_seed_address_grid, "_http_range", side_effect=lambda u, s, n: blob[s:s + n]):
            self.assertEqual({base: 77}, _seed_address_grid.pmtiles_z14_lengths("u"))

    def test_file_non_pmtiles_o_versione_diversa(self):
        for blob in (b"X" * 127, b"PMTiles" + bytes([2]) + bytes(119)):
            with mock.patch.object(_seed_address_grid, "_http_range", return_value=blob):
                with self.assertRaises(ValueError):
                    _seed_address_grid.pmtiles_z14_lengths("https://example.org/a.pmtiles")


class OsmBytesPerCellTest(unittest.TestCase):
    def test_massimo_tra_regioni_somma_per_cella_e_regione_irraggiungibile_saltata(self):
        base = (4 ** 14 - 1) // 3
        # tile z14 note: base e' (14, 0, 0); base + 1 e' (14, 0, 1): stessa cella z12, ma tile diverse
        by_url = {"a": {base: 100, base + 1: 40}, "b": {base: 70, base + 1: 90}}

        def lengths(url):
            if url == "rotta":
                raise OSError("rete")
            return by_url[url]

        manifest = {"regions": [
            {"addresses": {"file": {"url": "a"}}}, {"addresses": {"file": {"url": "b"}}},
            {"addresses": {"file": {"url": "rotta"}}}, {"addresses": None}, {}, {"addresses": {"file": None}},
        ]}
        with mock.patch.object(_seed_address_grid, "pmtiles_z14_lengths", side_effect=lengths), \
                mock.patch.object(sys, "stderr", io.StringIO()):
            per_cell = _seed_address_grid.osm_bytes_per_cell(manifest, zoom=12)
        self.assertEqual({(0, 0): 100 + 90}, dict(per_cell))  # max(100,70) + max(40,90)

    def test_tile_non_z14_ignorate_e_manifest_senza_regioni(self):
        with mock.patch.object(_seed_address_grid, "pmtiles_z14_lengths", return_value={0: 5}), \
                mock.patch.object(sys, "stderr", io.StringIO()):
            self.assertEqual({}, dict(_seed_address_grid.osm_bytes_per_cell({"regions": [{"addresses": {"file": {"url": "a"}}}]}, 12)))
            self.assertEqual({}, dict(_seed_address_grid.osm_bytes_per_cell({}, 12)))


class MainTest(unittest.TestCase):
    def test_seme_scritto_ordinato_e_combinato_con_il_margine(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest, rowgroups, output = Path(tmp, "m.json"), Path(tmp, "rg.csv"), Path(tmp, "seed.tsv")
            manifest.write_text(json.dumps({"regions": []}), encoding="utf-8")
            # 2 milioni di righe a Istanbul e 100 righe a Sydney; 10 byte per civico, tetto 10 MB
            write_rowgroups(rowgroups, [(2_000_000, 28.98, 41.0, 28.99, 41.01), (100, 151.2, -33.9, 151.21, -33.89)])
            argv = ["seed-address-grid.py", str(manifest), str(rowgroups), str(output), "--zoom", "3"]
            with mock.patch.object(sys, "argv", argv), mock.patch.object(sys, "stderr", io.StringIO()), \
                    mock.patch.object(sys, "stdout", io.StringIO()) as out:
                _seed_address_grid.main()
            lines = output.read_text(encoding="utf-8").splitlines()
        self.assertEqual(sorted(lines, key=lambda l: tuple(int(p) for p in l.split("/"))), lines)
        # Istanbul: 2 M * 10 B * 1,1 = 22 MB supera il tetto, ma si ferma al zoom richiesto; Sydney sta sotto il tetto
        # e il quadtree la tiene alla cella piu' grande con soli suoi dati: il quadrante z1 sud-est
        self.assertEqual(["1/1/1", "3/4/2"], lines)
        self.assertIn("seme:", out.getvalue())

    def test_load_manifest_da_file_locale(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "m.json")
            path.write_text(json.dumps({"regions": [{"id": "città"}]}, ensure_ascii=False), encoding="utf-8")
            self.assertEqual("città", _seed_address_grid.load_manifest(str(path))["regions"][0]["id"])


if __name__ == "__main__":
    unittest.main()
