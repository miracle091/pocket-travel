#!/usr/bin/env python3
"""Test di country_border.py (unittest, senza rete)."""

import json
import os
import sys
import tempfile
import unittest
import urllib.error
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

    def test_zona_economica_esclusiva_scartata(self):
        # "New Caledonia": prima la zona economica esclusiva (confine marittimo, con lo stesso codice ISO), poi il territorio.
        eez = {**SAN_MARINO, "osm_id": 2177258, "type": "maritime", "extratags": {"ISO3166-1": "NC"}}
        territory = {**SAN_MARINO, "osm_id": 3407643, "type": "administrative", "extratags": {"ISO3166-1": "NC"}}
        with tempfile.TemporaryDirectory() as cache, \
                mock.patch.object(country_border, "cerca", side_effect=[[], [eez, territory]]), \
                mock.patch.object(country_border.time, "sleep"):
            found = country_border.confine("NC", cache, "test", "New Caledonia")

        self.assertEqual(found, (3407643, SAN_MARINO["geojson"]))

    def test_main_scrive_la_feature_e_stampa_l_id(self):
        with tempfile.TemporaryDirectory() as cache, mock.patch.object(country_border, "cerca", return_value=[SAN_MARINO]), \
                mock.patch.object(country_border.time, "sleep"), mock.patch("sys.stdout") as stdout:
            out = os.path.join(cache, "confine.geojson")
            with mock.patch.object(sys, "argv", ["country_border.py", "--iso", "sm", "--out", out, "--cache", cache]):
                country_border.main()
            with open(out, encoding="utf-8") as f:
                feature = json.load(f)

        self.assertEqual(feature["geometry"], SAN_MARINO["geojson"])
        self.assertIn("54624", "".join(call.args[0] for call in stdout.write.call_args_list))

    def test_main_scrive_il_poligono_completo_della_relazione_scelta(self):
        # La ricerca sceglie la relazione con i poligoni semplificati; il lookup ne da' quello completo, che resta in cache.
        completo = {"type": "Polygon", "coordinates": [[[12.4, 43.9], [12.45, 43.89], [12.5, 43.9], [12.5, 44.0], [12.4, 43.9]]]}
        with tempfile.TemporaryDirectory() as cache, \
                mock.patch.object(country_border, "cerca", side_effect=[[SAN_MARINO], [{**SAN_MARINO, "geojson": completo}]]) as cerca, \
                mock.patch.object(country_border.time, "sleep"), mock.patch("sys.stdout"):
            out = os.path.join(cache, "confine.geojson")
            with mock.patch.object(sys, "argv", ["country_border.py", "--iso", "sm", "--out", out, "--cache", cache]):
                country_border.main()
                country_border.main()
            with open(out, encoding="utf-8") as f:
                feature = json.load(f)

        self.assertEqual(feature["geometry"], completo)
        self.assertEqual(cerca.call_count, 2)
        self.assertEqual(cerca.call_args_list[1].args, ({"osm_ids": "R54624"}, "PocketTravel-pipeline", "lookup"))

    def test_main_senza_poligono_completo_usa_quello_semplificato(self):
        # Lookup senza poligono, o fallito (rete, risposta troncata): vale quello della ricerca, e il fallimento non resta
        # in cache.
        for lookup in ([], OSError("rete"), ValueError("JSON troncato")):
            with tempfile.TemporaryDirectory() as cache, \
                    mock.patch.object(country_border, "cerca", side_effect=[[SAN_MARINO], lookup]), \
                    mock.patch.object(country_border.time, "sleep"), mock.patch("sys.stdout"), mock.patch("sys.stderr"):
                out = os.path.join(cache, "confine.geojson")
                with mock.patch.object(sys, "argv", ["country_border.py", "--iso", "sm", "--out", out, "--cache", cache]):
                    country_border.main()
                with open(out, encoding="utf-8") as f:
                    feature = json.load(f)
                cached = os.path.exists(os.path.join(cache, "confine-r54624.json"))

            self.assertEqual(feature["geometry"], SAN_MARINO["geojson"])
            self.assertEqual(cached, lookup == [])

    def test_main_senza_confine_esce_con_errore(self):
        with tempfile.TemporaryDirectory() as cache, mock.patch.object(country_border, "cerca", return_value=[]), \
                mock.patch.object(sys, "argv", ["country_border.py", "--iso", "ic", "--out", os.path.join(cache, "c"), "--cache", cache]):
            with self.assertRaises(SystemExit):
                country_border.main()


