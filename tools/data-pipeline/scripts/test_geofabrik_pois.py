#!/usr/bin/env python3
"""Test di geofabrik_pois.py sulla logica pura (scelta degli estratti, filtro, XML): niente rete, niente osmium.

Uso: python test_geofabrik_pois.py
"""
import io
import json
import math
import os
import random
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
        # Come la Città del Vaticano: nessun estratto suo, vale l'estratto del paese che lo contiene.
        italia = quadrato("italia", 0, 0, 10, 10)
        italia["properties"]["iso3166-1:alpha2"] = ["IT"]
        index = {"features": [italia]}

        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (4, 4, 5, 5), "va")]

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

    def test_con_iso_nazione_che_sta_solo_in_un_estratto_composto(self):
        # Come le Isole Vergini Britanniche: il bbox tocca solo la foglia us/us-virgin-islands, che non le contiene.
        isole_usa = quadrato("us/us-virgin-islands", 0, 0, 5, 10, "north-america")
        isole_usa["properties"]["iso3166-1:alpha2"] = ["VI"]
        index = {"features": [quadrato("north-america", 0, 0, 30, 30), isole_usa,
                              quadrato("central-america", 0, 0, 20, 20, "north-america"),
                              quadrato("cuba", 15, 15, 20, 20, "central-america")]}

        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (2, 2, 8, 8), "vg")]

        self.assertEqual(chosen, ["central-america"])

    def test_con_iso_nazione_tra_due_foglie_che_non_la_coprono(self):
        # Come San Marino: il bbox tocca centro e nord-est, ma solo italy contiene la nazione.
        index = {"features": [quadrato("italy", 0, 0, 20, 10),
                              quadrato("centro", 0, 0, 9, 10, "italy"), quadrato("nord-est", 11, 0, 20, 10, "italy")]}

        chosen = [cid for cid, _ in geofabrik_pois.scegli_estratti(index, (8, 4, 12, 6), "sm")]

        self.assertEqual(chosen, ["italy"])

    def test_con_iso_nazione_con_l_estratto_fuori_da_geofabrik(self):
        # Come Saint-Pierre e Miquelon: la foglia vicina (Terranova) non lo contiene, l'estratto e' di openstreetmap.fr.
        index = {"features": [quadrato("newfoundland-and-labrador", 0, 0, 10, 10)]}

        chosen = geofabrik_pois.scegli_estratti(index, (2, 2, 3, 3), "pm")

        self.assertEqual(chosen, [("saint_pierre_et_miquelon", geofabrik_pois.ESTRATTI_DELLE_NAZIONI["PM"])])
        self.assertTrue(chosen[0][1].startswith("https://download.openstreetmap.fr/"))

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
    def test_opl_e_punto_da_un_membro_anche_annidato(self):
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
        # Il punto viene sempre dalla way membro con l'id piu' basso (qui l'unica), a meta' della sua lunghezza.
        x, y = geofabrik_pois.punto_relazione(20, nodes, ways, relations)
        self.assertEqual((round(x, 5), round(y, 5)), (12.3355, 45.43825))
        # Senza way, il nodo; senza nodi, la relazione figlia.
        self.assertEqual(geofabrik_pois.punto_relazione(21, nodes, ways, relations), (12.34, 45.43))
        relations[23] = ({}, [("r", 21), ("r", 99)])
        self.assertEqual(geofabrik_pois.punto_relazione(23, nodes, ways, relations), (12.34, 45.43))

    def test_way_con_lo_stesso_id_della_relazione(self):
        relations = {100: ({}, [("w", 100)])}

        self.assertEqual(geofabrik_pois.punto_relazione(100, {1: (0.0, 0.0), 2: (2.0, 0.0)}, {100: [1, 2]}, relations),
                         (1.0, 0.0))

    def test_membro_scelto_fuori_dal_ritaglio(self):
        # La way 10 (id piu' basso) non c'e': il punto sarebbe fuori dal ritaglio, la relazione la tiene un'altra regione.
        relations = {1: ({}, [("w", 11), ("w", 10)])}
        ways = {11: [1, 2]}
        nodes = {1: (0.0, 0.0), 2: (1.0, 0.0)}

        self.assertIsNone(geofabrik_pois.punto_relazione(1, nodes, ways, relations))
        # Way con un nodo mancante: niente punto parziale, diverso da quello delle altre regioni.
        self.assertIsNone(geofabrik_pois.punto_relazione(1, {1: (0.0, 0.0)}, {10: [1, 2], 11: [1, 2]}, relations))

    def test_relazione_ciclica_finisce(self):
        relations = {1: ({}, [("r", 2)]), 2: ({}, [("r", 1), ("n", 5)])}

        self.assertEqual(geofabrik_pois.punto_relazione(1, {5: (1.0, 2.0)}, {}, relations), (1.0, 2.0))


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

    def test_tante_fasce_come_una_sola(self):
        # Confine con molti lati (un cerchio di 1.600): le fasce scalate danno gli stessi risultati di una fascia sola.
        cerchio = [(math.cos(i * math.pi / 800) * 2, math.sin(i * math.pi / 800)) for i in range(1600)]
        geometry = {"type": "Polygon", "coordinates": [cerchio + cerchio[:1]]}
        tante, una = geofabrik_pois.Confine(geometry), geofabrik_pois.Confine(geometry, 1)
        self.assertEqual(tante.n, 100)
        rnd = random.Random(2)
        for x, y in [(rnd.uniform(-2.2, 2.2), rnd.uniform(-1.1, 1.1)) for _ in range(2000)] + cerchio:
            self.assertEqual(tante.dentro(x, y), una.dentro(x, y), (x, y))

    def test_punto_sul_confine_comune_in_una_sola_regione(self):
        # Due regioni con il lato in comune percorso in versi opposti, vicino al meridiano 0 dove l'arrotondamento conta.
        rnd = random.Random(1)
        comune = [(rnd.uniform(-0.001, 0.001), i / 50) for i in range(51)]
        ovest = geofabrik_pois.Confine({"type": "Polygon", "coordinates": [comune + [(-1, 1), (-1, 0), comune[0]]]})
        est = geofabrik_pois.Confine({"type": "Polygon", "coordinates": [[comune[0], (1, 0), (1, 1)] + comune[::-1]]})
        for x, y in comune[1:-1]:
            self.assertEqual(ovest.dentro(x, y) + est.dentro(x, y), 1, (x, y))

    def test_punto_sull_oggetto(self):
        punto = geofabrik_pois.punto
        self.assertEqual(punto({"type": "Point", "coordinates": [9.5, 45.1]}), (9.5, 45.1))
        # Via aperta: a meta' della lunghezza (4 su 8), non al centro del rettangolo (2, 1,5).
        x, y = punto({"type": "LineString", "coordinates": [[0, 0], [0, 3], [4, 3], [4, 2]]})
        self.assertEqual((round(x, 2), y), (1.0, 3.0))
        # Area convessa: il centro del rettangolo, come "out center" di Overpass; la way chiusa come linea vale uguale.
        quadrato = anello(0, 0, 4, 2)
        self.assertEqual(punto({"type": "Polygon", "coordinates": [quadrato]}), (2.0, 1.0))
        self.assertEqual(punto({"type": "LineString", "coordinates": quadrato}), (2.0, 1.0))
        # Area a C, il centro del rettangolo e' nel vuoto: il centro del tratto interno piu' largo a meta' altezza.
        c = [[0, 0], [10, 0], [10, 2], [2, 2], [2, 8], [10, 8], [10, 10], [0, 10], [0, 0]]
        self.assertEqual(punto({"type": "Polygon", "coordinates": [c]}), (1.0, 5.0))
        # Centro nel buco: un punto dell'area.
        x, y = punto({"type": "Polygon", "coordinates": [anello(0, 0, 10, 10), anello(2, 2, 8, 8)]})
        self.assertTrue(x < 2 or x > 8)
        # Multipoligono: il centro del rettangolo cade tra le parti, il punto nella parte piu' grande.
        multi = {"type": "MultiPolygon", "coordinates": [[anello(0, 0, 1, 1)], [anello(5, 0, 9, 4)]]}
        self.assertEqual(punto(multi), (7.0, 2.0))

    def test_scrivi_xml_un_oggetto_in_una_sola_regione(self):
        # Tratti di una relazione lunga (la Grande Muraglia) che osmium extract -S types=any completa per intero: uno
        # dentro, uno a cavallo del confine con la regione vicina (110-112 e 112-114 E), uno lontano, uno nel buco; una
        # riserva a cavallo; la relazione stessa (type=route, centro dai membri) fuori da tutte e due.
        muro = {"historic": "citywalls", "name": "Great Wall of China", "wikidata": "Q12501"}
        features = [
            {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[110.2, 41.5], [110.4, 41.6]]},
             "properties": {"@type": "way", "@id": 1, **muro}},
            {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[111.5, 41], [112.1, 41]]},
             "properties": {"@type": "way", "@id": 2, **muro}},
            {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[115.4, 41.2], [115.6, 41.3]]},
             "properties": {"@type": "way", "@id": 3, **muro}},
            {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[110.9, 40.9], [111.1, 41.1]]},
             "properties": {"@type": "way", "@id": 4, **muro}},
            {"type": "Feature", "geometry": {"type": "Point", "coordinates": [111.5, 41.5]},
             "properties": {"@type": "node", "@id": 5, "amenity": "cafe"}},
            {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [anello(111.9, 41, 113, 41.2)]},
             "properties": {"@type": "way", "@id": 6, "leisure": "nature_reserve", "name": "Riserva"}},
        ]
        relazioni = [(9, {"type": "route", "historic": "citywalls", "name": "Great Wall of China", "wikidata": "Q12501"},
                      115.5, 41.0)]
        vicina = {"type": "Polygon", "coordinates": [anello(112, 40, 114, 42)]}
        ids, logs = {}, {}
        with tempfile.TemporaryDirectory() as tmp:
            source = os.path.join(tmp, "a.geojsonseq")
            with open(source, "w", encoding="utf-8") as f:
                f.writelines(json.dumps(x) + "\n" for x in features)
            for nome, confine in (("regione", self.CONFINE), ("vicina", vicina)):
                out = os.path.join(tmp, f"{nome}.osm.xml")
                with mock.patch("sys.stdout", new_callable=io.StringIO) as stdout:
                    geofabrik_pois.scrivi_xml([source], (109, 39, 116, 43), out, relazioni,
                                              geofabrik_pois.Confine(confine))
                ids[nome] = sorted((e.tag, e.get("id")) for e in ElementTree.parse(out).getroot())
                logs[nome] = stdout.getvalue()

        self.assertEqual(ids["regione"], [("node", "5"), ("way", "1"), ("way", "2")])
        self.assertEqual(ids["vicina"], [("way", "6")])
        self.assertIn("4 oggetti fuori dal confine scartati", logs["regione"])


