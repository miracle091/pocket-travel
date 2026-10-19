#!/usr/bin/env python3
"""Test di bbox_audit.py (unittest, senza rete)."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bbox_audit  # noqa: E402


def anello(x0, y0, x1, y1):
    return [[x0, y0], [x1, y0], [x1, y1], [x0, y1], [x0, y0]]


class EccedenzeTest(unittest.TestCase):
    def test_isola_fuori_dal_rettangolo(self):
        # Terraferma 10-12 E, 40-42 N nel rettangolo; un'isola di 0,5 x 0,5 gradi a ovest, un'altra a sud.
        geometry = {"type": "MultiPolygon", "coordinates": [
            [anello(10, 40, 12, 42)], [anello(8, 41, 8.5, 41.5)], [anello(11, 38, 11.5, 38.5)]]}
        out = {e["lato"]: e for e in bbox_audit.eccedenze(geometry, {"r": (9.9, 39.9, 12.1, 42.1)})}
        self.assertEqual(sorted(out), ["ovest", "sud"])
        ovest = out["ovest"]
        self.assertEqual((ovest["regione"], ovest["rettangolo"]), ("r", (8, 41, 8.5, 41.5)))
        # 0,5 x 0,5 gradi a 41 N: circa 55,7 x 41,8 km.
        self.assertAlmostEqual(ovest["km2"], 0.5 * 111.32 * 0.5 * 111.32 * 0.7547, delta=40)
        # Il punto piu' lontano e' a 1,9 gradi di longitudine dal lato ovest (9,9).
        self.assertAlmostEqual(ovest["km"], 1.9 * 111.32 * 0.7547, delta=2)
        self.assertFalse(ovest["antimeridiano"])
        self.assertEqual(out["sud"]["rettangolo"], (11, 38, 11.5, 38.5))

    def test_fascia_stretta_e_buco_ignorati(self):
        # Acque territoriali: il confine esce di 0,1 gradi (11 km) dal lato nord; il buco fuori dal rettangolo non conta.
        geometry = {"type": "Polygon", "coordinates": [anello(10, 40, 12, 42.1), anello(10.2, 40.2, 10.4, 40.4)]}
        self.assertEqual(bbox_audit.eccedenze(geometry, {"r": (10, 40, 12, 42)}), [])
        con_buco = {"type": "Polygon", "coordinates": [anello(10, 40, 13, 42), anello(12.2, 40.5, 12.8, 41.5)]}
        (est,) = bbox_audit.eccedenze(con_buco, {"r": (10, 40, 12, 42)})
        pieno = bbox_audit.eccedenze({"type": "Polygon", "coordinates": [anello(10, 40, 13, 42)]}, {"r": (10, 40, 12, 42)})
        self.assertAlmostEqual(est["km2"], pieno[0]["km2"] * (1 - 0.6 * 1 / 2), delta=50)

    def test_regioni_sullo_stesso_confine(self):
        # Due rettangoli sovrapposti coprono il confine (come le regioni russe sul confine della Russia): conta solo la
        # terra fuori da tutti e due, assegnata al rettangolo piu' vicino.
        geometry = {"type": "MultiPolygon", "coordinates": [[anello(10, 40, 20, 42)], [anello(21, 40, 21.5, 40.5)]]}
        rettangoli = {"a": (10, 40, 16, 42), "b": (14, 40, 20, 42)}
        (fuori,) = bbox_audit.eccedenze(geometry, rettangoli)
        self.assertEqual((fuori["regione"], fuori["lato"], fuori["rettangolo"]), ("b", "est", (21, 40, 21.5, 40.5)))
        # L'isola sta nel rettangolo di un'altra regione (un territorio d'oltremare con una regione propria): coperta.
        self.assertEqual(bbox_audit.eccedenze(geometry, rettangoli, coperto=lambda p: 20.9 <= p[0] <= 21.6), [])

    def test_oltre_l_antimeridiano(self):
        # Relazione divisa in due meta' a +-180 (come la da' Nominatim): la parte a -180/-175 non sta nel rettangolo
        # 160-180, ed e' assegnata a lui anche se un rettangolo a 0-10 E e' piu' vicino senza contare il giro.
        geometry = {"type": "MultiPolygon", "coordinates": [[anello(165, 60, 180, 66)], [anello(-180, 64, -175, 66)],
                                                            [anello(2, 60, 8, 62)]]}
        (fuori,) = bbox_audit.eccedenze(geometry, {"est": (160, 55, 180, 70), "ovest": (0, 55, 10, 70)})
        self.assertEqual((fuori["regione"], fuori["lato"], fuori["antimeridiano"]), ("est", "ovest", True))
        self.assertEqual(fuori["rettangolo"], (-180, 64, -175, 66))
        # Isole oltre +-180 senza toccarlo (le Chatham per la Nuova Zelanda) in una riga a parte da quelle sullo stesso lato.
        nz = {"type": "MultiPolygon", "coordinates": [[anello(170, -45, 175, -40)], [anello(-176.6, -44.2, -176.2, -43.7)],
                                                      [anello(160, -44, 160.5, -43.5)]]}
        righe = sorted((e["lato"], e["antimeridiano"], e["rettangolo"][0]) for e in bbox_audit.eccedenze(nz, {"nz": (166.5, -47, 178.5, -34)}))
        self.assertEqual(righe, [("ovest", False, 160), ("ovest", True, -176.6)])
        # Un'isola a ovest senza toccare -180 (Adak per l'Alaska) si raggiunge allargando il rettangolo.
        (adak,) = bbox_audit.eccedenze({"type": "MultiPolygon", "coordinates": [
            [anello(-160, 60, -140, 65)], [anello(-177, 51.6, -176, 52)]]}, {"alaska": (-170, 51, -130, 72)})
        self.assertFalse(adak["antimeridiano"])


if __name__ == "__main__":
    unittest.main()