def stato(osm_id, code, south, north, west, east):
    """Risultato di Nominatim per una suddivisione: codice ISO 3166-2 nell'indirizzo, rettangolo come stringhe."""
    return {"osm_type": "relation", "osm_id": osm_id, "address": {"ISO3166-2-lvl4": code},
            "boundingbox": [str(south), str(north), str(west), str(east)], "geojson": SAN_MARINO["geojson"]}


HUBEI_BBOX = (108.36, 29.03, 116.13, 33.27)
HEBEI = stato(912998, "CN-HE", 36.05, 42.62, 113.45, 119.85)
HUBEI = stato(913106, "CN-HB", 29.03, 33.28, 108.36, 116.13)


class SuddivisioneTest(unittest.TestCase):
    def test_copertura(self):
        self.assertAlmostEqual(country_border.copertura((0, 0, 2, 2), (1, 0, 3, 2)), 0.5)
        self.assertEqual(country_border.copertura((-180, 0, 180, 80), (-170, 51, -130, 71)), 1.0)
        self.assertEqual(country_border.copertura((0, 0, 1, 1), (2, 2, 3, 3)), 0.0)

    def test_suddivisione_con_il_nome_simile_e_un_altro_riquadro_scartata(self):
        with tempfile.TemporaryDirectory() as cache, \
                mock.patch.object(country_border, "cerca", side_effect=[[HEBEI], [HEBEI, HUBEI]]), \
                mock.patch.object(country_border.time, "sleep"):
            found = country_border.confine_suddivisione("cn", "Hubei", HUBEI_BBOX, cache, "test")

        self.assertEqual(found[0], 913106)

    def test_suddivisione_di_un_altra_nazione_scartata(self):
        other = stato(1, "TW-TPE", 29.03, 33.28, 108.36, 116.13)
        with tempfile.TemporaryDirectory() as cache, mock.patch.object(country_border, "cerca", return_value=[other]), \
                mock.patch.object(country_border.time, "sleep"):
            self.assertIsNone(country_border.confine_suddivisione("cn", "Hubei", HUBEI_BBOX, cache, "test"))

    def test_titolo_senza_la_parte_tra_parentesi(self):
        georgia = stato(161957, "US-GA", 30.36, 35.0, -85.61, -80.84)
        with tempfile.TemporaryDirectory() as cache, \
                mock.patch.object(country_border, "cerca", side_effect=[[], [], [georgia]]) as cerca, \
                mock.patch.object(country_border.time, "sleep"):
            found = country_border.confine_suddivisione("us", "Georgia (U.S. state)", (-85.61, 30.36, -80.84, 35.0), cache, "test")

        self.assertEqual(found[0], 161957)
        self.assertEqual(cerca.call_args_list[2].args[0]["q"], "Georgia")

    def test_main_senza_suddivisione_usa_la_nazione(self):
        russia = {**SAN_MARINO, "osm_id": 60189, "extratags": {"ISO3166-1": "RU"}}
        with tempfile.TemporaryDirectory() as cache, \
                mock.patch.object(country_border, "cerca", side_effect=[[], [], [russia], [russia]]), \
                mock.patch.object(country_border.time, "sleep"), mock.patch("sys.stdout") as stdout, mock.patch("sys.stderr"):
            argv = ["country_border.py", "--iso", "ru", "--name", "Volga Region", "--subdivision", "--bbox", "41,51,53,58",
                    "--out", os.path.join(cache, "c.geojson"), "--cache", cache]
            with mock.patch.object(sys, "argv", argv):
                country_border.main()

        self.assertIn("60189", "".join(call.args[0] for call in stdout.write.call_args_list))

    def test_main_con_un_riquadro_a_ovest_di_greenwich(self):
        # "--bbox -82.62,..." argparse lo legge come un'opzione: build-region.sh passa "--bbox=-82.62,...".
        virginia = stato(162068, "US-WV", 37.2, 40.64, -82.64, -77.72)
        with tempfile.TemporaryDirectory() as cache, mock.patch.object(country_border, "cerca", return_value=[virginia]), \
                mock.patch.object(country_border.time, "sleep"), mock.patch("sys.stdout") as stdout:
            argv = ["country_border.py", "--iso", "us", "--name", "West Virginia", "--subdivision",
                    "--bbox=-82.62,37.20,-77.72,40.65", "--out", os.path.join(cache, "c.geojson"), "--cache", cache]
            with mock.patch.object(sys, "argv", argv):
                country_border.main()

        self.assertIn("162068", "".join(call.args[0] for call in stdout.write.call_args_list))


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, *args):
        return json.dumps(self.payload).encode("utf-8")


