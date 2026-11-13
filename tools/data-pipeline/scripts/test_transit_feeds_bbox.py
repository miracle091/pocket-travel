#!/usr/bin/env python3
"""Test di transit_feeds_bbox.py (unittest, senza rete): python3 test_transit_feeds_bbox.py"""

import datetime
import io
import os
import sys
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import transit_feeds_bbox as t  # noqa: E402

REGIONS = '''REGIONS=(
  "francia-bretagna|Francia - Bretagna|-5.14|47.29|-1.03|48.88|Brittany|fr|Francia|Bretagna|Europa"
  "francia-ile-de-france|Francia - Île-de-France|1.44|48.12|3.54|49.24|Île-de-France|fr|Francia|Île-de-France|Europa"
  "francia-grande|Francia - Grande|0.00|46.00|5.00|50.00|X|fr|Francia|Grande|Europa"
  "martinica|Martinica (Francia)|-61.23|14.39|-60.81|14.88|Martinique|mq|||Nord America"
  "italia|Italia|6.6|35.5|18.5|47.1|Italy|it|||Europa"
)
'''
TODAY = datetime.date(2026, 11, 13)


def feed_row(**kw):
    row = {"id": "tdg-1", "name": "Rete", "provider": "Ente", "urls.license": "odbl",
           "urls.latest": "https://example.org/latest.zip", "urls.direct_download": "",
           "data_type": "gtfs", "status": "active", "location.country_code": "FR"}
    row.update({f"location.bounding_box.{k}": "" for k in t.BBOX_KEYS})
    row.update(kw)
    return row


def bbox(s, n, w, e):
    return dict(zip((f"location.bounding_box.{k}" for k in t.BBOX_KEYS), map(str, (s, n, w, e))))


def make_zip(files):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for name, text in files.items():
            z.writestr(name, text)
    buf.seek(0)
    return buf


def opener_for(files):
    return lambda url, ua: zipfile.ZipFile(make_zip(files))


class RegionsTest(unittest.TestCase):
    def setUp(self):
        self.regions = t.load_regions(REGIONS, "FR")

    def test_country_and_overseas_regions(self):
        self.assertEqual([r[0] for r in self.regions],
                         ["francia-bretagna", "francia-ile-de-france", "francia-grande", "martinica"])

    def test_smallest_rectangle_wins(self):
        self.assertEqual(t.region_at(self.regions, 48.85, 2.35), "francia-ile-de-france")
        self.assertEqual(t.region_at(self.regions, 47.0, 4.0), "francia-grande")
        self.assertIsNone(t.region_at(self.regions, 41.9, 12.5))


class UnusableTest(unittest.TestCase):
    def setUp(self):
        self.regions = t.load_regions(REGIONS, "FR")

    def test_good_bbox_is_usable(self):
        self.assertFalse(t.unusable(feed_row(**bbox(48.8, 48.9, 2.3, 2.4)), self.regions))

    def test_missing_zero_inverted_or_outside(self):
        for b in ({}, bbox(0.0, 48.7, -3.9, 0.0), bbox(48.9, 48.8, 2.3, 2.4), bbox(41.8, 42.0, 12.4, 12.6)):
            with self.subTest(b=b):
                self.assertTrue(t.unusable(feed_row(**b), self.regions))


