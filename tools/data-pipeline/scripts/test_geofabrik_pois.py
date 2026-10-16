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


def vuoto(fid, parent):
    """Estratto con il poligono vuoto nell'indice (Giappone, sei stati USA dal 2026-10)."""
    return {"properties": {"id": fid, "parent": parent, "urls": {"pbf": f"https://example.org/{fid}-latest.osm.pbf"}},
            "geometry": {"type": "MultiPolygon", "coordinates": [[[]]]}}


def scrivi(text):
    """Finto scarica che scrive text al posto del file .poly."""
    def scarica(url, dest, _ua):
        with open(dest, "w", encoding="utf-8") as f:
            f.write(text(url) if callable(text) else text)
    return scarica


class CompletaGeometrieTest(unittest.TestCase):
    def test_anelli_del_file_poly_senza_i_buchi(self):
        poly = "isola\n1\n  0 0\n  4 0\n  4 4\nEND\n!1\n  1 1\n  2 1\n  2 2\nEND\n2\n  9 9\n  10 9\n  10 10\nEND\nEND\n"

        self.assertEqual(geofabrik_pois.anelli_poly(poly), [[[[0, 0], [4, 0], [4, 4]]], [[[9, 9], [10, 9], [10, 10]]]])
        self.assertEqual(geofabrik_pois.anelli_poly("none\nEND\n"), [])

    def test_poligono_dal_poly_o_dal_genitore_e_l_estratto_resta_una_foglia(self):
        index = {"features": [
            quadrato("giappone", 0, 0, 20, 10),
            quadrato("kanto", 10, 0, 20, 10, "giappone"),
            vuoto("kansai", "giappone"),
            vuoto("kyushu", "giappone"),
        ]}
        # kansai: .geojson vuoto (Geofabrik risponde 200 senza contenuto), poligono dal .poly; kyushu: vuoti tutti e due.
        files = {"kansai.poly": "kansai\n1\n  0 0\n  5 0\n  5 5\n  0 5\nEND\nEND\n", "kyushu.poly": "none\nEND\n"}

        with tempfile.TemporaryDirectory() as cache, \
                mock.patch.object(geofabrik_pois, "scarica", side_effect=scrivi(lambda url: files.get(url.rsplit("/", 1)[1], ""))):
            geofabrik_pois.completa_geometrie(index, cache, "test")

        # kyushu ha il poligono del Giappone, che copre anche kanto: resta una foglia, non un composto.
        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (1, 1, 2, 2))]
        self.assertEqual(sorted(chosen), ["kansai", "kyushu"])

    def test_il_geojson_dell_estratto_viene_prima_del_poly(self):
        index = {"features": [quadrato("giappone", 0, 0, 20, 10), vuoto("kyushu", "giappone")]}
        geojson = '{ "type" : "MultiPolygon", "coordinates" : [[[[0,0],[3,0],[3,3],[0,3],[0,0]]]] }'

        with tempfile.TemporaryDirectory() as cache, \
                mock.patch.object(geofabrik_pois, "scarica", side_effect=scrivi(lambda url: geojson if url.endswith(".geojson") else "none\nEND\n")):
            geofabrik_pois.completa_geometrie(index, cache, "test")

        kyushu = index["features"][1]
        self.assertEqual(kyushu["geometry"]["coordinates"], [[[[0, 0], [3, 0], [3, 3], [0, 3], [0, 0]]]])
        self.assertNotIn("poligono_del_genitore", kyushu["properties"])

    def test_senza_poligono_ne_genitore_esce(self):
        index = {"features": [vuoto("solo", None)]}
        with tempfile.TemporaryDirectory() as cache, \
                mock.patch.object(geofabrik_pois, "scarica", side_effect=scrivi(lambda url: "none\nEND\n" if url.endswith(".poly") else "")), \
                self.assertRaises(SystemExit):
            geofabrik_pois.completa_geometrie(index, cache, "test")

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


def anello(x0, y0, x1, y1):
    return [[x0, y0], [x1, y0], [x1, y1], [x0, y1], [x0, y0]]