class SorelleTest(unittest.TestCase):
    # Le 10 regioni russe sul confine della nazione (regions.sh): 17 coppie di rettangoli si sovrappongono.
    RUSSIA = {
        "russia-caucaso-settentrionale": (32.50, 41.19, 48.58, 50.23), "russia-centro": (30.79, 51.78, 47.61, 59.64),
        "russia-nord-europeo": (28.41, 58.49, 68.95, 81.86), "russia-nord-ovest": (27.35, 55.57, 36.18, 61.33),
        "russia-terra-nera-centrale": (34.11, 49.56, 43.24, 53.83), "russia-urali-europei": (50.79, 50.49, 61.59, 61.69),
        "russia-volga": (41.16, 44.74, 54.18, 56.67), "russia-volga-vjatka": (41.78, 53.66, 53.93, 61.06),
        "russia-siberia": (60.00, 45.00, 120.00, 82.00), "russia-estremo-oriente": (120.00, 41.50, 180.00, 77.50),
    }

    def vincitrici(self, lon, lat, inverti=False):
        out = []
        for rid, bbox in self.RUSSIA.items():
            sorelle = [(s, b) for s, b in self.RUSSIA.items() if s != rid]
            if inverti:
                sorelle.reverse()
            minx, miny, maxx, maxy = bbox
            if minx <= lon <= maxx and miny <= lat <= maxy and geofabrik_pois.vince(lon, lat, rid, bbox, sorelle):
                out.append(rid)
        return out

    def test_ogni_punto_in_una_sola_regione_in_qualunque_ordine(self):
        # Griglia di 0,25 gradi sui lati dei rettangoli compresi (lati comuni a 60 E e 120 E, angoli).
        coppie = set()
        for i in range(int((180 - 27) / 0.25) + 1):
            for j in range(int((82 - 41) / 0.25) + 1):
                lon, lat = 27 + i * 0.25, 41 + j * 0.25
                dentro = [r for r, (a, b, c, d) in self.RUSSIA.items() if a <= lon <= c and b <= lat <= d]
                coppie.update((x, y) for x in dentro for y in dentro if x < y)
                vincitrici = self.vincitrici(lon, lat)
                self.assertEqual(len(vincitrici), 1 if dentro else 0, (lon, lat, vincitrici))
                self.assertEqual(vincitrici, self.vincitrici(lon, lat, inverti=True), (lon, lat))
        # Le 17 coppie sovrapposte, piu' Siberia ed Estremo Oriente, che hanno in comune il lato a 120 E.
        self.assertEqual(len(coppie), 18)

    def test_vince_la_regione_con_il_centro_piu_vicino(self):
        # Nizhny Novgorod (44,0 E; 56,33 N) sta nei rettangoli di Centro, Volga e Volga-Vjatka; Celjabinsk (61,4 E;
        # 55,16 N) in quelli degli Urali europei e della Siberia, a 1,4 gradi dal lato est del primo.
        self.assertEqual(self.vincitrici(44.0, 56.33), ["russia-volga-vjatka"])
        self.assertEqual(self.vincitrici(61.4, 55.16), ["russia-urali-europei"])
        # Fuori dalle sovrapposizioni resta alla sua regione; senza sorelle vince sempre.
        self.assertEqual(self.vincitrici(50.0, 46.0), ["russia-volga"])
        self.assertTrue(geofabrik_pois.vince(30.0, 60.0, "x", (0, 0, 30, 60), []))

    def test_a_parita_l_id_piu_basso(self):
        a, b = (0, 0, 2, 2), (0, 0, 2, 2)
        self.assertTrue(geofabrik_pois.vince(1, 1, "a", a, [("b", b)]))
        self.assertFalse(geofabrik_pois.vince(1, 1, "b", b, [("a", a)]))

    def test_scrivi_xml_cede_i_punti_alla_sorella(self):
        features = [{"type": "Feature", "geometry": {"type": "Point", "coordinates": [x, 0.5]},
                     "properties": {"@type": "node", "@id": i, "amenity": "cafe"}} for i, x in enumerate((0.2, 1.5, 1.9))]
        with tempfile.TemporaryDirectory() as tmp:
            source = os.path.join(tmp, "a.geojsonseq")
            with open(source, "w", encoding="utf-8") as f:
                f.writelines(json.dumps(x) + "\n" for x in features)
            out = os.path.join(tmp, "out.osm.xml")
            with mock.patch("sys.stdout", new_callable=io.StringIO) as stdout:
                count = geofabrik_pois.scrivi_xml([source], (0, 0, 2, 1), out, sorelle=[("b", (1, 0, 3, 1))], regione="a")
            ids = [e.get("id") for e in ElementTree.parse(out).getroot()]
        # 1,5 e' a meta' tra i centri (1 e 2): parita', resta in "a"; 1,9 e' piu' vicino al centro di "b".
        self.assertEqual((count, ids), (2, ["0", "1"]))
        self.assertIn("1 oggetti ceduti alle regioni sorelle", stdout.getvalue())


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
        # Da Python 3.14 un HTTPError mai chiuso da' un ResourceWarning, che il controllo dei test conta come errore.
        self.addCleanup(error.close)
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
        self.addCleanup(error.close)
        with mock.patch.object(geofabrik_pois.urllib.request, "urlopen", side_effect=error) as urlopen:
            with self.assertRaises(urllib.error.HTTPError):
                geofabrik_pois.scarica("https://d.example/x.osm.pbf", self.dest, "ua")
        self.assertEqual(1, urlopen.call_count)

    def test_5xx_si_riprova(self):
        error = urllib.error.HTTPError("https://d.example/x", 503, "Unavailable", {}, None)
        self.addCleanup(error.close)
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
