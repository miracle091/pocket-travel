#!/usr/bin/env python3
"""Test di geofabrik_pois.py sulla logica pura (scelta degli estratti, filtro, XML): niente rete, niente osmium.

Uso: python test_geofabrik_pois.py
"""
import json
import os
import sys
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
