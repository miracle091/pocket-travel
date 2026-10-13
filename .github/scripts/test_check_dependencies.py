#!/usr/bin/env python3
"""Test di check-dependencies.py: nessuna rete, le richieste HTTP sono simulate."""
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("check_dependencies", HERE / "check-dependencies.py")
cd = importlib.util.module_from_spec(spec)
sys.modules["check_dependencies"] = cd
spec.loader.exec_module(cd)

REPORT = """\
releaseRuntimeClasspath - Runtime classpath of compilation 'release'.
+--- project :core:data
+--- androidx.core:core-ktx:1.19.1
|    +--- androidx.annotation:annotation:1.9.1 (*)
|    \\--- androidx.collection:collection:1.4.2 -> 1.5.0 (c)
+--- androidx.compose:compose-bom:2026.09.00
|    \\--- androidx.compose.ui:ui -> 1.9.0
+--- org.lz4:lz4-java:1.8.0 -> at.yawk.lz4:lz4-java:1.12.0
+--- com.google.guava:guava:{strictly 33.0} -> 33.0
+--- com.example:only-constraint:{strictly 1.0}
+--- com.example:missing:1.0 FAILED
\\--- org.jetbrains.kotlin:kotlin-stdlib:2.4.21 (n)
"""


def metadata(*versions):
    items = "".join(f"<version>{v}</version>" for v in versions)
    return f"<metadata><versioning><versions>{items}</versions></versioning></metadata>".encode()


class FakeHttp:
    def __init__(self, routes):
        self.routes = routes
        self.calls = []

    def __call__(self, url, data=None):
        self.calls.append((url, data))
        r = self.routes.get(url)
        if r is None:
            raise cd.HttpError(404)
        if isinstance(r, Exception):
            raise r
        return r


class ParseReportTest(unittest.TestCase):
    def test_coordinate_risolte(self):
        self.assertEqual(cd.parse_report(REPORT), {
            ("androidx.core", "core-ktx", "1.19.1"),
            ("androidx.annotation", "annotation", "1.9.1"),
            ("androidx.collection", "collection", "1.5.0"),
            ("androidx.compose", "compose-bom", "2026.09.00"),
            ("androidx.compose.ui", "ui", "1.9.0"),
            ("at.yawk.lz4", "lz4-java", "1.12.0"),
            ("com.google.guava", "guava", "33.0"),
            ("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.21"),
        })

    def test_rilocazione_non_diventa_versione(self):
        coords = cd.parse_report("+--- org.lz4:lz4-java:1.8.0 -> at.yawk.lz4:lz4-java:1.12.0\n")
        self.assertEqual(coords, {("at.yawk.lz4", "lz4-java", "1.12.0")})

    def test_senza_versione_dichiarata(self):
        self.assertEqual(cd.parse_report("|    \\--- g:a -> 2.0 (*)\n"), {("g", "a", "2.0")})


class VersionTest(unittest.TestCase):
    def test_ordine(self):
        key = cd.version_key
        self.assertLess(key("1.5.0-beta01"), key("1.5.0"))
        self.assertLess(key("1.5.0-alpha02"), key("1.5.0-beta01"))
        self.assertLess(key("1.5.0-rc01"), key("1.5.0"))
        self.assertLess(key("1.9"), key("1.10"))
        self.assertEqual(key("1.0"), key("1.0.0"))

    def test_prerelease(self):
        for v in ("1.5.0-beta01", "2.0.0-alpha.6", "1.0-M1", "1.0.0-SNAPSHOT", "1.0-rc1", "1.0-dev-5",
                  "13.6.1-pre935d410353da9d701ad42c97f2e0c300c8d408b8"):
            self.assertTrue(cd.is_prerelease(v), v)
        for v in ("1.12", "20260814", "33.0-jre", "3.53.4.0", "1.0.Final"):
            self.assertFalse(cd.is_prerelease(v), v)

    def test_stabile_ignora_prerelease(self):
        self.assertEqual(cd.newer_version("1.0", ["1.0", "1.1", "1.2-beta01"]), "1.1")
        self.assertIsNone(cd.newer_version("1.1", ["1.0", "1.1", "1.2-beta01"]))

    def test_prerelease_scelta_di_proposito_segue_la_sua_linea(self):
        avail = ["1.4.0", "1.5.0-beta01", "1.5.0-beta02", "1.6.0-alpha01"]
        self.assertEqual(cd.newer_version("1.5.0-beta01", avail), "1.5.0-beta02")
        self.assertEqual(cd.newer_version("1.5.0-beta01", avail + ["1.5.0"]), "1.5.0")
        self.assertIsNone(cd.newer_version("1.5.0-beta02", avail))


