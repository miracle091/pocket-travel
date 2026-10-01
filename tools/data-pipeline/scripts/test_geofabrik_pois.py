#!/usr/bin/env python3
"""Test di geofabrik_pois.py sulla logica pura (scelta degli estratti, filtro, XML): niente rete, niente osmium.

Uso: python test_geofabrik_pois.py
"""
import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from unittest import mock
from xml.etree import ElementTree

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import geofabrik_pois  # noqa: E402


def quadrato(fid, minx, miny, maxx, maxy, parent=None):
    ring = [[minx, miny], [maxx, miny], [maxx, maxy], [minx, maxy], [minx, miny]]
    return {"properties": {"id": fid, "parent": parent, "urls": {"pbf": f"https://example.org/{fid}.osm.pbf"}},
            "geometry": {"type": "Polygon", "coordinates": [ring]}}


class ScegliEstrattiTest(unittest.TestCase):
    def test_foglie_che_toccano_il_bbox_senza_i_composti_ne_i_genitori(self):
        index = {"features": [
            quadrato("europa", 0, 0, 30, 30),
            quadrato("a", 0, 0, 10, 10, "europa"),
            quadrato("b", 10, 0, 20, 10, "europa"),
            quadrato("lontano", 25, 25, 30, 30, "europa"),
            # Composto: contiene "a" e "b" (come alps con la Svizzera).
            quadrato("a-e-b", -0.1, -0.1, 20.1, 10.1, "europa"),
        ]}

        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (5, 5, 15, 8))]

        self.assertEqual(sorted(chosen), ["a", "b"])

    def test_composto_con_il_confine_in_comune(self):
        # Come us e us-pacific con us/alaska: i vertici della foglia stanno sul bordo del composto.
        index = {"features": [
            quadrato("a", 0, 0, 10, 10),
            quadrato("b", 10, 0, 20, 10),
            quadrato("a-e-b", 0, 0, 20, 10),
        ]}

        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (5, 5, 15, 8))]

        self.assertEqual(sorted(chosen), ["a", "b"])

    def test_composto_scartato_anche_se_le_sue_parti_non_toccano_il_bbox(self):
        # Come britain-and-ireland per il Belgio: il bbox tocca solo il margine del composto.
        index = {"features": [
            quadrato("a", 0, 0, 10, 10),
            quadrato("a-con-margine", 0, 0, 13, 10),
            quadrato("b", 14, 0, 20, 10),
        ]}

        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (11, 5, 15, 8))]

        self.assertEqual(chosen, ["b"])

    def test_due_estratti_con_lo_stesso_rettangolo_ne_resta_uno(self):
        # Come south-africa e south-africa-and-lesotho.
        index = {"features": [quadrato("paese", 0, 0, 10, 10), quadrato("paese-e-altro", 0, 0, 10, 10)]}

        self.assertEqual([c for c, _ in geofabrik_pois.scegli_estratti(index, (1, 1, 4, 4))], ["paese"])

    def test_un_enclave_non_rende_composto_il_paese(self):
        # Come Ceuta e Melilla nel poligono del Marocco: il Marocco resta.
        index = {"features": [
            quadrato("paese", 0, 0, 10, 10),
            quadrato("enclave", 2, 2, 3, 3),
        ]}

        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (1, 1, 4, 4))]

        self.assertEqual(sorted(chosen), ["enclave", "paese"])

    def test_senza_foglie_il_composto_piu_piccolo(self):
        # Come Sint Maarten: solo central-america (che ha figli altrove) e north-america lo coprono.
        index = {"features": [
            quadrato("north-america", 0, 0, 30, 30),
            quadrato("central-america", 0, 0, 10, 10, "north-america"),
            quadrato("cuba", 0, 0, 2, 2, "central-america"),
        ]}

        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (6, 6, 7, 7))]

        self.assertEqual(chosen, ["central-america"])

    def test_bbox_tutto_dentro_un_estratto(self):
        index = {"features": [quadrato("a", 0, 0, 10, 10)]}

        self.assertEqual([c for c, _ in geofabrik_pois.scegli_estratti(index, (4, 4, 5, 5))], ["a"])