class DescribeTest(unittest.TestCase):
    def setUp(self):
        self.regions = t.load_regions(REGIONS, "FR")

    def test_region_from_stops_and_days_from_feed_info(self):
        # Fermate scambiate (-1.70, 48.12), a zero o vuote non spostano il centro: restano fuori dalle regioni.
        files = {"gtfs/stops.txt": "﻿stop_id,stop_lat,stop_lon\n1,48.10,-1.70\n2,48.12,-1.66\n3,0,0\n4,,\n"
                                   "5,-1.70,48.12\n",
                 "gtfs/feed_info.txt": "feed_publisher_name,feed_end_date\nX,20261213\n"}
        row = t.describe(feed_row(), self.regions, "ua", TODAY, opener_for(files))
        self.assertEqual(row, ["tdg-1", "Rete", "francia-bretagna", "odbl", "30", "2/4", ""])

    def test_days_from_calendar_and_added_dates(self):
        files = {"stops.txt": "stop_id,stop_lat,stop_lon\n1,14.6,-61.0\n",
                 "calendar.txt": "service_id,start_date,end_date\nA,20261101,20261120\n",
                 "calendar_dates.txt": "service_id,date,exception_type\nA,20261231,2\nA,20261125,1\n"}
        row = t.describe(feed_row(), self.regions, "ua", TODAY, opener_for(files))
        self.assertEqual(row[2:5], ["martinica", "odbl", "12"])

    def test_region_with_most_stops_not_centre(self):
        # Due fermate in Bretagna e una in Martinica: il centro cadrebbe nell'Atlantico, vince la Bretagna.
        files = {"stops.txt": "stop_lat,stop_lon\n48.1,-1.7\n48.2,-1.6\n14.6,-61.0\n"}
        row = t.describe(feed_row(), self.regions, "ua", TODAY, opener_for(files))
        self.assertEqual(row[2], "francia-bretagna")
        self.assertEqual(row[5], "2/3")

    def test_tie_goes_to_smallest_rectangle(self):
        # Parigi sta sia nell'Ile-de-France sia nel rettangolo piu' grande: stesse fermate, vince il piu' piccolo.
        # La scelta resta incerta: la nota nomina l'altra regione.
        files = {"stops.txt": "stop_lat,stop_lon\n48.85,2.35\n"}
        row = t.describe(feed_row(), self.regions, "ua", TODAY, opener_for(files))
        self.assertEqual(row[2], "francia-ile-de-france")
        self.assertEqual(row[6], "da controllare, rettangoli sovrapposti: francia-grande (1 fermate)")

    def test_no_note_when_one_region_clearly_wins(self):
        # Tre fermate in Bretagna e una a Parigi: l'Ile-de-France e il rettangolo grande ne hanno 1, meno della meta'.
        files = {"stops.txt": "stop_lat,stop_lon\n48.1,-1.7\n48.2,-1.6\n48.3,-1.5\n48.85,2.35\n"}
        row = t.describe(feed_row(), self.regions, "ua", TODAY, opener_for(files))
        self.assertEqual(row[2:7:4], ["francia-bretagna", ""])

    def test_direct_download_when_latest_missing(self):
        seen = []

        def opener(url, ua):
            seen.append(url)
            return zipfile.ZipFile(make_zip({"stops.txt": "stop_lat,stop_lon\n48.85,2.35\n"}))

        t.describe(feed_row(**{"urls.latest": "", "urls.direct_download": "https://d/x"}), self.regions, "ua", TODAY,
                   opener)
        self.assertEqual(seen, ["https://d/x"])

    def test_reasons_when_region_missing(self):
        no_stops = opener_for({"stops.txt": "stop_id,stop_lat,stop_lon\n1,41.9,12.5\n"})

        def broken(url, ua):
            raise zipfile.BadZipFile("not a zip")

        for opener, reason in [(no_stops, "nessuna fermata dentro"), (broken, "feed non letto")]:
            with self.subTest(reason=reason):
                row = t.describe(feed_row(), self.regions, "ua", TODAY, opener)
                self.assertEqual(row[2], "")
                self.assertIn(reason, row[6])
        row = t.describe(feed_row(**{"urls.latest": ""}), self.regions, "ua", TODAY, no_stops)
        self.assertEqual(row[6], "nessun url nel catalogo")


class RangeFileTest(unittest.TestCase):
    def test_reads_zip_member_through_ranges(self):
        data = make_zip({"stops.txt": "stop_lat,stop_lon\n48.85,2.35\n"}).getvalue()
        requests = []

        class Resp(io.BytesIO):
            pass

        def fake_urlopen(req, timeout):
            start, end = map(int, req.headers["Range"].removeprefix("bytes=").split("-"))
            requests.append((start, end))
            return Resp(data[start:end + 1])

        original = t.urllib.request.urlopen
        t.urllib.request.urlopen = fake_urlopen
        try:
            with zipfile.ZipFile(t.RangeFile("https://x", len(data), {})) as zf:
                regions = t.load_regions(REGIONS, "FR")
                self.assertEqual(t.stops_region(t.read_csv(zf, "stops.txt"), regions),
                                 ("francia-ile-de-france", 1, 1, [("francia-grande", 1)]))
        finally:
            t.urllib.request.urlopen = original
        self.assertTrue(requests)


if __name__ == "__main__":
    unittest.main()
