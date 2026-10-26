#!/usr/bin/env python3
"""Test di licenses_page.py (unittest, senza rete): python3 test_licenses_page.py"""

import datetime
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import licenses_page  # noqa: E402

DAY = datetime.date(2026, 10, 9)
REGIONS = {"italia": "it", "francia-corsica": "fr", "francia-bretagna": "fr"}
FEEDS = [
    {"name": "Venezia <ACTV>", "regions": ["italia"], "license": "CC-BY", "licenseUrl": "https://example.org/l",
     "attribution": "Comune di Venezia & ACTV"},
    {"name": "Milano (ATM)", "regions": ["italia"], "license": "CC-BY", "attribution": "ATM"},
    {"name": "SNCF", "regions": ["francia"], "license": "ODbL-1.0", "attribution": "SNCF"},
]
ATTRIBUTIONS = [
    {"source": "OpenStreetMap", "license": "ODbL-1.0", "url": "https://www.openstreetmap.org/copyright"},
    {"source": "(c) Austrian Address Register (BEV); CC BY 4.0; modificato", "license": "CC-BY-4.0"},
    {"source": "UrbIS, Bruxelles, via BOSA; CC BY 4.0; modificato", "license": "CC-BY-4.0"},
]


class CountryCodeTest(unittest.TestCase):
    def test_region_in_manifest(self):
        self.assertEqual(licenses_page.country_code("italia", REGIONS), "it")

    def test_country_split_in_regions(self):
        self.assertEqual(licenses_page.country_code("francia", REGIONS), "fr")

    def test_unknown_region(self):
        self.assertEqual(licenses_page.country_code("atlantide", REGIONS), "")


class ShortSourceTest(unittest.TestCase):
    def test_drops_copyright_and_notes(self):
        self.assertEqual(licenses_page.short_source("(c) Austrian Address Register (BEV); CC BY 4.0"),
                         "Austrian Address Register (BEV)")

    def test_plain_name(self):
        self.assertEqual(licenses_page.short_source("OpenStreetMap"), "OpenStreetMap")


class LicenseUrlTest(unittest.TestCase):
    def test_spdx_identifier(self):
        self.assertEqual(licenses_page.license_url("CC-BY-4.0", None), "https://spdx.org/licenses/CC-BY-4.0.html")

    def test_custom_license_uses_source_page(self):
        url = "https://docs.overturemaps.org/attribution/"
        self.assertEqual(licenses_page.license_url("LicenseRef-Proprietary", url), url)
        self.assertEqual(licenses_page.license_url("varie", url), url)


class BuildPageTest(unittest.TestCase):
    def test_sources_grouped_by_license(self):
        page = licenses_page.build_page("it", ATTRIBUTIONS, FEEDS, REGIONS, DAY)
        self.assertIn("<details><summary><strong>CC-BY-4.0</strong> (2)</summary>"
                      '<p><a href="https://spdx.org/licenses/CC-BY-4.0.html">Testo della licenza</a> · '
                      "Austrian Address Register (BEV), UrbIS, Bruxelles, via BOSA</p></details>", page)
        # La licenza con piu' fonti viene prima.
        self.assertLess(page.index("<strong>CC-BY-4.0</strong>"), page.index("<strong>ODbL-1.0</strong>"))

    def test_networks_grouped_by_country(self):
        page = licenses_page.build_page("en", ATTRIBUTIONS, FEEDS, REGIONS, DAY)
        self.assertIn("<strong>IT</strong> (2)", page)
        self.assertIn('Milano (ATM) (CC-BY), Venezia &lt;ACTV&gt; (<a href="https://example.org/l">CC-BY</a>)', page)
        self.assertLess(page.index("SNCF"), page.index("Milano"))
        self.assertIn("assets/flags/fr.svg", page)

    def test_dates_per_language(self):
        self.assertIn("9 ott 2026", licenses_page.build_page("it", [], [], {}, DAY))
        self.assertIn("Oct 9, 2026", licenses_page.build_page("en", [], [], {}, DAY))

    def test_empty_sections(self):
        page = licenses_page.build_page("it", [], [], {}, DAY)
        self.assertEqual(page.count("Nessun dato pubblicato"), 2)


class DataSourcesTest(unittest.TestCase):
    def write_defs(self, root, libraries):
        for folder in ("libraries", "licenses"):
            os.makedirs(os.path.join(root, folder))
        with open(os.path.join(root, "licenses", "public-domain-x.json"), "w", encoding="utf-8") as f:
            json.dump({"name": "Public domain (X)", "url": "https://x.example/terms"}, f)
        for i, library in enumerate(libraries):
            with open(os.path.join(root, "libraries", f"{i}.json"), "w", encoding="utf-8") as f:
                json.dump(library, f)

    def test_every_data_definition_of_the_app_has_a_row(self):
        # Le definizioni vere della schermata Licenze: una fonte nuova senza uso in DATA_USE fa fallire qui.
        names = [name for name, _, _ in licenses_page.data_sources()]
        self.assertEqual(names, list(licenses_page.DATA_USE))
        page = licenses_page.build_page("it", [], [], {}, DAY)
        self.assertIn('<a href="https://www.weather.gov">NOAA National Weather Service</a></td>'
                      '<td><a href="https://www.weather.gov/disclaimer">Pubblico dominio</a>', page)

    def test_definition_without_use_is_an_error(self):
        with tempfile.TemporaryDirectory() as root:
            self.write_defs(root, [{"name": "Fonte nuova", "licenses": ["public-domain-x"], "tag": "data"}])
            with self.assertRaisesRegex(ValueError, "Fonte nuova"):
                licenses_page.data_sources(root)

    def test_software_definitions_are_not_data(self):
        with tempfile.TemporaryDirectory() as root:
            data = [{"name": n, "licenses": ["public-domain-x"], "tag": "data"} for n in licenses_page.DATA_USE]
            self.write_defs(root, data + [{"name": "BRouter", "licenses": ["public-domain-x"]}])
            sources = licenses_page.data_sources(root)
            self.assertNotIn("BRouter", [name for name, _, _ in sources])
            self.assertEqual(licenses_page.license_cell(sources[0][2], "en"),
                             '<a href="https://x.example/terms">Public domain</a>')


class MainTest(unittest.TestCase):
    def test_writes_both_pages_with_missing_index(self):
        with tempfile.TemporaryDirectory() as site:
            with open(os.path.join(site, "transit.json"), "w", encoding="utf-8") as f:
                json.dump({"feeds": FEEDS}, f)
            self.assertEqual(licenses_page.main(["licenses_page.py", site]), 0)
            for name in ("licenses.html", "licenses-en.html"):
                with open(os.path.join(site, name), encoding="utf-8") as f:
                    self.assertIn("SNCF", f.read())


if __name__ == "__main__":
    unittest.main()