class ConfineTest(unittest.TestCase):
    # Regione 110-112 E, 40-42 N con un buco (Pechino nell'Hebei) a 110,8-111,2; un'isola a 113-113,2.
    CONFINE = {"type": "Feature", "properties": {}, "geometry": {"type": "MultiPolygon", "coordinates": [
        [anello(110, 40, 112, 42), anello(110.8, 40.8, 111.2, 41.2)], [anello(113, 40, 113.2, 40.2)]]}}

    def test_dentro_fuori_buco_e_isola_con_poche_o_tante_fasce(self):
        for fasce in (1, 3, None):
            confine = geofabrik_pois.Confine(self.CONFINE, fasce)
            self.assertTrue(confine.dentro(110.5, 41.5), fasce)
            self.assertFalse(confine.dentro(111, 41), fasce)
            self.assertTrue(confine.dentro(113.1, 40.1), fasce)
            self.assertFalse(confine.dentro(112.5, 41), fasce)
            self.assertFalse(confine.dentro(111, 39), fasce)
            self.assertFalse(confine.dentro(111, 43), fasce)

    def test_fascia_di_100_metri_oltre_il_confine_anche_nel_buco(self):
        confine = geofabrik_pois.Confine(self.CONFINE["geometry"])
        # A 41 N un grado di longitudine e' circa 84 km: 0,0006 gradi sono circa 50 m, 0,0018 circa 150 m.
        self.assertTrue(confine.tiene(112.0006, 41))
        self.assertFalse(confine.tiene(112.0018, 41))
        self.assertTrue(confine.tiene(110.8006, 41))
        self.assertFalse(confine.tiene(110.8018, 41))
        # Oltre il vertice, in diagonale: circa 71 m.
        self.assertTrue(confine.tiene(112.0006, 42.00045))
        self.assertAlmostEqual(confine.distanza(112.0018, 41, 1000), 151, delta=2)

    def test_scrivi_xml_scarta_i_membri_della_relazione_fuori_dal_confine(self):
        # Tratti di una relazione lunga (la Grande Muraglia) che osmium extract -S types=any completa per intero: uno
        # dentro, uno a cavallo con il centro a 50 m dal confine, uno lontano, uno nel buco; e la relazione stessa
        # (type=route, centro dai membri) con il centro fuori.
        muro = {"historic": "citywalls", "name": "Great Wall of China", "wikidata": "Q12501"}
        features = [
            {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[110.2, 41.5], [110.4, 41.6]]},
             "properties": {"@type": "way", "@id": 1, **muro}},
            {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[111.99, 41], [112.0112, 41]]},
             "properties": {"@type": "way", "@id": 2, **muro}},
            {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[112.4, 41.2], [112.6, 41.3]]},
             "properties": {"@type": "way", "@id": 3, **muro}},
            {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[110.9, 40.9], [111.1, 41.1]]},
             "properties": {"@type": "way", "@id": 4, **muro}},
            {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [[[112.4, 41.2], [112.6, 41.2], [112.6, 41.3], [112.4, 41.2]]]},
             "properties": {"@type": "way", "@id": 3, **muro}},
            {"type": "Feature", "geometry": {"type": "Point", "coordinates": [111.5, 41.5]},
             "properties": {"@type": "node", "@id": 5, "amenity": "cafe"}},
        ]
        relazioni = [(9, {"type": "route", "historic": "citywalls", "name": "Great Wall of China", "wikidata": "Q12501"},
                      113.5, 41.0)]
        with tempfile.TemporaryDirectory() as tmp:
            source = os.path.join(tmp, "a.geojsonseq")
            with open(source, "w", encoding="utf-8") as f:
                f.writelines(json.dumps(x) + "\n" for x in features)
            out = os.path.join(tmp, "out.osm.xml")
            with mock.patch("sys.stdout", new_callable=io.StringIO) as stdout:
                count = geofabrik_pois.scrivi_xml([source], (109, 39, 114, 43), out, relazioni,
                                                  geofabrik_pois.Confine(self.CONFINE))
            root = ElementTree.parse(out).getroot()

        self.assertEqual(count, 3)
        self.assertEqual(sorted((e.tag, e.get("id")) for e in root), [("node", "5"), ("way", "1"), ("way", "2")])
        self.assertIn("3 oggetti con il centro oltre 100 m dal confine scartati", stdout.getvalue())


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


