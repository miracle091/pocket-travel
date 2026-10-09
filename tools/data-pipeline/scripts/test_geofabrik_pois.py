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

    def test_con_iso_solo_gli_estratti_della_nazione_figli_compresi(self):
        # Come la Norvegia: il bbox contiene la Svezia e la Finlandia, e nord-norge sta sotto norway.
        norvegia = quadrato("norway", 0, 0, 10, 10, "europa")
        nord = quadrato("nord", 0, 5, 10, 10, "norway")
        sud = quadrato("sud", 0, 0, 10, 5, "norway")
        svezia = quadrato("svezia", 10, 0, 20, 10, "europa")
        for f, iso in ((norvegia, ["NO"]), (svezia, ["SE"])):
            f["properties"]["iso3166-1:alpha2"] = iso
        index = {"features": [quadrato("europa", 0, 0, 20, 10), norvegia, nord, sud, svezia]}

        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (2, 2, 15, 8), "no")]

        self.assertEqual(sorted(chosen), ["nord", "sud"])

    def test_con_iso_la_sottodivisione_vale_per_il_suo_paese(self):
        # Come us/alaska: ha solo iso3166-2 e come genitore il continente; yukon e' canadese.
        alaska = quadrato("us/alaska", 0, 0, 10, 10, "continente")
        alaska["properties"]["iso3166-2"] = ["US-AK"]
        yukon = quadrato("yukon", 10, 0, 20, 10, "canada")
        canada = quadrato("canada", 10, 0, 20, 10, "continente")
        canada["properties"]["iso3166-1:alpha2"] = ["CA"]
        index = {"features": [quadrato("continente", 0, 0, 20, 10), alaska, canada, yukon]}

        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (2, 2, 15, 8), "us")]

        self.assertEqual(chosen, ["us/alaska"])

    def test_con_iso_senza_estratti_della_nazione_si_usa_solo_il_bbox(self):
        # Come San Marino: nessun estratto suo, vale l'estratto del paese che lo contiene.
        italia = quadrato("italia", 0, 0, 10, 10)
        italia["properties"]["iso3166-1:alpha2"] = ["IT"]
        index = {"features": [italia]}

        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (4, 4, 5, 5), "sm")]

        self.assertEqual(chosen, ["italia"])

    def test_con_iso_paese_mancante_dall_indice(self):
        # gcc-states contiene l'Arabia Saudita ma l'indice non la elenca.
        gcc = quadrato("gcc-states", 0, 0, 10, 10)
        gcc["properties"]["iso3166-1:alpha2"] = ["QA"]
        iran = quadrato("iran", 10, 0, 20, 10)
        iran["properties"]["iso3166-1:alpha2"] = ["IR"]
        index = {"features": [gcc, iran]}

        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (2, 2, 15, 8), "sa")]

        self.assertEqual(chosen, ["gcc-states"])

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
        self.assertTrue(f("way", {"tourism": "attraction", "man_made": "tower", "name": "Tour Eiffel"}))
        self.assertTrue(f("relation", {"tourism": "museum", "name": "Musée du Louvre"}))
        self.assertTrue(f("way", {"historic": "monument", "name": "Colosseo"}))
        self.assertFalse(f("way", {"tourism": "attraction"}))
        self.assertFalse(f("way", {"historic": "memorial", "name": "Lapide"}))
        self.assertFalse(f("way", {"tourism": "information", "information": "board"}))
        self.assertTrue(f("way", {"tourism": "information", "information": "office"}))
        self.assertFalse(f("node", {"aeroway": "aerodrome"}))
        self.assertTrue(f("relation", {"aeroway": "aerodrome", "iata": "RIX"}))

    def test_luoghi_famosi_solo_con_wikidata(self):
        f = geofabrik_pois.filtro_overpass
        self.assertTrue(f("relation", {"amenity": "place_of_worship", "name": "Ibn Tulun"}))
        self.assertTrue(f("relation", {"boundary": "national_park", "name": "Parque Nacional del Manu"}))
        self.assertTrue(f("relation", {"boundary": "protected_area", "protect_class": "2", "name": "Fuji-Hakone-Izu"}))
        self.assertFalse(f("relation", {"boundary": "protected_area", "protect_class": "5", "name": "Paesaggio"}))
        self.assertFalse(f("relation", {"boundary": "national_park"}))
        for tags in ({"man_made": "bridge"}, {"historic": "citywalls"}, {"landuse": "religious"}, {"leisure": "garden"}):
            self.assertFalse(f("way", {**tags, "name": "Senza wikidata"}), tags)
            self.assertTrue(f("way", {**tags, "name": "Famoso", "wikidata": "Q1"}), tags)
        self.assertFalse(f("node", {"man_made": "bridge", "name": "Famoso", "wikidata": "Q1"}))
        self.assertTrue(f("node", {"place": "square", "name": "Place des Vosges", "wikidata": "Q898629"}))
        self.assertFalse(f("way", {"place": "square", "name": "Piazzetta"}))


