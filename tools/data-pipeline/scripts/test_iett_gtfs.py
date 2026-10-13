#!/usr/bin/env python3
"""Test di iett_gtfs.py (unittest, senza rete): python3 test_iett_gtfs.py"""

import csv
import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import iett_gtfs  # noqa: E402


class FixMojibakeTest(unittest.TestCase):
    def test_double_encoded_turkish(self):
        self.assertEqual(iett_gtfs.fix_mojibake("KADIKÃ–Y - KÄ°RAZLITEPE"), "KADIKÖY - KİRAZLITEPE")

    def test_latin1_control_bytes(self):
        # "Ş" = C5 9E: il 0x9E e' rimasto come carattere latin-1, non come "ž" di cp1252.
        self.assertEqual(iett_gtfs.fix_mojibake("ATAÅ\x9eEHÄ°R"), "ATAŞEHİR")

    def test_clean_text_unchanged(self):
        self.assertEqual(iett_gtfs.fix_mojibake("KİRAZLITEPE"), "KİRAZLITEPE")


class ParseCoordTest(unittest.TestCase):
    def test_thousands_separators(self):
        self.assertAlmostEqual(iett_gtfs.parse_coord("410.191.700.005.564", iett_gtfs.LAT_RANGE), 41.01917, places=5)
        self.assertAlmostEqual(iett_gtfs.parse_coord("286.843.529.999.755", iett_gtfs.LON_RANGE), 28.68435, places=5)

    def test_plain_decimal_with_trailing_garbage(self):
        self.assertAlmostEqual(iett_gtfs.parse_coord("41.00506785536913:17 17.03.20268", iett_gtfs.LAT_RANGE), 41.005068, places=5)

    def test_single_group_out_of_range_read_as_thousands(self):
        self.assertAlmostEqual(iett_gtfs.parse_coord("410.191", iett_gtfs.LAT_RANGE), 41.0191, places=4)

    def test_scientific_and_text_rejected(self):
        self.assertIsNone(iett_gtfs.parse_coord("4,12E+14", iett_gtfs.LAT_RANGE))
        self.assertIsNone(iett_gtfs.parse_coord("direction: GİDİŞ", iett_gtfs.LAT_RANGE))


class ReadRowsTest(unittest.TestCase):
    def test_semicolon_and_bom(self):
        rows = list(iett_gtfs.read_rows("﻿route_id;route_type\r\n1;3\r\n2;\r\n"))
        self.assertEqual(rows, [{"route_id": "1", "route_type": "3"}, {"route_id": "2", "route_type": ""}])

    def test_comma(self):
        self.assertEqual(list(iett_gtfs.read_rows("a,b\n1,2\n")), [{"a": "1", "b": "2"}])


class StopTimesTest(unittest.TestCase):
    def convert(self, text, coords):
        out = io.StringIO()
        written, estimated = iett_gtfs.convert_stop_times(io.StringIO(text), coords, csv.writer(out))
        return [row for row in csv.reader(io.StringIO(out.getvalue()))], written, estimated

    def test_interpolates_by_distance_and_sorts_sequence(self):
        coords = {"A": (41.0, 29.0), "B": (41.01, 29.0), "C": (41.03, 29.0), "D": (41.5, 29.0)}
        text = (
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time,timepoint\n"
            "t1,C,3,06:30:00,06:30:00,1\n"
            "t1,A,1,06:00:00,06:00:00,1\n"
            "t1,B,2,,,0\n"
            "t1,D,4,,,0\n"
        )
        rows, written, estimated = self.convert(text, coords)
        # B e' a un terzo della distanza fra A e C: 06:10; D dopo l'ultimo orario noto si scarta.
        self.assertEqual([r[1] for r in rows], ["A", "B", "C"])
        self.assertEqual(rows[1][3], "06:10:00")
        self.assertEqual((written, estimated), (3, 1))

    def test_drops_stops_without_coordinates_and_keeps_times_after_midnight(self):
        coords = {"A": (41.0, 29.0), "C": (41.02, 29.0)}
        text = (
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time,timepoint\n"
            "t1,A,1,25:00:00,25:00:00,1\n"
            "t1,X,2,,,0\n"
            "t1,C,3,25:20:00,25:20:00,1\n"
            "t2,A,1,07:00:00,07:00:00,1\n"
        )
        rows, written, _ = self.convert(text, coords)
        self.assertEqual([(r[0], r[1], r[3]) for r in rows], [("t1", "A", "25:00:00"), ("t1", "C", "25:20:00"), ("t2", "A", "07:00:00")])
        self.assertEqual(written, 3)