class GeometriaTest(unittest.TestCase):
    ring = [(0, 0), (10, 0), (10, 10), (0, 10)]

    def test_punto_nel_poligono(self):
        self.assertTrue(geofabrik_pois.dentro(5, 5, self.ring))
        self.assertFalse(geofabrik_pois.dentro(11, 5, self.ring))
        self.assertFalse(geofabrik_pois.dentro(5, -1, self.ring))

    def test_bbox_tocca_in_tutti_e_tre_i_modi(self):
        bbox_dentro = (4, 4, 6, 6)       # nessun vertice dell'anello nel bbox, ma gli angoli del bbox sono nell'anello
        bbox_vertice = (9, 9, 20, 20)    # un vertice dell'anello dentro il bbox
        self.assertTrue(geofabrik_pois.tocca_bbox([self.ring], bbox_dentro))
        self.assertTrue(geofabrik_pois.tocca_bbox([self.ring], bbox_vertice))
        # Striscia lunga che attraversa il quadrato senza vertici dentro il bbox e senza angoli dentro l'anello.
        striscia = [(-5, 4), (15, 4), (15, 6), (-5, 6)]
        self.assertTrue(geofabrik_pois.tocca_bbox([striscia], (4, -5, 6, 15)))
        self.assertFalse(geofabrik_pois.tocca_bbox([self.ring], (20, 20, 30, 30)))
        self.assertFalse(geofabrik_pois.tocca_bbox([], (0, 0, 1, 1)))

    def test_rettangolo_e_area(self):
        rect = geofabrik_pois.rettangolo([self.ring, [(-2, 3), (4, 12), (1, 1)]])
        self.assertEqual((-2, 0, 10, 12), rect)
        self.assertEqual(144, geofabrik_pois.area(rect))

    def test_centro_di_punto_poligono_e_multipoligono(self):
        self.assertEqual([9.5, 45.1], geofabrik_pois.centro({"type": "Point", "coordinates": [9.5, 45.1]}))
        # Come nel GeoJSON letto da json: liste, non tuple
        square = [[0, 0], [10, 0], [10, 10], [0, 10], [0, 0]]
        self.assertEqual((5.0, 5.0), geofabrik_pois.centro({"type": "Polygon", "coordinates": [square]}))
        multi = {"type": "MultiPolygon", "coordinates": [[[[0, 0], [2, 0], [2, 2], [0, 0]]], [[[8, 8], [10, 8], [10, 10], [8, 8]]]]}
        self.assertEqual((5.0, 5.0), geofabrik_pois.centro(multi))
        self.assertEqual((2.0, 1.0), geofabrik_pois.centro({"type": "LineString", "coordinates": [[0, 0], [4, 2]]}))

    def test_nome_file_senza_barre(self):
        self.assertEqual("us-alaska", geofabrik_pois.nome_file("us/alaska"))
        self.assertEqual("italy", geofabrik_pois.nome_file("italy"))


class PoligoniTest(unittest.TestCase):
    def test_poly_con_buco_e_righe_vuote(self):
        poly = "nome\n1\n  0 0\n  4 0\n  4 4\n\nEND\n!2\n 1 1\n 2 1\n 2 2\nEND\nEND\n"
        self.assertEqual([[[[0.0, 0.0], [4.0, 0.0], [4.0, 4.0]]]], geofabrik_pois.anelli_poly(poly))

    def test_poly_con_notazione_scientifica(self):
        poly = "n\n1\n 1.0E+01 -5.0e-1\n 2 3\n 4 5\nEND\nEND\n"
        self.assertEqual([[[[10.0, -0.5], [2.0, 3.0], [4.0, 5.0]]]], geofabrik_pois.anelli_poly(poly))

    def test_anelli_di_poligono_e_multipoligono_scartano_i_vuoti(self):
        ring = [[0, 0], [1, 0], [1, 1], [0, 0]]
        self.assertEqual([ring], geofabrik_pois.anelli({"type": "Polygon", "coordinates": [ring, [[0.2, 0.2], [0.4, 0.2], [0.3, 0.3]]]}))
        self.assertEqual([], geofabrik_pois.anelli({"type": "Polygon", "coordinates": [[]]}))
        self.assertEqual([ring], geofabrik_pois.anelli({"type": "MultiPolygon", "coordinates": [[ring], [], [[]]]}))

    def test_geojson_non_valido_o_senza_geometria(self):
        self.assertEqual([], geofabrik_pois.anelli_geojson("<html>404</html>"))
        self.assertEqual([], geofabrik_pois.anelli_geojson("{}"))
        self.assertEqual([], geofabrik_pois.anelli_geojson("null"))

    def test_opl_con_caratteri_speciali_e_nodi_senza_coordinate(self):
        opl = ("n1 x9.5 y45.25\n"
               "n2 v1\n"                       # nodo senza posizione: ignorato
               "w10 Nn1,n2,n3\n"
               "r20 Mw10@outer,n1@,r21 Tname=Caff%e8%,type=site\n"
               "\n")
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "x.opl")
            with open(path, "w", encoding="utf-8") as f:
                f.write(opl)
            nodes, ways, relations = geofabrik_pois.leggi_opl(path)
        self.assertEqual({1: (9.5, 45.25)}, nodes)
        self.assertEqual({10: [1, 2, 3]}, ways)
        self.assertEqual([("w", 10), ("n", 1), ("r", 21)], relations[20][1])
        self.assertEqual("site", relations[20][0]["type"])
        self.assertEqual("Caffè", relations[20][0]["name"])


