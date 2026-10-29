"""Supporto dei test degli script shell (test_plan_shards.py, test_assemble_requests.py, test_build_transit.py,
test_build_cities_dump.py, test_generate_weekly_schedule.py, test_build_shell_helpers.py): copia gli script in una
cartella temporanea con file finti al posto di rete e Gradle, un piccolo server HTTP locale, l'ambiente senza le
variabili di GitHub Actions. Niente rete vera.
"""
import http.server
import os
import shutil
import stat
import subprocess
import tempfile
import threading
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import urlsplit

REPO = Path(__file__).resolve().parents[3]
BASH = shutil.which("bash")


def write(path, text, executable=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    if executable:
        path.chmod(path.stat().st_mode | stat.S_IXUSR)
    return path


def copy(relative, tree):
    """Copia un file del repository nello stesso percorso relativo di [tree]."""
    target = tree / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(REPO / relative, target)
    target.chmod(target.stat().st_mode | stat.S_IXUSR)
    return target


def environment(tree, **extra):
    """Ambiente di un processo bash: senza le variabili di GitHub Actions, con python3 nel PATH (su Windows c'e' solo
    "python") e con le cartelle di prova in [tree]."""
    env = {k: v for k, v in os.environ.items() if not k.startswith(("GITHUB_", "RUNNER_", "PAGES_", "PUBLISH"))}
    shims = tree / "shims"
    shims.mkdir(exist_ok=True)
    if shutil.which("python3") is None:
        write(shims / "python3", f'#!/usr/bin/env bash\nexec "{Path(shutil.which("python") or "python").as_posix()}" "$@"\n', executable=True)
    env["PATH"] = f"{shims.as_posix()}{os.pathsep}{env['PATH']}"
    env.update({k: str(v) for k, v in extra.items()})
    return env


def shim(tree, name, body):
    """Comando finto nel PATH di [environment]."""
    return write(tree / "shims" / name, "#!/usr/bin/env bash\n" + body, executable=True)


def run_bash(args, cwd, env, timeout=120):
    """Esegue bash con [args]; stderr passa da un file perche' `tee /dev/stderr` degli script non funziona con una pipe
    su Git Bash."""
    with tempfile.TemporaryFile("w+", encoding="utf-8") as err:
        proc = subprocess.run([BASH, *map(str, args)], cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=err, text=True, timeout=timeout)
        err.seek(0)
        proc.stderr = err.read()
    return proc


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.server.requests.append((self.path, dict(self.headers)))
        body = self.server.files.get(urlsplit(self.path).path)
        if body is None:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_HEAD = do_GET


@contextmanager
def serve(files):
    """Server HTTP su 127.0.0.1: [files] e' {percorso: byte}; altro risponde 404. Registra (percorso con query,
    intestazioni) di ogni richiesta in .requests; .base e' l'indirizzo."""
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.files, server.requests = files, []
    server.base = f"http://127.0.0.1:{server.server_address[1]}"
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def parse_output(path):
    """File GITHUB_OUTPUT -> {nome: valore}."""
    return dict(line.split("=", 1) for line in Path(path).read_text(encoding="utf-8").splitlines() if "=" in line)