class ResourceUrlsTest(unittest.TestCase):
    def test_picks_csv_files_and_stop_times_zip(self):
        resources = [{"name": n, "format": "CSV", "url": f"https://x/{n}.csv"} for n in iett_gtfs.CSV_FILES + ["stop_times"]]
        resources.append({"name": "stop_times", "format": "ZIP", "url": "https://x/stop_times.zip"})
        urls = iett_gtfs.resource_urls(json.dumps({"result": {"resources": resources}}))
        self.assertEqual(urls["stop_times"], "https://x/stop_times.zip")
        self.assertEqual(urls["stops"], "https://x/stops.csv")

    def test_missing_resource(self):
        with self.assertRaises(ValueError):
            iett_gtfs.resource_urls(json.dumps({"result": {"resources": []}}))


class TimeHelpersTest(unittest.TestCase):
    def test_secondi_e_formato_oltre_la_mezzanotte(self):
        self.assertEqual(25 * 3600 + 20 * 60, iett_gtfs.gtfs_seconds("25:20:00"))
        self.assertIsNone(iett_gtfs.gtfs_seconds(""))
        self.assertEqual("25:20:05", iett_gtfs.gtfs_time(25 * 3600 + 20 * 60 + 5))
        self.assertEqual("00:00:00", iett_gtfs.gtfs_time(0))

    def test_distanza_in_metri_circa(self):
        # un centesimo di grado di latitudine sono circa 1,1 km
        self.assertAlmostEqual(1112, iett_gtfs.distance_m((41.0, 29.0), (41.01, 29.0)), delta=2)
        self.assertEqual(0, iett_gtfs.distance_m((41.0, 29.0), (41.0, 29.0)))

    def test_fermate_coincidenti_ripartiscono_in_parti_uguali(self):
        coords = {"A": (41.0, 29.0), "B": (41.0, 29.0), "C": (41.0, 29.0)}
        calls = [(1, "A", 0), (2, "B", None), (3, "C", 600)]
        self.assertEqual([0, 300, 600], iett_gtfs.interpolate_trip(calls, coords))

    def test_orari_prima_del_primo_noto_restano_vuoti(self):
        coords = {"A": (41.0, 29.0), "B": (41.01, 29.0), "C": (41.02, 29.0)}
        self.assertEqual([None, 100, 200], iett_gtfs.interpolate_trip([(1, "A", None), (2, "B", 100), (3, "C", 200)], coords))

    def test_coordinate_limite_e_formati_strani(self):
        self.assertEqual(41.0, iett_gtfs.parse_coord("41.0", iett_gtfs.LAT_RANGE))
        self.assertIsNone(iett_gtfs.parse_coord("", iett_gtfs.LAT_RANGE))
        self.assertIsNone(iett_gtfs.parse_coord("39.9", iett_gtfs.LAT_RANGE))
        self.assertIsNone(iett_gtfs.parse_coord("...", iett_gtfs.LAT_RANGE))
        self.assertEqual(29.0, iett_gtfs.parse_coord(" 29 ", iett_gtfs.LON_RANGE))


STOPS = ("stop_id;stop_code;stop_name;stop_lat;stop_lon\n"
         "1;S1;KADIKÃ–Y Ä°skele;410.191.700.005.564;290.231.200.000.000\n"
         "2;S2;Esponenziale;4,12E+14;2,9E+14\n"
         "3;S3;Piazza Taksim;41.0370;28.9850\n")
AGENCY = "agency_id;agency_name;agency_url;agency_timezone\n1;IETT;https://iett.example;Europe/Istanbul\n"
CALENDAR = ("service_id;monday;tuesday;wednesday;thursday;friday;saturday;sunday;start_date;end_date\n"
            "WD;1;1;1;1;1;0;0;20260101;20261231\n")
ROUTES = ("route_id;agency_id;route_short_name;route_long_name;route_type\n"
          "R1;1;15;KADIKÃ–Y - TAKSÄ°M;\n"
          "R2;1;16;Tram;0\n")
