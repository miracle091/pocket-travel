#!/usr/bin/env python3
"""Test delle richieste di .github/scripts/publish-regions/assemble.sh a Pages: il catalogo e gli indici pubblicati si
leggono con un parametro nuovo e Cache-Control: no-cache (Pages risponde con max-age=600 dietro una CDN, e una run subito
dopo un deploy partirebbe da file vecchi). Gradle e' finto (esce con errore appena finiscono i download). Richiede bash.

Uso: python test_assemble_requests.py
"""
import base64
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import shell_fixtures as fx

SCRIPT = ".github/scripts/publish-regions/assemble.sh"
SYNC_CONFIG = "core/sync/src/main/kotlin/com/pockettravel/core/sync/SyncConfig.kt"
# La firma si verifica solo nella CI del progetto: un fork firma con la sua chiave.
PROJECT = "miracle091/pocket-travel"


def sign_manifest(tree, body, valid=True):
    """Chiave ECDSA P-256 di prova, SyncConfig.kt finto con la sua parte pubblica e firma DER di [body] (non valida
    se [valid] e' falso)."""
    key, data, sig = tree / "k.pem", tree / "m.json", tree / "m.json.sig"
    subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(key)], check=True, capture_output=True)
    pub = subprocess.run(["openssl", "ec", "-in", str(key), "-pubout", "-outform", "DER"], check=True, capture_output=True).stdout
    key_b64 = base64.b64encode(pub).decode()
    fx.write(tree / SYNC_CONFIG, "const val OFFICIAL_PUBLIC_KEY =\n    \"" + key_b64 + "\"\n")
    data.write_bytes(body if valid else body + b" ")
    subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key), "-out", str(sig), str(data)], check=True, capture_output=True)
    return sig.read_bytes()


@unittest.skipUnless(fx.BASH, "serve bash")
class AssembleRequestsTest(unittest.TestCase):
    def run_assemble(self, files, signature_valid=True, repository=PROJECT):
        with tempfile.TemporaryDirectory() as d:
            tree = Path(d)
            if "/manifest.json" in files and "/manifest.json.sig" not in files:
                files = {**files, "/manifest.json.sig": sign_manifest(tree, files["/manifest.json"], signature_valid)}
            for f in (SCRIPT, ".github/scripts/verify-app-status.sh", ".github/scripts/env.sh", ".github/scripts/retry.sh", "tools/data-pipeline/scripts/portable.sh", "tools/data-pipeline/scripts/lib.sh"):
                fx.copy(f, tree)
            fx.write(tree / "gradlew", "#!/usr/bin/env bash\nexit 1\n", executable=True)
            runner = tree / "runner"
            runner.mkdir()
            with fx.serve(files) as server:
                env = fx.environment(tree, GITHUB_REPOSITORY=repository, GITHUB_REPOSITORY_OWNER="o", RUNNER_TEMP=runner.as_posix(),
                                     GITHUB_OUTPUT=(runner / "out").as_posix(), GITHUB_STEP_SUMMARY=(runner / "summary").as_posix(),
                                     PAGES_BASE_URL=server.base, SIGNATURE_RETRY_DELAY="0")
                proc = fx.run_bash([SCRIPT], tree, env)
                return proc, list(server.requests)

    def test_catalogo_e_indici_senza_cache(self):
        files = {"/manifest.json": b"{}", "/address-grid.json": b"{}", "/transit.json": b"{}"}
        proc, requests = self.run_assemble(files)
        self.assertNotEqual(0, proc.returncode)  # Gradle finto
        paths = {urlsplit(p).path: (parse_qs(urlsplit(p).query), h) for p, h in requests}
        self.assertEqual({"/manifest.json", "/address-grid.json", "/transit.json"}, set(paths) - {"/manifest.json.sig"})
        for path, (query, headers) in paths.items():
            self.assertTrue(query.get("cb", [""])[0].isdigit(), f"{path}: parametro cb mancante")
            self.assertEqual("no-cache", headers.get("Cache-Control"), path)

    def test_firma_non_valida_ferma_il_passo(self):
        proc, requests = self.run_assemble({"/manifest.json": b"{}"}, signature_valid=False)
        self.assertNotEqual(0, proc.returncode)
        self.assertIn("firma di manifest.json pubblicato mancante o non valida", proc.stderr)
        self.assertNotIn("/address-grid.json", {urlsplit(p).path for p, _ in requests})
        # 4 tentativi, e la diagnostica della firma su stderr.
        self.assertEqual(4, sum(urlsplit(p).path == "/manifest.json" for p, _ in requests))
        self.assertIn("firma:", proc.stderr)

    def test_firma_vuota_ferma_il_passo(self):
        proc, _ = self.run_assemble({"/manifest.json": b"{}", "/manifest.json.sig": b""})
        self.assertNotEqual(0, proc.returncode)

    def test_stesso_parametro_per_tutti_i_file_della_run(self):
        files = {"/manifest.json": b"{}", "/address-grid.json": b"{}", "/transit.json": b"{}"}
        _, requests = self.run_assemble(files)
        self.assertEqual(1, len({parse_qs(urlsplit(p).query)["cb"][0] for p, _ in requests}))

    def test_fork_senza_verifica(self):
        proc, requests = self.run_assemble({"/manifest.json": b"{}"}, signature_valid=False, repository="o/r")
        self.assertNotIn("firma", proc.stderr)
        self.assertNotIn("/manifest.json.sig", {urlsplit(p).path for p, _ in requests})

    def test_primo_pubblico_senza_firma(self):
        proc, requests = self.run_assemble({})
        self.assertNotIn("firma", proc.stderr)
        self.assertNotIn("/manifest.json.sig", {urlsplit(p).path for p, _ in requests})