class FiltroOverpassTest(unittest.TestCase):
    def test_come_la_query_di_build_region(self):
        f = geofabrik_pois.filtro_overpass
        self.assertTrue(f("node", {"amenity": "bench"}))
        self.assertFalse(f("way", {"amenity": "bench"}))
        self.assertTrue(f("way", {"amenity": "parking"}))
        self.assertFalse(f("node", {"railway": "level_crossing"}))
        self.assertTrue(f("node", {"railway": "halt"}))
        self.assertFalse(f("relation", {"leisure": "park"}))
        self.assertTrue(f("relation", {"leisure": "park", "name": "Mezaparks"}))
        self.assertTrue(f("way", {"leisure": "marina"}))
        self.assertTrue(f("relation", {"leisure": "marina", "name": "Andrejosta"}))
        self.assertFalse(f("way", {"tourism": "information", "information": "board"}))
        self.assertTrue(f("way", {"tourism": "information", "information": "office"}))
        self.assertFalse(f("node", {"aeroway": "aerodrome"}))
        self.assertTrue(f("relation", {"aeroway": "aerodrome", "iata": "RIX"}))


class ScriviXmlTest(unittest.TestCase):
    def test_nodi_e_centri_nel_bbox_una_volta_sola(self):
        features = [
            {"type": "Feature", "geometry": {"type": "Point", "coordinates": [24.1, 56.9]},
             "properties": {"@type": "node", "@id": 1, "amenity": "cafe", "name": "Kafe & \"Rīga\""}},
            # Way chiusa: osmium la da' come linea e come area, deve uscire una volta sola.
            {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[24, 56], [24.2, 56.4]]},
             "properties": {"@type": "way", "@id": 2, "amenity": "parking"}},
            {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [[[24, 56], [24.2, 56], [24.2, 56.4], [24, 56]]]},
             "properties": {"@type": "way", "@id": 2, "amenity": "parking"}},
            {"type": "Feature", "geometry": {"type": "Point", "coordinates": [30, 60]},
             "properties": {"@type": "node", "@id": 3, "amenity": "cafe"}},
            {"type": "Feature", "geometry": {"type": "Point", "coordinates": [24, 56]},
             "properties": {"@type": "node", "@id": 4, "highway": "crossing"}},
        ]
        with tempfile.TemporaryDirectory() as tmp:
            source = os.path.join(tmp, "a.geojsonseq")
            with open(source, "w", encoding="utf-8") as f:
                f.writelines(json.dumps(x) + "\n" for x in features)
            out = os.path.join(tmp, "out.osm.xml")

            count = geofabrik_pois.scrivi_xml([source], (20, 55, 28, 58), out)
            with open(out, encoding="utf-8") as f:
                xml = f.read()

        self.assertEqual(count, 2)
        self.assertIn('<node id="1" lat="56.9000000" lon="24.1000000"><tag k="amenity" v="cafe"/>', xml)
        name = ElementTree.fromstring(xml).find("node/tag[@k='name']").get("v")
        self.assertEqual(name, 'Kafe & "Rīga"')
        self.assertIn('<way id="2"><center lat="56.2000000" lon="24.1000000"/>', xml)
        self.assertEqual(xml.count("<way "), 1)


class FileDatatoTest(unittest.TestCase):
    def test_il_piu_recente_della_cartella(self):
        listing = (b'<a href="iceland-260929.osm.pbf">x</a><a href="iceland-260930.osm.pbf">x</a>'
                   b'<a href="iceland-260930.osm.pbf.md5">x</a><a href="iceland-latest.osm.pbf">x</a>'
                   b'<a href="ireland-261001.osm.pbf">x</a>')
        response = io.BytesIO(listing)
        with mock.patch.object(geofabrik_pois.urllib.request, "urlopen", return_value=response):
            url = geofabrik_pois.url_datato("https://d.example/europe/iceland-latest.osm.pbf", "ua")

        self.assertEqual(url, "https://d.example/europe/iceland-260930.osm.pbf")

    def test_404_sul_latest_ripiega_sul_datato_con_id_con_la_barra(self):
        url = "https://d.example/north-america/us/alaska-latest.osm.pbf"
        error = urllib.error.HTTPError(url, 404, "Not Found", {}, None)
        calls = []

        def scarica(u, dest, _ua):
            calls.append((u, dest))
            if u == url:
                raise error

        with tempfile.TemporaryDirectory() as cache, \
                mock.patch.object(geofabrik_pois, "scarica", side_effect=scarica), \
                mock.patch.object(geofabrik_pois, "url_datato", return_value=url.replace("latest", "260930")), \
                mock.patch.object(geofabrik_pois.subprocess, "run"), \
                mock.patch.object(geofabrik_pois.os, "replace"), mock.patch.object(geofabrik_pois.os, "remove"):
            reduced = geofabrik_pois.estratto_ridotto("us/alaska", url, cache, "ua")

        self.assertEqual([u for u, _ in calls], [url, url.replace("latest", "260930")])
        self.assertEqual(os.path.dirname(calls[0][1]), cache)
        self.assertEqual(os.path.basename(reduced), "us-alaska-poi.osm.pbf")


if __name__ == "__main__":
    unittest.main()
