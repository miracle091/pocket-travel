#!/usr/bin/env python3
"""Test di generate-subregion-rows.py (riquadri, slug, righe di pilot-regions.sh) su un GeoJSON sintetico: nessuna
rete (--check-wikivoyage non e' coperto).

Uso: python test_generate_subregion_rows.py
"""
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

# Il nome del file (con i trattini) non e' un identificatore Python valido: si carica dal path.
_spec = importlib.util.spec_from_file_location("generate_subregion_rows", Path(__file__).parent / "generate-subregion-rows.py")
gsr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gsr)


def polygon(*ring):
    return {"type": "Polygon", "coordinates": [[list(p) for p in ring]]}


def feature(name, geometry, adm0="USA", **props):
    return {"properties": {"name": name, "adm0_a3": adm0, **props}, "geometry": geometry}


class GeometryTest(unittest.TestCase):
    def test_points_attraversa_multipolygon(self):
        geometry = {"type": "MultiPolygon", "coordinates": [[[[0, 0], [1, 0], [1, 1]]], [[[5, 5], [6, 6], [5, 6]]]]}
        xs, ys = gsr.points(geometry)
        self.assertEqual([0, 1, 1, 5, 6, 5], xs)
        self.assertEqual([0, 0, 1, 5, 6, 6], ys)

    def test_bbox_arrotondato_verso_l_esterno(self):
        g = polygon((-10.126, 40.001), (-9.5, 41.234), (-8.9, 40.5))
        self.assertEqual((-10.13, 40.0, -8.9, 41.24), gsr.bbox([g]))

    def test_bbox_unisce_piu_geometrie(self):
        a, b = polygon((0, 0), (1, 1), (1, 0)), polygon((5, -3), (6, 2), (5, 2))
        self.assertEqual((0.0, -3.0, 6.0, 2.0), gsr.bbox([a, b]))

    def test_center_e_il_centro_del_riquadro(self):
        self.assertEqual((2.0, 3.0), gsr.center(polygon((0, 0), (4, 0), (4, 6))))


class TextHelpersTest(unittest.TestCase):
    def test_slug_toglie_accenti_e_simboli(self):
        self.assertEqual("citta-del-messico", gsr.slug("Città del  Messico!"))
        self.assertEqual("distretto-di-columbia", gsr.slug("Distretto di Columbia"))

    def test_slug_di_solo_simboli_e_vuoto(self):
        self.assertEqual("", gsr.slug("???"))

    def test_pairs(self):
        self.assertEqual({"Georgia": "Georgia_(U.S._state)", "A": "b=c"},
                         gsr.pairs(["Georgia=Georgia_(U.S._state)", "A=b=c"]))
        self.assertEqual({}, gsr.pairs(None))


class MainTest(unittest.TestCase):
    def _run(self, features, *extra):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "ne.geojson"
            path.write_text(json.dumps({"features": features}), encoding="utf-8")
            argv = ["x", "--geojson", str(path), "--country", "USA", "--id-prefix", "stati-uniti",
                    "--display-prefix", "Stati Uniti", "--flag", "us", "--group", "Stati Uniti d'America",
                    "--continent", "Nord America", *extra]
            with mock.patch.object(sys, "argv", argv), redirect_stdout(io.StringIO()) as out:
                gsr.main()
        return out.getvalue().splitlines()

    def test_righe_ordinate_con_nome_italiano_e_titolo_wikivoyage(self):
        features = [
            feature("Texas", polygon((-106.65, 25.84), (-93.51, 36.5), (-100, 30)), name_it="Texas"),
            feature("New York", polygon((-79.755, 40.5), (-71.8, 45.01), (-75, 42)), name_it="New York"),
            feature("Ontario", polygon((0, 0), (1, 1), (1, 0)), adm0="CAN"),
        ]
        rows = self._run(features, "--name", "Texas=Texas (stato)")
        self.assertEqual([
            '  "stati-uniti-new-york|Stati Uniti - New York|-79.76|40.50|-71.80|45.01|New_York|us|'
            "Stati Uniti d'America|New York|Nord America\"",
            '  "stati-uniti-texas-stato|Stati Uniti - Texas (stato)|-106.65|25.84|-93.51|36.50|Texas|us|'
            "Stati Uniti d'America|Texas (stato)|Nord America\"",
        ], rows)

    def test_exclude_wiki_e_nome_it_mancante(self):
        features = [
            feature("Alaska", polygon((0, 0), (1, 1), (1, 0)), name_it="Alaska"),
            feature("Georgia", polygon((0, 0), (2, 2), (2, 0))),
        ]
        rows = self._run(features, "--exclude", "Alaska, ", "--wiki", "Georgia=Georgia_(U.S._state)")
        self.assertEqual(1, len(rows))
        self.assertIn("|Georgia_(U.S._state)|", rows[0])
        self.assertIn('"stati-uniti-georgia|Stati Uniti - Georgia|', rows[0])

    def test_within_filtra_per_centro(self):
        features = [
            feature("Dentro", polygon((0, 0), (2, 2), (2, 0))),
            feature("Fuori", polygon((50, 50), (52, 52), (52, 50))),
        ]
        rows = self._run(features, "--within=-5,-5,10,10")
        self.assertEqual(1, len(rows))
        self.assertIn("-dentro|", rows[0])

    def test_group_by_unisce_le_suddivisioni(self):
        features = [
            feature("Dip1", polygon((0, 0), (1, 1), (1, 0)), region="Nord"),
            feature("Dip2", polygon((3, 3), (4, 5), (4, 3)), region="Nord"),
            feature("Dip3", polygon((9, 9), (10, 10), (10, 9)), region=None),
        ]
        rows = self._run(features, "--group-by", "region")
        self.assertEqual(['  "stati-uniti-nord|Stati Uniti - Nord|0.00|0.00|4.00|5.00|Nord|us|'
                          "Stati Uniti d'America|Nord|Nord America\""], rows)


if __name__ == "__main__":
    unittest.main()