@unittest.skipUnless(fx.BASH, "serve bash")
class FetchPublishedManifestTest(unittest.TestCase):
    def fetch(self, files, path="/manifest.json", query="", repository="o/r", signature_valid=None):
        with tempfile.TemporaryDirectory() as d:
            tree = Path(d)
            if signature_valid is not None:
                files = {**files, path + ".sig": sign_manifest(tree, files[path], signature_valid)}
            for f in ("tools/data-pipeline/scripts/lib.sh", "tools/data-pipeline/scripts/portable.sh", ".github/scripts/verify-app-status.sh"):
                fx.copy(f, tree)
            out = tree / "out.json"
            with fx.serve(files) as server:
                script = f'source tools/data-pipeline/scripts/lib.sh; fetch_published_manifest "{server.base}{path}{query}" "{out.as_posix()}"'
                started = time.monotonic()
                proc = fx.run_bash(["-c", script], tree, fx.environment(tree, GITHUB_REPOSITORY=repository))
                return proc.stdout.strip(), list(server.requests), time.monotonic() - started

    def test_firma_valida_nel_progetto(self):
        code, requests, _ = self.fetch({"/manifest.json": b"{}"}, repository=PROJECT, signature_valid=True)
        self.assertEqual("200", code)
        self.assertIn("/manifest.json.sig", {urlsplit(p).path for p, _ in requests})

    def test_firma_non_valida_nel_progetto(self):
        code, _, _ = self.fetch({"/manifest.json": b"{}"}, repository=PROJECT, signature_valid=False)
        self.assertEqual("firma", code)

    def test_firma_mancante_nel_progetto(self):
        code, _, _ = self.fetch({"/manifest.json": b"{}"}, repository=PROJECT)
        self.assertEqual("firma", code)

    def test_fork_senza_richiesta_della_firma(self):
        code, requests, _ = self.fetch({"/manifest.json": b"{}"})
        self.assertEqual("200", code)
        self.assertNotIn("/manifest.json.sig", {urlsplit(p).path for p, _ in requests})

    def test_richiesta_senza_cache(self):
        code, requests, _ = self.fetch({"/manifest.json": b"{}"})
        self.assertEqual("200", code)
        (path, headers), = requests
        self.assertTrue(parse_qs(urlsplit(path).query).get("cb", [""])[0].isdigit())
        self.assertEqual("no-cache", headers.get("Cache-Control"))

    def test_query_esistente_conservata(self):
        _, requests, _ = self.fetch({"/manifest.json": b"{}"}, query="?x=1")
        query = parse_qs(urlsplit(requests[0][0]).query)
        self.assertEqual(["1"], query["x"])
        self.assertIn("cb", query)

    def test_404_senza_ritentativi(self):
        code, requests, elapsed = self.fetch({})
        self.assertEqual("404", code)
        self.assertEqual(1, len(requests))
        self.assertLess(elapsed, 15)


if __name__ == "__main__":
    unittest.main()