class CercaTest(unittest.TestCase):
    def test_parametri_della_ricerca_e_user_agent(self):
        with mock.patch("urllib.request.urlopen", return_value=FakeResponse([{"osm_id": 1}])) as urlopen:
            result = country_border.cerca({"q": "Città del Messico"}, "UA-di-prova")
        self.assertEqual([{"osm_id": 1}], result)
        request = urlopen.call_args.args[0]
        self.assertEqual("UA-di-prova", request.get_header("User-agent"))
        self.assertTrue(request.full_url.startswith("https://nominatim.openstreetmap.org/search?"))
        for fragment in ("polygon_geojson=1", "polygon_threshold=0.0001", "format=jsonv2", "limit=5", "extratags=1",
                         "addressdetails=1", "q=Citt%C3%A0+del+Messico"):
            self.assertIn(fragment, request.full_url)

    def test_lookup_con_il_poligono_completo(self):
        with mock.patch("urllib.request.urlopen", return_value=FakeResponse([])) as urlopen:
            country_border.cerca({"osm_ids": "R161349"}, "ua", "lookup")
        url = urlopen.call_args.args[0].full_url
        self.assertTrue(url.startswith("https://nominatim.openstreetmap.org/lookup?osm_ids=R161349&polygon_geojson=1"))
        self.assertNotIn("polygon_threshold", url)

    def test_riprova_e_per_429_attende_di_piu(self):
        too_many = urllib.error.HTTPError("https://n", 429, "Too Many Requests", {}, None)
        with mock.patch("urllib.request.urlopen", side_effect=[too_many, OSError("rete"), FakeResponse([])]), \
                mock.patch("time.sleep") as sleep, mock.patch("sys.stderr"):
            self.assertEqual([], country_border.cerca({"q": "x"}, "ua"))
        self.assertEqual([mock.call(60), mock.call(10)], sleep.call_args_list)

    def test_dopo_tre_tentativi_rilancia(self):
        with mock.patch("urllib.request.urlopen", side_effect=OSError("rete")) as urlopen, \
                mock.patch("time.sleep"), mock.patch("sys.stderr"):
            with self.assertRaises(OSError):
                country_border.cerca({"q": "x"}, "ua")
        self.assertEqual(3, urlopen.call_count)


class ScegliAndCacheTest(unittest.TestCase):
    def test_scegli_salta_i_non_poligoni_e_le_relazioni_non_valide(self):
        node = {"osm_type": "node", "osm_id": 1, "geojson": {"type": "Polygon", "coordinates": []}}
        line = {"osm_type": "relation", "osm_id": 2, "geojson": {"type": "LineString", "coordinates": []}}
        no_geometry = {"osm_type": "relation", "osm_id": 3}
        good = {"osm_type": "relation", "osm_id": "4", "geojson": {"type": "MultiPolygon", "coordinates": []}}
        self.assertEqual((4, good["geojson"]), country_border.scegli([node, line, no_geometry, good], lambda r: True))
        self.assertIsNone(country_border.scegli([good], lambda r: False))
        self.assertIsNone(country_border.scegli(None, lambda r: True))

    def test_in_cache_scrive_una_volta_e_crea_la_cartella(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "sotto", "cartella", "x.json")
            fetch = mock.Mock(return_value=[{"nome": "Réunion"}])
            self.assertEqual([{"nome": "Réunion"}], country_border.in_cache(path, fetch))
            self.assertEqual([{"nome": "Réunion"}], country_border.in_cache(path, fetch))
            fetch.assert_called_once()
            with open(path, encoding="utf-8") as f:
                self.assertEqual([{"nome": "Réunion"}], json.load(f))

    def test_copertura_senza_sovrapposizione_e_parziale(self):
        self.assertEqual(0.0, country_border.copertura((0, 0, 1, 1), (2, 2, 3, 3)))
        self.assertEqual(0.0, country_border.copertura((0, 0, 1, 1), (1, 0, 2, 1)))   # solo un lato in comune
        self.assertEqual(0.25, country_border.copertura((0, 0, 1, 1), (0.5, 0.5, 1.5, 1.5)))
        self.assertEqual(1.0, country_border.copertura((-5, -5, 5, 5), (0, 0, 1, 1)))

    def test_iso_di_lunghezza_errata_esce_subito(self):
        argv = ["country_border.py", "--iso", "ITA", "--out", "x", "--cache", "y"]
        with mock.patch.object(sys, "argv", argv), mock.patch.object(country_border, "cerca") as cerca:
            with self.assertRaises(SystemExit):
                country_border.main()
        cerca.assert_not_called()


if __name__ == "__main__":
    unittest.main()
