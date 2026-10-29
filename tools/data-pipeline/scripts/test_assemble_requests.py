#!/usr/bin/env python3
"""Test delle richieste di .github/scripts/publish-regions/assemble.sh a Pages: il catalogo e gli indici pubblicati si
leggono con un parametro nuovo e Cache-Control: no-cache (Pages risponde con max-age=600 dietro una CDN, e una run subito
dopo un deploy partirebbe da file vecchi). Gradle e' finto (esce con errore appena finiscono i download). Richiede bash.

Uso: python test_assemble_requests.py
"""
import tempfile
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import shell_fixtures as fx

SCRIPT = ".github/scripts/publish-regions/assemble.sh"


@unittest.skipUnless(fx.BASH, "serve bash")
class AssembleRequestsTest(unittest.TestCase):
    def run_assemble(self, files):
        with tempfile.TemporaryDirectory() as d:
            tree = Path(d)
            for f in (SCRIPT, ".github/scripts/env.sh", "tools/data-pipeline/scripts/portable.sh", "tools/data-pipeline/scripts/lib.sh"):
                fx.copy(f, tree)
            fx.write(tree / "gradlew", "#!/usr/bin/env bash\nexit 1\n", executable=True)
            runner = tree / "runner"
            runner.mkdir()
            with fx.serve(files) as server:
                env = fx.environment(tree, GITHUB_REPOSITORY="o/r", GITHUB_REPOSITORY_OWNER="o", RUNNER_TEMP=runner.as_posix(),
                                     GITHUB_OUTPUT=(runner / "out").as_posix(), GITHUB_STEP_SUMMARY=(runner / "summary").as_posix(),
                                     PAGES_BASE_URL=server.base)
                proc = fx.run_bash([SCRIPT], tree, env)
                return proc, list(server.requests)

    def test_catalogo_e_indici_senza_cache(self):
        files = {"/manifest.json": b"{}", "/address-grid.json": b"{}", "/transit.json": b"{}"}
        proc, requests = self.run_assemble(files)
        self.assertNotEqual(0, proc.returncode)  # Gradle finto
        paths = {urlsplit(p).path: (parse_qs(urlsplit(p).query), h) for p, h in requests}
        self.assertEqual({"/manifest.json", "/address-grid.json", "/transit.json"}, set(paths))
        for path, (query, headers) in paths.items():
            self.assertTrue(query.get("cb", [""])[0].isdigit(), f"{path}: parametro cb mancante")
            self.assertEqual("no-cache", headers.get("Cache-Control"), path)

    def test_stesso_parametro_per_tutti_i_file_della_run(self):
        files = {"/manifest.json": b"{}", "/address-grid.json": b"{}", "/transit.json": b"{}"}
        _, requests = self.run_assemble(files)
        self.assertEqual(1, len({parse_qs(urlsplit(p).query)["cb"][0] for p, _ in requests}))


if __name__ == "__main__":
    unittest.main()