class CatalogTest(unittest.TestCase):
    CATALOG = {
        "versions": {"agp": "9.4.1", "okhttp": "5.5.0", "unused": "1.0"},
        "libraries": {
            "agp": {"group": "com.android.tools.build", "name": "gradle", "version": {"ref": "agp"}},
            "okhttp": {"group": "com.squareup.okhttp3", "name": "okhttp", "version": {"ref": "okhttp"}},
            "ui": {"group": "androidx.compose.ui", "name": "ui"},
        },
        "plugins": {"app": {"id": "com.android.application", "version": {"ref": "agp"}}},
    }

    def test_voci(self):
        entries = cd.catalog_entries(self.CATALOG)
        self.assertEqual([e[0] for e in entries], ["agp", "okhttp"])
        self.assertIn(("com.android.application", "com.android.application.gradle.plugin"), entries[0][2])

    def test_notice_e_repository(self):
        http = FakeHttp({
            f"{cd.GOOGLE}/com/android/tools/build/gradle/maven-metadata.xml": metadata("9.4.1", "9.5.0", "9.6.0-alpha1"),
            f"{cd.CENTRAL}/com/squareup/okhttp3/okhttp/maven-metadata.xml": metadata("5.4.0", "5.5.0"),
        })
        out = []
        cd.check_versions(self.CATALOG, http, out.append)
        self.assertEqual(out, ["::notice::agp: 9.4.1 -> 9.5.0 disponibile (com.android.tools.build:gradle)"])

    def test_plugin_dal_portale(self):
        catalog = {
            "versions": {"detekt": "2.0.0-alpha.6"},
            "plugins": {"d": {"id": "dev.detekt", "version": {"ref": "detekt"}}},
        }
        http = FakeHttp({
            f"{cd.PLUGIN_PORTAL}/dev/detekt/dev.detekt.gradle.plugin/maven-metadata.xml":
                metadata("1.23.8", "2.0.0-alpha.6", "2.0.0-alpha.7", "2.1.0-alpha.1"),
        })
        out = []
        cd.check_versions(catalog, http, out.append)
        self.assertEqual(len(out), 1)
        self.assertIn("2.0.0-alpha.6 -> 2.0.0-alpha.7", out[0])

    def test_rete_giu_e_solo_warning(self):
        http = FakeHttp({
            f"{cd.GOOGLE}/com/android/tools/build/gradle/maven-metadata.xml": OSError("timeout"),
            f"{cd.CENTRAL}/com/squareup/okhttp3/okhttp/maven-metadata.xml": cd.HttpError(503),
        })
        out = []
        cd.check_versions(self.CATALOG, http, out.append)
        self.assertTrue(out)
        self.assertTrue(all(line.startswith("::warning::") for line in out), out)


class VulnerabilityTest(unittest.TestCase):
    COORD = ("at.yawk.lz4", "lz4-java", "1.8.0")

    def osv(self, vulnerable):
        batch = {"results": [{"vulns": [{"id": "GHSA-xxxx"}]} if vulnerable else {}]}
        detail = {
            "id": "GHSA-xxxx",
            "aliases": ["CVE-2025-0001", "GHSA-yyyy"],
            "affected": [
                {"package": {"name": "at.yawk.lz4:lz4-java"},
                 "ranges": [{"events": [{"introduced": "0"}, {"fixed": "1.10.1"}]}]},
                {"package": {"name": "altro:pacchetto"}, "ranges": [{"events": [{"fixed": "9.9"}]}]},
            ],
        }
        return FakeHttp({
            cd.OSV_BATCH_URL: json.dumps(batch).encode(),
            cd.OSV_VULN_URL + "GHSA-xxxx": json.dumps(detail).encode(),
        })

    def test_vulnerabile(self):
        http = self.osv(True)
        out = []
        n = cd.check_vulnerabilities({"app": {self.COORD}}, http, out.append)
        self.assertEqual(n, 1)
        self.assertEqual(len(out), 1)
        self.assertTrue(out[0].startswith("::error::"))
        for part in ("at.yawk.lz4:lz4-java:1.8.0", "GHSA-xxxx", "CVE-2025-0001", "1.10.1", "app"):
            self.assertIn(part, out[0])
        self.assertNotIn("9.9", out[0])
        body = json.loads(http.calls[0][1])
        self.assertEqual(body["queries"][0], {
            "package": {"ecosystem": "Maven", "name": "at.yawk.lz4:lz4-java"}, "version": "1.8.0"})

    def test_pulito(self):
        out = []
        self.assertEqual(cd.check_vulnerabilities({"app": {self.COORD}}, self.osv(False), out.append), 0)
        self.assertEqual(out, [])

    def test_batch_da_500(self):
        coords = {("g", f"a{i}", "1.0") for i in range(1201)}
        sizes = []

        def http(url, data=None):
            n = len(json.loads(data)["queries"])
            sizes.append(n)
            return json.dumps({"results": [{} for _ in range(n)]}).encode()

        cd.check_vulnerabilities({"app": coords}, http, lambda _: None)
        self.assertEqual(sizes, [500, 500, 201])

    def test_osv_giu_e_solo_warning(self):
        http = FakeHttp({cd.OSV_BATCH_URL: OSError("irraggiungibile")})
        out = []
        self.assertEqual(cd.check_vulnerabilities({"app": {self.COORD}}, http, out.append), 0)
        self.assertEqual(len(out), 1)
        self.assertTrue(out[0].startswith("::warning::"))

    def test_dettaglio_non_raggiungibile_resta_errore(self):
        http = self.osv(True)
        del http.routes[cd.OSV_VULN_URL + "GHSA-xxxx"]
        out = []
        self.assertEqual(cd.check_vulnerabilities({"app": {self.COORD}}, http, out.append), 1)
        self.assertIn("GHSA-xxxx", out[0])


class HostTest(unittest.TestCase):
    def test_host_non_fidato_rifiutato(self):
        with self.assertRaises(ValueError):
            cd.http_request("https://example.com/maven-metadata.xml")


class RunTest(unittest.TestCase):
    def test_exit_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "app.txt"
            report.write_text("+--- at.yawk.lz4:lz4-java:1.8.0\n", encoding="utf-8")
            catalog = Path(tmp) / "libs.versions.toml"
            catalog.write_text('[versions]\nx = "1.0"\n', encoding="utf-8")
            for vulnerable, expected in ((True, 1), (False, 0)):
                batch = {"results": [{"vulns": [{"id": "V-1"}]} if vulnerable else {}]}
                http = FakeHttp({cd.OSV_BATCH_URL: json.dumps(batch).encode()})
                self.assertEqual(cd.run([str(report)], str(catalog), http=http, out=lambda _: None), expected)


if __name__ == "__main__":
    unittest.main()