class FakeDownload:
    def __init__(self, body, length="auto", status=None):
        self.body = body
        self.headers = {} if length is None else {"Content-Length": str(len(body) if length == "auto" else length)}
        self.offset = 0

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, size):
        chunk = self.body[self.offset:self.offset + size]
        self.offset += len(chunk)
        return chunk


class ScaricaTest(unittest.TestCase):
    def setUp(self):
        for patcher in (mock.patch("time.sleep"), mock.patch("builtins.print")):
            patcher.start()
            self.addCleanup(patcher.stop)
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dest = os.path.join(tmp.name, "estratto.osm.pbf")

    def test_file_completo_sostituisce_il_parziale(self):
        with mock.patch.object(geofabrik_pois.urllib.request, "urlopen", return_value=FakeDownload(b"dati" * 10)):
            geofabrik_pois.scarica("https://d.example/x.osm.pbf", self.dest, "ua")
        with open(self.dest, "rb") as f:
            self.assertEqual(b"dati" * 10, f.read())
        self.assertFalse(os.path.exists(self.dest + ".part"))

    def test_file_troncato_si_riprova_poi_si_arrende(self):
        with mock.patch.object(geofabrik_pois.urllib.request, "urlopen",
                               side_effect=lambda *a, **k: FakeDownload(b"abc", length=100)) as urlopen:
            with self.assertRaises(OSError) as ctx:
                geofabrik_pois.scarica("https://d.example/x.osm.pbf", self.dest, "ua", attempts=3)
        self.assertEqual(3, urlopen.call_count)
        self.assertIn("3 byte invece di 100", str(ctx.exception))
        self.assertFalse(os.path.exists(self.dest))

    def test_troncato_al_primo_tentativo_riuscito_al_secondo(self):
        responses = [FakeDownload(b"abc", length=10), FakeDownload(b"0123456789")]
        with mock.patch.object(geofabrik_pois.urllib.request, "urlopen", side_effect=responses):
            geofabrik_pois.scarica("https://d.example/x.osm.pbf", self.dest, "ua")
        with open(self.dest, "rb") as f:
            self.assertEqual(b"0123456789", f.read())

    def test_senza_content_length_il_file_si_accetta(self):
        with mock.patch.object(geofabrik_pois.urllib.request, "urlopen", return_value=FakeDownload(b"xyz", length=None)):
            geofabrik_pois.scarica("https://d.example/x.osm.pbf", self.dest, "ua")
        self.assertTrue(os.path.exists(self.dest))

    def test_4xx_non_si_riprova(self):
        error = urllib.error.HTTPError("https://d.example/x", 404, "Not Found", {}, None)
        with mock.patch.object(geofabrik_pois.urllib.request, "urlopen", side_effect=error) as urlopen:
            with self.assertRaises(urllib.error.HTTPError):
                geofabrik_pois.scarica("https://d.example/x.osm.pbf", self.dest, "ua")
        self.assertEqual(1, urlopen.call_count)

    def test_5xx_si_riprova(self):
        error = urllib.error.HTTPError("https://d.example/x", 503, "Unavailable", {}, None)
        with mock.patch.object(geofabrik_pois.urllib.request, "urlopen", side_effect=[error, FakeDownload(b"ok")]) as urlopen:
            geofabrik_pois.scarica("https://d.example/x.osm.pbf", self.dest, "ua")
        self.assertEqual(2, urlopen.call_count)

    def test_nessun_file_datato_nell_elenco(self):
        with mock.patch.object(geofabrik_pois.urllib.request, "urlopen", side_effect=lambda *a, **k: io.BytesIO(b"<html>niente</html>")):
            with self.assertRaises(OSError) as ctx:
                geofabrik_pois.url_datato("https://d.example/europe/iceland-latest.osm.pbf", "ua")
        self.assertIn("nessun file datato per iceland", str(ctx.exception))

    def test_elenco_non_raggiungibile_prova_la_pagina_dell_estratto(self):
        def opened(request, timeout):
            if request.full_url.endswith("/"):
                raise OSError("rete assente")
            return io.BytesIO(b'<a href="iceland-261001.osm.pbf">x</a>')
        with mock.patch.object(geofabrik_pois.urllib.request, "urlopen", side_effect=opened):
            url = geofabrik_pois.url_datato("https://d.example/europe/iceland-latest.osm.pbf", "ua")
        self.assertEqual("https://d.example/europe/iceland-261001.osm.pbf", url)


if __name__ == "__main__":
    unittest.main()
