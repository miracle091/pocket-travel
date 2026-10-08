#!/usr/bin/env python3
"""Test di iett_gtfs.py (unittest, senza rete): python3 test_iett_gtfs.py"""

import csv
import io
import json
import os
import sys
import unittest

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


if __name__ == "__main__":
    unittest.main()
