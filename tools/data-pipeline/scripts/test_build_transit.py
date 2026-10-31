#!/usr/bin/env python3
"""Test di build-transit.sh senza Gradle: scelta del feed e dei parametri, download con un server HTTP locale, voce
pubblicata tenuta se il feed non e' cambiato, rete scaduta (uscita 3). La conversione (generateTransit) non e' coperta
qui: e' un test Kotlin. Richiede bash, jq, unzip.

Uso: python test_build_transit.py
"""
import hashlib
import io
import json
import shutil
import tempfile
import unittest
import zipfile
from datetime import date, timedelta
from pathlib import Path

import shell_fixtures as fx

SCRIPT = "tools/data-pipeline/scripts/build-transit.sh"
HEADER = "# id\tregioni\tnome\tlicenza\tattribuzione\turl_licenza\turl_feed\tcerca_ultimo\turl_rt\n"
FAILING_GRADLE = "#!/usr/bin/env bash\nexit 7\n"


def feed_zip(content=b"stop_id\n1\n"):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        z.writestr("stops.txt", content)
    return buffer.getvalue()


def feeds_row(feed_id="rete-a", url="http://127.0.0.1:9/feed.zip", realtime=""):
    return f"{feed_id}\tit,fr\tRete A\tCC-BY-4.0\tAttribuzione A\thttps://licenza\t{url}\t\t{realtime}\n"


def published_entry(zip_bytes, days_old=1, valid_until="2999-01-01", **extra):
    version = (date.today() - timedelta(days=days_old)).strftime("%Y.%m.%d.1.1")
    return {"id": "rete-a", "name": "Vecchio nome", "regions": ["it"], "license": "x", "attribution": "x", "licenseUrl": "x",
            "version": version, "sourceSha256": hashlib.sha256(zip_bytes).hexdigest(), "validUntil": valid_until, **extra}