TRIPS = "trip_id;route_id;service_id;trip_headsign;direction_id\nT1;R1;WD;KADIKÃ–Y;0\n"
STOP_TIMES = ("trip_id,stop_id,stop_sequence,arrival_time,departure_time,timepoint\n"
              "T1,1,1,08:00:00,08:00:00,1\n"
              "T1,2,2,,,0\n"          # fermata scartata: coordinate esponenziali
              "T1,3,3,08:10:00,08:10:00,1\n")


def make_fetch(resources=None, texts=None):
    names = iett_gtfs.CSV_FILES + ["stop_times"]
    resources = resources if resources is not None else (
        [{"name": n, "format": "CSV", "url": f"https://x/{n}.csv"} for n in iett_gtfs.CSV_FILES]
        + [{"name": "stop_times", "format": "ZIP", "url": "https://x/stop_times.zip"}])
    texts = texts or {"agency": AGENCY, "calendar": CALENDAR, "routes": ROUTES, "stops": STOPS, "trips": TRIPS}
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("stop_times.txt", "﻿" + STOP_TIMES)
    payload = {iett_gtfs.PACKAGE_URL: json.dumps({"result": {"resources": resources}}).encode("utf-8"),
               "https://x/stop_times.zip": buf.getvalue()}
    payload.update({f"https://x/{n}.csv": t.encode("utf-8") for n, t in texts.items()})
    assert names

    def fetch(url, user_agent):
        return payload[url]
    return fetch


class MainTest(unittest.TestCase):
    def run_main(self, out, **kwargs):
        with mock.patch.object(iett_gtfs, "fetch", make_fetch(**kwargs)), \
                mock.patch.object(sys, "argv", ["iett_gtfs.py", "--out", out]), \
                mock.patch.object(sys, "stdout", io.StringIO()) as stdout, mock.patch.object(sys, "stderr", io.StringIO()):
            return iett_gtfs.main(), stdout.getvalue()

    def read(self, archive, name):
        return list(csv.reader(io.StringIO(archive.read(name).decode("utf-8"))))

    def test_feed_composto_corretto(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "feed.zip")
            code, text = self.run_main(out)
            with zipfile.ZipFile(out) as z:
                self.assertEqual(["agency.txt", "calendar.txt", "routes.txt", "stops.txt", "trips.txt", "stop_times.txt"], z.namelist())
                stops = self.read(z, "stops.txt")
                routes = self.read(z, "routes.txt")
                trips = self.read(z, "trips.txt")
                times = self.read(z, "stop_times.txt")
        self.assertEqual(0, code)
        self.assertEqual("iett_gtfs: 2 fermate, 2 passaggi (0 con orario stimato)\n", text)
        self.assertEqual([["1", "S1", "KADIKÖY İskele", "41.0191700005564", "29.02312"],
                          ["3", "S3", "Piazza Taksim", "41.037", "28.985"]], stops[1:])
        self.assertEqual(["R1", "1", "15", "KADIKÖY - TAKSİM", "3"], routes[1])  # route_type vuoto -> autobus
        self.assertEqual("0", routes[2][4])                                      # route_type 0 (tram) resta
        self.assertEqual("KADIKÖY", trips[1][3])
        self.assertEqual([["T1", "1", "1", "08:00:00", "08:00:00"], ["T1", "3", "3", "08:10:00", "08:10:00"]], times[1:])

    def test_stesso_input_stesso_zip(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = os.path.join(tmp, "a.zip"), os.path.join(tmp, "b.zip")
            self.run_main(a)
            self.run_main(b)
            with open(a, "rb") as fa, open(b, "rb") as fb:
                self.assertEqual(fa.read(), fb.read())

    def test_risorsa_mancante_esce_con_1_senza_scrivere(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "feed.zip")
            code, _ = self.run_main(out, resources=[])
            self.assertEqual(1, code)
            self.assertFalse(os.path.exists(out))

    def test_errore_di_rete_esce_con_1(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(iett_gtfs, "fetch", side_effect=OSError("giu")), \
                mock.patch.object(sys, "argv", ["iett_gtfs.py", "--out", os.path.join(tmp, "f.zip")]), \
                mock.patch.object(sys, "stderr", io.StringIO()):
            self.assertEqual(1, iett_gtfs.main())


if __name__ == "__main__":
    unittest.main()
