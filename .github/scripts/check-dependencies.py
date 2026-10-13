#!/usr/bin/env python3
"""Controllo delle dipendenze: vulnerabilita' note (OSV) e versioni piu' nuove.

Uso: check-dependencies.py [--catalog gradle/libs.versions.toml] report.txt [report.txt ...]

I report sono l'output di `gradle dependencies --configuration <classpath>`. Le coordinate risolte
(transitive comprese) vengono cercate su OSV: una vulnerabilita' e' un errore (exit 1). Le versioni
del catalogo si confrontano con quelle pubblicate su Google Maven, Maven Central e sul portale dei
plugin Gradle: una versione piu' nuova e' solo una nota (::notice::). Se un servizio non risponde
si emette un ::warning:: e l'esito non cambia: la CI non deve fallire per un'API di terzi.
"""
import argparse
import json
import re
import sys
import tomllib
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlparse

USER_AGENT = "pocket-travel-check-dependencies/1.0 (https://github.com/miracle091/pocket-travel)"
TIMEOUT_S = 30
OSV_BATCH_URL = "https://api.osv.dev/v1/querybatch"
OSV_VULN_URL = "https://api.osv.dev/v1/vulns/"
OSV_BATCH_SIZE = 500

GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"
PLUGIN_PORTAL = "https://plugins.gradle.org/m2"

# Gli unici host contattati: niente URL che arrivano da file o da risposte.
TRUSTED_HOSTS = {"api.osv.dev", "dl.google.com", "repo1.maven.org", "plugins.gradle.org"}


class HttpError(Exception):
    """Risposta HTTP con stato diverso da 2xx."""

    def __init__(self, status):
        super().__init__(f"HTTP {status}")
        self.status = status


def http_request(url, data=None):
    """Esegue la richiesta (POST se c'e' `data`) verso un host fidato e restituisce il corpo."""
    if urlparse(url).hostname not in TRUSTED_HOSTS:
        raise ValueError(f"host non fidato: {url}")
    headers = {"User-Agent": USER_AGENT}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:  # noqa: S310 (host verificato sopra)
            return resp.read()
    except urllib.error.HTTPError as e:
        raise HttpError(e.code) from e


# ---------------------------------------------------------------------------
# Report di `gradle dependencies`
# ---------------------------------------------------------------------------

_TREE_LINE = re.compile(r"^[ |]*[+\\]--- (.*)$")
_MARKERS = re.compile(r"(\s+\((?:\*|c|n)\))+\s*$")


def parse_report(text):
    """Restituisce l'insieme delle coordinate (gruppo, artefatto, versione) risolte nel report."""
    found = set()
    for line in text.splitlines():
        m = _TREE_LINE.match(line)
        if not m:
            continue
        rest = _MARKERS.sub("", m.group(1)).strip()
        if rest.startswith("project ") or rest.endswith(" FAILED"):
            continue
        if " -> " in rest:
            left, right = (s.strip() for s in rest.split(" -> ", 1))
            # Con una rilocazione (g:a:1 -> altro.g:altro.a:2) vale il lato destro per intero.
            parts = right.split(":") if ":" in right else left.split(":")[:2] + [right]
        else:
            parts = rest.split(":")
        if len(parts) != 3 or not all(parts):
            continue
        group, name, version = parts
        # Vincolo senza risoluzione (g:a:{strictly 1.0}): non e' una versione.
        if version.startswith("{") or " " in version:
            continue
        found.add((group, name, version))
    return found


# ---------------------------------------------------------------------------
# Vulnerabilita' (OSV)
# ---------------------------------------------------------------------------


