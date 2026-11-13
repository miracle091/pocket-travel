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
HEADER = "# id\tregioni\tnome\tlicenza\tattribuzione\turl_licenza\turl_feed\tcerca_ultimo\turl_rt\turl_sa\taggancio_rt\n"
GENERATOR = 3  # GENERATOR_VERSION di build-transit.sh
FAILING_GRADLE = "#!/usr/bin/env bash\nexit 7\n"


def feed_zip(content=b"stop_id\n1\n"):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        z.writestr("stops.txt", content)
    return buffer.getvalue()


def feeds_row(feed_id="rete-a", url="http://127.0.0.1:9/feed.zip", realtime="", alerts=None, match=None):
    """Riga della lista; [alerts] e [match] None = riga senza le colonne url_sa e aggancio_rt (facoltative)."""
    tail = "" if alerts is None and match is None else f"\t{alerts or ''}"
    if match is not None:
        tail += f"\t{match}"
    return f"{feed_id}\tit,fr\tRete A\tCC-BY-4.0\tAttribuzione A\thttps://licenza\t{url}\t\t{realtime}{tail}\n"


def published_entry(zip_bytes, days_old=1, valid_until="2999-01-01", **extra):
    version = (date.today() - timedelta(days=days_old)).strftime("%Y.%m.%d.1.1")
    return {"id": "rete-a", "name": "Vecchio nome", "regions": ["it"], "license": "x", "attribution": "x", "licenseUrl": "x",
            "version": version, "sourceSha256": hashlib.sha256(zip_bytes).hexdigest(), "validUntil": valid_until,
            "generator": GENERATOR, **extra}


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

    def test_url_avvisi_non_https(self):
        proc = self.run_script(["rete-a", "v", "https://h", self.out], rows=feeds_row(alerts="http://rt/alerts.pb"))
        self.assertEqual(1, proc.returncode)
        self.assertIn("url_sa non https", proc.stderr)

    def test_aggancio_non_valido(self):
        proc = self.run_script(["rete-a", "v", "https://h", self.out], rows=feeds_row(match="per_trip_id"))
        self.assertEqual(1, proc.returncode)
        self.assertIn("aggancio_rt non valido", proc.stderr)

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

    def test_convertitore_senza_host_salta_il_download(self):
        unreachable = fx.write(self.tree / "unreachable.txt", "script:iett_gtfs.py\n")
        proc = self.run_script(["--download-only", "rete-a", self.prefetch], rows=feeds_row(url="script:iett_gtfs.py"),
                               TRANSIT_UNREACHABLE=unreachable.as_posix())
        self.assertEqual(1, proc.returncode)
        self.assertIn("non raggiunge il suo host in questo job", proc.stderr)

    def test_convertitore_che_esce_con_75_si_segna_irraggiungibile_con_1_no(self):
        for code, listed in ((75, ["script:finto.py"]), (1, [])):
            with self.subTest(code=code):
                fx.write(self.tree / "tools/data-pipeline/scripts/finto.py", f"import sys\nsys.exit({code})\n")
                unreachable = fx.write(self.tree / "unreachable.txt", "")
                proc = self.run_script(["--download-only", "rete-a", self.prefetch], rows=feeds_row(url="script:finto.py"),
                                       TRANSIT_UNREACHABLE=unreachable.as_posix())
                self.assertEqual(1, proc.returncode, proc.stderr)
                self.assertEqual(listed, unreachable.read_text(encoding="utf-8").split())

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

    def with_rows(self, rows):
        """Le prossime run_script usano la riga [rows] della lista."""
        self.run_script = lambda args, rows=rows, **env: BuildTransitTest.run_script(self, args, rows, **env)

    def curl_sizes(self):
        """curl finto: il peso dei due feed del tempo reale, senza rete (gli indirizzi sono https)."""
        fx.shim(self.tree, "curl", 'case "${@: -1}" in *rt.pb) echo -n 1234;; *alerts.pb) echo -n 567;; *) exit 22;; esac\n')

    def test_avvisi_nella_voce_con_il_peso_misurato(self):
        data = feed_zip()
        self.curl_sizes()
        self.with_rows(feeds_row(realtime="https://h/rt.pb", alerts="https://h/alerts.pb"))
        proc = self.build(data, {"feeds": [published_entry(data, gtfsIds=True, alertsUrl="https://h/alerts.pb")]})
        self.assertEqual(0, proc.returncode, proc.stderr)
        (entry,) = self.lines("transit-entries.jsonl")
        self.assertEqual(("https://h/rt.pb", 1234, "https://h/alerts.pb", 567),
                         (entry["realtimeUrl"], entry["realtimeSizeBytes"], entry["alertsUrl"], entry["alertsSizeBytes"]))

    def test_avvisi_nello_stesso_file_dei_ritardi_hanno_lo_stesso_peso(self):
        data = feed_zip()
        self.curl_sizes()
        self.with_rows(feeds_row(realtime="https://h/rt.pb", alerts="https://h/rt.pb"))
        proc = self.build(data, {"feeds": [published_entry(data, gtfsIds=True, alertsUrl="https://h/rt.pb")]})
        self.assertEqual(0, proc.returncode, proc.stderr)
        (entry,) = self.lines("transit-entries.jsonl")
        self.assertEqual((1234, 1234), (entry["realtimeSizeBytes"], entry["alertsSizeBytes"]))

    def test_solo_avvisi_senza_peso_se_il_feed_non_arriva(self):
        data = feed_zip()
        self.with_rows(feeds_row(alerts="https://127.0.0.1:9/alerts.pb"))
        proc = self.build(data, {"feeds": [published_entry(data, gtfsIds=True, alertsUrl="https://127.0.0.1:9/alerts.pb")]})
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("feed degli avvisi non scaricato", proc.stderr)
        (entry,) = self.lines("transit-entries.jsonl")
        self.assertEqual("https://127.0.0.1:9/alerts.pb", entry["alertsUrl"])
        self.assertNotIn("alertsSizeBytes", entry)
        self.assertNotIn("realtimeUrl", entry)

    def test_avvisi_tolti_dalla_lista_la_rete_si_ricostruisce(self):
        data = feed_zip()
        fx.write(self.tree / "gradlew", FAILING_GRADLE, executable=True)
        proc = self.build(data, {"feeds": [published_entry(data, gtfsIds=True, alertsUrl="https://h/alerts.pb")]})
        self.assertIn("converto", proc.stdout)
        self.assertNotIn("la tengo", proc.stdout)

    def test_voce_senza_avvisi_per_una_rete_che_ora_li_ha_si_ricostruisce(self):
        data = feed_zip()
        fx.write(self.tree / "gradlew", FAILING_GRADLE, executable=True)
        self.curl_sizes()
        self.with_rows(feeds_row(alerts="https://h/alerts.pb"))
        proc = self.build(data, {"feeds": [published_entry(data)]})
        self.assertIn("converto", proc.stdout)

    def test_solo_avvisi_voce_senza_gtfs_ids_si_ricostruisce(self):
        data = feed_zip()
        fx.write(self.tree / "gradlew", FAILING_GRADLE, executable=True)
        self.curl_sizes()
        self.with_rows(feeds_row(alerts="https://h/alerts.pb"))
        proc = self.build(data, {"feeds": [published_entry(data, alertsUrl="https://h/alerts.pb")]})
        self.assertIn("converto", proc.stdout)

    def test_voce_di_un_altro_generatore_si_ricostruisce(self):
        data = feed_zip()
        fx.write(self.tree / "gradlew", FAILING_GRADLE, executable=True)
        for generator in (GENERATOR - 1, None):
            with self.subTest(generator=generator):
                entry = published_entry(data)
                if generator is None:
                    del entry["generator"]
                else:
                    entry["generator"] = generator
                proc = self.build(data, {"feeds": [entry]})
                self.assertIn("converto", proc.stdout)
                self.assertNotIn("la tengo", proc.stdout)

    def gradle_args(self, rows):
        """Argomenti dati a gradle dalla conversione di una rete con la riga [rows] (gradlew finto che li scrive)."""
        record = self.tree / "gradle-args.txt"
        fx.write(self.tree / "gradlew", f'#!/usr/bin/env bash\necho "$@" > "{record.as_posix()}"\nexit 7\n', executable=True)
        self.curl_sizes()
        self.with_rows(rows)
        self.build(feed_zip())
        return record.read_text(encoding="utf-8")

    def test_id_gtfs_con_url_rt_o_url_sa_o_tutti_e_due_ma_non_senza(self):
        cases = (
            (feeds_row(), False),
            (feeds_row(realtime="https://h/rt.pb"), True),
            (feeds_row(alerts="https://h/alerts.pb"), True),
            (feeds_row(realtime="https://h/rt.pb", alerts="https://h/alerts.pb"), True),
            (feeds_row(match="linea_ora"), True),
            (feeds_row(match=""), False),
        )
        for rows, expected in cases:
            with self.subTest(rows=rows):
                self.assertEqual(expected, "--gtfs-ids" in self.gradle_args(rows))

    def test_trip_keys_solo_con_linea_ora(self):
        for match, expected in (("linea_ora", True), ("", False)):
            with self.subTest(match=match):
                self.assertEqual(expected, "--trip-keys" in self.gradle_args(feeds_row(realtime="https://h/rt.pb", match=match)))

    def test_aggancio_nel_riuso_della_voce(self):
        data = feed_zip()
        fx.write(self.tree / "gradlew", FAILING_GRADLE, executable=True)
        self.curl_sizes()
        cases = (
            # (riga della lista, voce pubblicata, la tengo?)
            (feeds_row(realtime="https://h/rt.pb", match="linea_ora"), dict(gtfsIds=True, tripKeys=True), True),
            (feeds_row(realtime="https://h/rt.pb", match="linea_ora"), dict(gtfsIds=True), False),
            (feeds_row(realtime="https://h/rt.pb"), dict(gtfsIds=True, tripKeys=True), False),
            (feeds_row(match="linea_ora"), dict(), False),
        )
        for rows, extra, kept in cases:
            with self.subTest(rows=rows, extra=extra):
                self.with_rows(rows)
                proc = self.build(data, {"feeds": [published_entry(data, **extra)]})
                self.assertEqual(kept, "la tengo" in proc.stdout, proc.stdout)

    def test_conversione_scrive_generator_e_avvisi_nella_voce(self):
        # gradlew finto: scrive la transit.db (secondo argomento di --args) e la riga delle statistiche.
        fx.write(self.tree / "gradlew", """#!/usr/bin/env bash
eval "set -- ${3#--args=}"
echo db > "$2"
printf '2\t1\t3\t4\t2999-01-01\t1.0,2.0,3.0,4.0\n'
""", executable=True)
        self.curl_sizes()
        self.with_rows(feeds_row(alerts="https://h/alerts.pb"))
        proc = self.build(feed_zip())
        self.assertEqual(0, proc.returncode, proc.stderr)
        (entry,) = self.lines("transit-entries.jsonl")
        self.assertEqual((GENERATOR, True, "https://h/alerts.pb", 567), (entry["generator"], entry["gtfsIds"], entry["alertsUrl"], entry["alertsSizeBytes"]))
        self.assertNotIn("realtimeUrl", entry)
        self.assertNotIn("tripKeys", entry)

    def test_conversione_con_aggancio_scrive_trip_keys_e_gtfs_ids(self):
        fx.write(self.tree / "gradlew", """#!/usr/bin/env bash
eval "set -- ${3#--args=}"
echo db > "$2"
printf '2\t1\t3\t4\t2999-01-01\t1.0,2.0,3.0,4.0\n'
""", executable=True)
        self.with_rows(feeds_row(match="linea_ora"))
        proc = self.build(feed_zip())
        self.assertEqual(0, proc.returncode, proc.stderr)
        (entry,) = self.lines("transit-entries.jsonl")
        self.assertEqual((True, True), (entry["gtfsIds"], entry["tripKeys"]))

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

    def test_voce_senza_valid_until_non_si_tiene(self):
        data = feed_zip()
        fx.write(self.tree / "gradlew", FAILING_GRADLE, executable=True)
        entry = published_entry(data)
        del entry["validUntil"]
        proc = self.build(data, {"feeds": [entry]})
        self.assertIn("converto", proc.stdout)
        self.assertNotIn("la tengo", proc.stdout)

    def test_voce_con_la_data_della_versione_illeggibile_si_ricostruisce(self):
        data = feed_zip()
        fx.write(self.tree / "gradlew", FAILING_GRADLE, executable=True)
        entry = {**published_entry(data), "version": "2026.13.45.1.1"}
        proc = self.build(data, {"feeds": [entry]})
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