@unittest.skipUnless(fx.BASH and shutil.which("jq") and shutil.which("unzip"), "servono bash, jq e unzip")
class BuildTransitTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.tree = Path(self.dir.name)
        for f in (SCRIPT, "tools/data-pipeline/scripts/lib.sh", "tools/data-pipeline/scripts/portable.sh"):
            fx.copy(f, self.tree)
        self.out = self.tree / "out"
        self.prefetch = self.tree / "prefetch"
        self.prefetch.mkdir()

    def run_script(self, args, rows=None, **env):
        feeds = fx.write(self.tree / "feeds.tsv", HEADER + (rows if rows is not None else feeds_row()))
        environment = fx.environment(self.tree, TRANSIT_FEEDS=feeds.as_posix(), **env)
        return fx.run_bash([self.tree / SCRIPT, *args], self.tree, environment)

    def build(self, zip_bytes, index=None, **env):
        """Rete con il feed gia' scaricato (TRANSIT_PREFETCH_DIR) e l'indice pubblicato [index]."""
        (self.prefetch / "rete-a.zip").write_bytes(zip_bytes)
        published = []
        if index is not None:
            published = [fx.write(self.tree / "index.json", json.dumps(index))]
        return self.run_script(["rete-a", "2026.10.30.1.1", "https://h/rel", self.out, *published],
                               TRANSIT_PREFETCH_DIR=self.prefetch.as_posix(), **env)

    def lines(self, name):
        path = self.out / name
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()] if path.exists() else []

    def test_parametri_non_validi(self):
        self.assertEqual(1, self.run_script([]).returncode)
        proc = self.run_script(["Rete_Sbagliata", "v", "https://h", self.out])
        self.assertEqual(1, proc.returncode)
        self.assertIn("feedId non valido", proc.stderr)
        self.assertEqual(1, self.run_script(["--download-only", "rete-a"]).returncode)

    def test_rete_fuori_dalla_lista(self):
        proc = self.run_script(["rete-b", "v", "https://h", self.out])
        self.assertEqual(1, proc.returncode)
        self.assertIn("non e' nella lista", proc.stderr)

    def test_url_tempo_reale_non_https(self):
        proc = self.run_script(["rete-a", "v", "https://h", self.out], rows=feeds_row(realtime="http://rt/feed.pb"))
        self.assertEqual(1, proc.returncode)
        self.assertIn("url_rt non https", proc.stderr)

    def test_download_only_scrive_lo_zip(self):
        data = feed_zip()
        with fx.serve({"/feed.zip": data}) as server:
            proc = self.run_script(["--download-only", "rete-a", self.prefetch], rows=feeds_row(url=f"{server.base}/feed.zip"))
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(data, (self.prefetch / "rete-a.zip").read_bytes())
        self.assertEqual([], list(self.prefetch.glob("*.part")))

    def test_download_only_con_un_file_che_non_e_uno_zip_non_scrive_nulla(self):
        with fx.serve({"/feed.zip": b'{"errors":["Invalid region name"]}'}) as server:
            proc = self.run_script(["--download-only", "rete-a", self.prefetch], rows=feeds_row(url=f"{server.base}/feed.zip"))
        self.assertEqual(1, proc.returncode)
        self.assertIn("non e' uno zip valido", proc.stderr)
        self.assertEqual([], list(self.prefetch.iterdir()))

    def test_scadenza_del_job_salta_il_download(self):
        proc = self.run_script(["--download-only", "rete-a", self.prefetch], TRANSIT_DEADLINE="1")
        self.assertEqual(1, proc.returncode)
        self.assertIn("tempo del job finito", proc.stderr)

    def test_host_non_raggiungibile_salta_il_download(self):
        unreachable = fx.write(self.tree / "unreachable.txt", "127.0.0.1:9\n")
        proc = self.run_script(["--download-only", "rete-a", self.prefetch], TRANSIT_UNREACHABLE=unreachable.as_posix())
        self.assertEqual(1, proc.returncode)
        self.assertIn("non risponde in questo job", proc.stderr)

    def test_feed_invariato_e_recente_tiene_la_voce_con_i_dati_della_lista(self):
        data = feed_zip()
        proc = self.build(data, {"feeds": [published_entry(data)]})
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("la tengo", proc.stdout)
        (entry,) = self.lines("transit-entries.jsonl")
        self.assertEqual(("Rete A", ["it", "fr"], "Attribuzione A"), (entry["name"], entry["regions"], entry["attribution"]))
        self.assertEqual(published_entry(data)["version"], entry["version"])

    def test_tempo_reale_non_scaricato_avvisa_senza_rompere_la_voce(self):
        data = feed_zip()
        self.run_script = lambda args, rows=None, **env: BuildTransitTest.run_script(
            self, args, feeds_row(realtime="https://127.0.0.1:9/rt.pb"), **env)
        proc = self.build(data, {"feeds": [published_entry(data, gtfsIds=True)]})
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("feed del tempo reale non scaricato", proc.stderr)
        (entry,) = self.lines("transit-entries.jsonl")
        self.assertEqual("https://127.0.0.1:9/rt.pb", entry["realtimeUrl"])
        self.assertNotIn("realtimeSizeBytes", entry)

    def test_feed_invariato_ma_voce_vecchia_si_ricostruisce(self):
        data = feed_zip()
        fx.write(self.tree / "gradlew", FAILING_GRADLE, executable=True)
        proc = self.build(data, {"feeds": [published_entry(data, days_old=30)]})
        self.assertNotEqual(0, proc.returncode)
        self.assertIn("converto", proc.stdout)
        self.assertEqual([], self.lines("transit-entries.jsonl"))

    def test_voce_con_orari_finiti_non_si_tiene(self):
        data = feed_zip()
        fx.write(self.tree / "gradlew", FAILING_GRADLE, executable=True)
        proc = self.build(data, {"feeds": [published_entry(data, valid_until="2000-01-01")]})
        self.assertIn("converto", proc.stdout)
        self.assertNotIn("la tengo", proc.stdout)

    def test_feed_cambiato_si_ricostruisce(self):
        fx.write(self.tree / "gradlew", FAILING_GRADLE, executable=True)
        proc = self.build(feed_zip(b"nuovo\n"), {"feeds": [published_entry(feed_zip())]})
        self.assertIn("converto", proc.stdout)

    def test_rete_gia_scaduta_con_lo_stesso_feed_esce_con_3(self):
        data = feed_zip()
        expired = {"id": "rete-a", "name": "Vecchio", "regions": ["xx"], "reason": "orari", "sourceSha256": hashlib.sha256(data).hexdigest()}
        proc = self.build(data, {"feeds": [], "missing": [expired]})
        self.assertEqual(3, proc.returncode, proc.stderr)
        (entry,) = self.lines("transit-expired.jsonl")
        self.assertEqual(("Rete A", ["it", "fr"], "orari"), (entry["name"], entry["regions"], entry["reason"]))

    def test_scaduta_con_un_altro_feed_o_motivo_non_vale(self):
        data = feed_zip()
        fx.write(self.tree / "gradlew", FAILING_GRADLE, executable=True)
        other = {"id": "rete-a", "reason": "orari", "sourceSha256": "00"}
        same_feed_other_reason = {**other, "sourceSha256": hashlib.sha256(data).hexdigest(), "reason": "licenza"}
        proc = self.build(data, {"feeds": [], "missing": [other, same_feed_other_reason]})
        self.assertNotEqual(3, proc.returncode)
        self.assertIn("converto", proc.stdout)


if __name__ == "__main__":
    unittest.main()