def query_osv(coords, http):
    """Restituisce {(g, a, v): [id, ...]} per le coordinate con vulnerabilita' note."""
    ordered = sorted(coords)
    hits = {}
    for i in range(0, len(ordered), OSV_BATCH_SIZE):
        chunk = ordered[i:i + OSV_BATCH_SIZE]
        body = {"queries": [
            {"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for g, a, v in chunk
        ]}
        results = json.loads(http(OSV_BATCH_URL, json.dumps(body).encode("utf-8"))).get("results", [])
        for coord, res in zip(chunk, results):
            ids = [v["id"] for v in res.get("vulns", [])]
            if ids:
                hits[coord] = ids
    return hits


def vuln_details(vuln_id, package, http):
    """Alias CVE e versioni corrette di una vulnerabilita'; vuoti se il dettaglio non e' raggiungibile."""
    try:
        d = json.loads(http(OSV_VULN_URL + vuln_id))
    except Exception:  # noqa: BLE001 (il dettaglio e' solo un di piu')
        return [], []
    cves = sorted(a for a in d.get("aliases", []) if a.startswith("CVE-"))
    fixed = sorted({
        e["fixed"]
        for aff in d.get("affected", [])
        if aff.get("package", {}).get("name") == package
        for rng in aff.get("ranges", [])
        for e in rng.get("events", [])
        if "fixed" in e
    })
    return cves, fixed


def check_vulnerabilities(reports, http, out):
    """`reports` e' {etichetta: set di coordinate}. Restituisce il numero di coordinate vulnerabili."""
    where = {}
    for label, coords in reports.items():
        for c in coords:
            where.setdefault(c, set()).add(label)
    if not where:
        return 0
    try:
        hits = query_osv(where.keys(), http)
    except Exception as e:  # noqa: BLE001
        out(f"::warning::OSV non raggiungibile ({e}): controllo delle vulnerabilita' saltato")
        return 0
    for (g, a, v), ids in sorted(hits.items()):
        for vuln_id in ids:
            cves, fixed = vuln_details(vuln_id, f"{g}:{a}", http)
            extra = (" " + ",".join(cves)) if cves else ""
            fix = f"corretta in {', '.join(fixed)}" if fixed else "nessuna versione corretta indicata"
            out(f"::error::{g}:{a}:{v} vulnerabile: {vuln_id}{extra}, {fix} "
                f"(report: {', '.join(sorted(where[(g, a, v)]))})")
    return len(hits)


# ---------------------------------------------------------------------------
# Versioni
# ---------------------------------------------------------------------------

_VERSION = re.compile(r"^(\d+(?:\.\d+)*)(.*)$")
# "pre<hash>" e' la forma delle build di anteprima di MapLibre (13.6.1-pre935d410...).
_PRE_TOKEN = re.compile(r"^(alpha|beta|rc|cr|m|milestone|dev|eap|snapshot|preview|pre)\d*$|^pre[0-9a-f]{7,}$", re.I)
_PRE_RANK = {"alpha": 0, "dev": 0, "eap": 0, "snapshot": 0, "preview": 0, "pre": 0, "m": 0, "milestone": 0,
             "beta": 1, "rc": 2, "cr": 2}


def _pre_token(version):
    for tok in re.split(r"[^A-Za-z0-9]+", version):
        if _PRE_TOKEN.match(tok):
            return re.match(r"[A-Za-z]+", tok).group(0).lower()
    return None


def is_prerelease(version):
    return _pre_token(version) is not None


def version_key(version):
    """Chiave d'ordinamento: i numeri iniziali, poi pre-release < stabile, poi i numeri del suffisso."""
    m = _VERSION.match(version)
    if not m:
        return ((-1,), 0, ())
    nums = [int(n) for n in m.group(1).split(".")]
    while len(nums) > 1 and nums[-1] == 0:
        nums.pop()
    tok = _pre_token(m.group(2))
    rank = _PRE_RANK[tok] if tok else 3
    suffix = tuple(int(n) for n in re.findall(r"\d+", m.group(2)))
    return (tuple(nums), rank, suffix)


def release_line(version):
    m = _VERSION.match(version)
    return tuple(m.group(1).split(".")[:2]) if m else None


def newer_version(current, available):
    """La versione piu' nuova da segnalare, o None.

    Una versione stabile si confronta con la piu' alta stabile. Una pre-release scelta di proposito
    si confronta con le versioni piu' nuove della stessa linea (stessi primi due numeri), qualunque
    sia il loro stato.
    """
    if is_prerelease(current):
        line = release_line(current)
        candidates = [v for v in available if release_line(v) == line]
    else:
        candidates = [v for v in available if not is_prerelease(v)]
    candidates = [v for v in candidates if version_key(v) > version_key(current)]
    return max(candidates, key=version_key) if candidates else None


def catalog_entries(catalog):
    """Per ogni versione del catalogo usata da una libreria o da un plugin: (ref, versione, [(gruppo, nome)])."""
    versions = catalog.get("versions", {})
    by_ref = {}
    for lib in catalog.get("libraries", {}).values():
        ver = lib.get("version")
        if isinstance(ver, dict) and ver.get("ref") in versions:
            by_ref.setdefault(ver["ref"], []).append((lib["group"], lib["name"]))
    for plugin in catalog.get("plugins", {}).values():
        ver = plugin.get("version")
        if isinstance(ver, dict) and ver.get("ref") in versions:
            by_ref.setdefault(ver["ref"], []).append((plugin["id"], plugin["id"] + ".gradle.plugin"))
    return [(ref, versions[ref], by_ref[ref]) for ref in versions if ref in by_ref]


def published_versions(group, name, http, down_hosts, problems):
    """Versioni pubblicate; None se nessun repository risponde. Gli errori di rete finiscono in `problems`."""
    path = f"{group.replace('.', '/')}/{name}/maven-metadata.xml"
    is_plugin = name.endswith(".gradle.plugin")
    for base in ([PLUGIN_PORTAL, CENTRAL] if is_plugin else [GOOGLE, CENTRAL]):
        host = urlparse(base).hostname
        if host in down_hosts:
            continue
        try:
            xml = http(f"{base}/{path}")
        except HttpError as e:
            if e.status != 404:
                problems.add(f"{host}: {e}")
            continue
        except Exception as e:  # noqa: BLE001
            down_hosts.add(host)
            problems.add(f"{host}: {e}")
            continue
        try:
            return [v.text for v in ET.fromstring(xml).iter("version") if v.text]
        except ET.ParseError:
            problems.add(f"{host}: maven-metadata.xml illeggibile per {group}:{name}")
    return None


def check_versions(catalog, http, out):
    """Segnala con ::notice:: le versioni del catalogo con un aggiornamento disponibile."""
    down_hosts, problems, unknown = set(), set(), []
    for ref, current, artifacts in catalog_entries(catalog):
        available = None
        for group, name in artifacts:
            available = published_versions(group, name, http, down_hosts, problems)
            if available:
                break
        if not available:
            unknown.append(ref)
            continue
        newer = newer_version(current, available)
        if newer:
            group, name = artifacts[0]
            out(f"::notice::{ref}: {current} -> {newer} disponibile ({group}:{name})")
    for p in sorted(problems):
        out(f"::warning::Controllo delle versioni incompleto, {p}")
    if unknown and not problems:
        out(f"::warning::Versioni non trovate nei repository: {', '.join(unknown)}")


# ---------------------------------------------------------------------------


def run(report_paths, catalog_path, http=http_request, out=print):
    """Esegue entrambi i controlli e restituisce il codice di uscita."""
    reports = {}
    for p in report_paths:
        path = Path(p)
        reports[path.stem] = parse_report(path.read_text(encoding="utf-8", errors="replace"))
    total = len({c for coords in reports.values() for c in coords})
    out(f"Coordinate risolte: {total} in {len(reports)} report")
    vulnerable = check_vulnerabilities(reports, http, out)
    catalog = tomllib.loads(Path(catalog_path).read_text(encoding="utf-8"))
    check_versions(catalog, http, out)
    if vulnerable:
        out(f"Dipendenze con vulnerabilita' note: {vulnerable}")
        return 1
    out("Nessuna vulnerabilita' nota nelle dipendenze risolte")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--catalog", default="gradle/libs.versions.toml")
    parser.add_argument("reports", nargs="+", help="output di `gradle dependencies`")
    args = parser.parse_args(argv)
    return run(args.reports, args.catalog)


if __name__ == "__main__":
    sys.exit(main())