class RelazioniNonAreeTest(unittest.TestCase):
    def test_opl_e_centro_dai_membri_anche_annidati(self):
        opl = (
            "n1 x12.3350000 y45.4380000\n"
            "n2 x12.3360000 y45.4385000\n"
            "n3 x12.3400000 y45.4300000\n"
            "n9 x12.0000000 y45.0000000\n"
            "w10 Tbuilding:part=yes Nn1,n2\n"
            "r20 Ttype=building,tourism=attraction,name=Ponte%20%di%20%Rialto,wikidata=Q52505 Mw10@outline,r21@,n99@\n"
            "r21 Ttype=site Mn3@\n"
            "r22 T Mn9@\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "a.opl")
            with open(path, "w", encoding="utf-8") as f:
                f.write(opl)
            nodes, ways, relations = geofabrik_pois.leggi_opl(path)

        self.assertEqual(nodes[1], (12.335, 45.438))
        self.assertEqual(ways[10], [1, 2])
        tags, members = relations[20]
        self.assertEqual(tags["name"], "Ponte di Rialto")
        self.assertEqual(members, [("w", 10), ("r", 21), ("n", 99)])
        self.assertEqual(relations[22], ({}, [("n", 9)]))
        # Il nodo 99 manca (fuori dal ritaglio): conta quello che c'e'.
        points = geofabrik_pois.punti_relazione(20, nodes, ways, relations)
        self.assertEqual(sorted(points), [(12.335, 45.438), (12.336, 45.4385), (12.34, 45.43)])

    def test_relazione_ciclica_finisce(self):
        relations = {1: ({}, [("r", 2)]), 2: ({}, [("r", 1), ("n", 5)])}

        self.assertEqual(geofabrik_pois.punti_relazione(1, {5: (1.0, 2.0)}, {}, relations), [(1.0, 2.0)])


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

            relazioni = [(7, {"type": "site", "tourism": "museum", "name": "Muzejs"}, 24.15, 56.95),
                         (8, {"type": "site", "tourism": "museum", "name": "Lontano"}, 30.0, 60.0)]
            count = geofabrik_pois.scrivi_xml([source], (20, 55, 28, 58), out, relazioni)
            with open(out, encoding="utf-8") as f:
                xml = f.read()

        self.assertEqual(count, 3)
        self.assertIn('<relation id="7"><center lat="56.9500000" lon="24.1500000"/><tag k="type" v="site"/>', xml)
        self.assertNotIn('id="8"', xml)
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

    def test_cartella_di_primo_livello_usa_la_pagina_dell_estratto(self):
        # asia/ rimanda ad asia.html, senza file datati: si leggono quelli di gcc-states.html.
        pages = {
            "https://d.example/asia/": b'<a href="gcc-states-latest.osm.pbf">x</a>',
            "https://d.example/asia/gcc-states.html":
                b'<a href="gcc-states-140101.osm.pbf">x</a><a href="gcc-states-260929.osm.pbf">x</a>',
        }
        opened = lambda request, timeout: io.BytesIO(pages[request.full_url])
        with mock.patch.object(geofabrik_pois.urllib.request, "urlopen", side_effect=opened):
            url = geofabrik_pois.url_datato("https://d.example/asia/gcc-states-latest.osm.pbf", "ua")

        self.assertEqual(url, "https://d.example/asia/gcc-states-260929.osm.pbf")

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
