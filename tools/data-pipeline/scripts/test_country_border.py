#!/usr/bin/env python3
"""Test di country_border.py (unittest, senza rete)."""

import json
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import country_border  # noqa: E402

SAN_MARINO = {"osm_type": "relation", "osm_id": 54624, "extratags": {"ISO3166-1": "SM"},
              "geojson": {"type": "Polygon", "coordinates": [[[12.4, 43.9], [12.5, 43.9], [12.5, 44.0], [12.4, 43.9]]]}}


class ConfineTest(unittest.TestCase):
    def test_relazione_e_poligono_e_cache(self):
        with tempfile.TemporaryDirectory() as cache, mock.patch.object(country_border, "cerca", return_value=[SAN_MARINO]) as cerca:
            first = country_border.confine("SM", cache, "test")
            second = country_border.confine("sm", cache, "test")

        self.assertEqual(first, (54624, SAN_MARINO["geojson"]))
        self.assertEqual(second, first)
        cerca.assert_called_once()

    def test_senza_risultato_o_senza_poligono_nessun_confine(self):
        point = {"osm_type": "node", "osm_id": 1, "geojson": {"type": "Point", "coordinates": [0, 0]}}
        for results in ([], [point]):
            with tempfile.TemporaryDirectory() as cache, mock.patch.object(country_border, "cerca", return_value=results):
                self.assertIsNone(country_border.confine("RE", cache, "test"))

    def test_relazione_di_un_altra_nazione_nessun_confine(self):
        peru = {**SAN_MARINO, "osm_id": 288247, "extratags": {"ISO3166-1:alpha2": "PE"}}
        for result in (peru, {**SAN_MARINO, "extratags": None}):
            with tempfile.TemporaryDirectory() as cache, mock.patch.object(country_border, "cerca", return_value=[result]):
                self.assertIsNone(country_border.confine("PR", cache, "test"))

    def test_territorio_cercato_per_nome_con_lo_stesso_codice(self):
        peru = {**SAN_MARINO, "osm_id": 288247, "extratags": {"ISO3166-1:alpha2": "PE"}}
        town = {**SAN_MARINO, "osm_id": 1, "extratags": {}}
        puerto_rico = {**SAN_MARINO, "osm_id": 4422604, "extratags": {"ISO3166-1": "PR"}}
        with tempfile.TemporaryDirectory() as cache,                 mock.patch.object(country_border, "cerca", side_effect=[[peru], [town, puerto_rico]]) as cerca,                 mock.patch.object(country_border.time, "sleep"):
            found = country_border.confine("PR", cache, "test", "Puerto Rico")

        self.assertEqual(found, (4422604, SAN_MARINO["geojson"]))
        self.assertEqual(cerca.call_args_list[1].args[0], {"q": "Puerto Rico"})

    def test_main_scrive_la_feature_e_stampa_l_id(self):
        with tempfile.TemporaryDirectory() as cache, mock.patch.object(country_border, "cerca", return_value=[SAN_MARINO]), \
                mock.patch("sys.stdout") as stdout:
            out = os.path.join(cache, "confine.geojson")
            with mock.patch.object(sys, "argv", ["country_border.py", "--iso", "sm", "--out", out, "--cache", cache]):
                country_border.main()
            with open(out, encoding="utf-8") as f:
                feature = json.load(f)

        self.assertEqual(feature["geometry"], SAN_MARINO["geojson"])
        self.assertIn("54624", "".join(call.args[0] for call in stdout.write.call_args_list))

    def test_main_senza_confine_esce_con_errore(self):
        with tempfile.TemporaryDirectory() as cache, mock.patch.object(country_border, "cerca", return_value=[]), \
                mock.patch.object(sys, "argv", ["country_border.py", "--iso", "ic", "--out", os.path.join(cache, "c"), "--cache", cache]):
            with self.assertRaises(SystemExit):
                country_border.main()


if __name__ == "__main__":
    unittest.main()
