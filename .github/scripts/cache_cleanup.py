#!/usr/bin/env python3
"""Pulizia delle cache di GitHub Actions del repository (workflow cache-cleanup.yml).

GitHub toglie da solo le voci non usate da 7 giorni e, oltre i 10 GB, le meno usate di recente; lo spazio si
spreca con le copie superate della stessa famiglia (una chiave nuova a ogni run), che vicino al limite farebbero
cadere voci utili come le dipendenze di Gradle o i dump di Wikivoyage. Regole:

- famiglia = la chiave senza la parte che cambia a ogni salvataggio (hash, data, numero della run);
- di ogni famiglia restano le 2 voci usate piu' di recente (lastAccessedAt);
- le voci di una singola run (chiave che finisce con <run id>-<tentativo>) si tolgono dopo 3 giorni;
- le voci di ref diversi da refs/heads/main si tolgono;
- la voce piu' recente di una famiglia di main non si tocca mai, anche se vecchia.

Uso: cache_cleanup.py [--dry-run]. Ambiente: GITHUB_REPOSITORY, GH_TOKEN (actions: write), GITHUB_STEP_SUMMARY
(facoltativo, per il riepilogo).
"""

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone

MAIN_REF = "refs/heads/main"
KEEP_PER_FAMILY = 2
PER_RUN_MAX_AGE = timedelta(days=3)
# Chiave per singola run: <famiglia>-<run id>-<tentativo> (city-facts-0-38718619106-1).
PER_RUN = re.compile(r"^(?P<family>.+)-\d{9,}-\d+$")
# Parti finali che cambiano a ogni salvataggio: hash (anche con il prefisso della lingua, come i dump di Wikivoyage
# "it<hash>-en<hash>") e date di rilascio (overture-check-2026-09-23.1).
VARIABLE_SUFFIX = re.compile(r"(-(?:[a-z]{2})?[0-9a-f]{12,}|-\d{4}-\d{2}-\d{2}(?:\.\d+)?)+$")


def family_of(key):
    """(famiglia, voce per singola run?)"""
    m = PER_RUN.match(key)
    if m:
        return m.group("family"), True
    return VARIABLE_SUFFIX.sub("", key), False


def parse_time(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def plan(entries, now):
    """Voci da cancellare, ognuna con il motivo: lista di (voce, motivo), nell'ordine di entries."""
    reasons = {}
    families = {}
    for e in entries:
        if e["ref"] != MAIN_REF:
            reasons[e["id"]] = f"ref {e['ref']}"
            continue
        families.setdefault(family_of(e["key"])[0], []).append(e)
    for family, members in families.items():
        members.sort(key=lambda e: parse_time(e["lastAccessedAt"]), reverse=True)
        for i, e in enumerate(members):
            if i == 0:
                continue  # la piu' recente della famiglia resta sempre
            if i >= KEEP_PER_FAMILY:
                reasons[e["id"]] = f"oltre le {KEEP_PER_FAMILY} piu' recenti di {family}"
            elif family_of(e["key"])[1] and now - parse_time(e["createdAt"]) > PER_RUN_MAX_AGE:
                reasons[e["id"]] = f"voce di una sola run con piu' di {PER_RUN_MAX_AGE.days} giorni"
    return [(e, reasons[e["id"]]) for e in entries if e["id"] in reasons]


def gh(*args):
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def usage(repo):
    data = json.loads(gh("api", f"repos/{repo}/actions/cache/usage"))
    return data["active_caches_count"], data["active_caches_size_in_bytes"]


def cell(text):
    return text.replace("|", r"\|")


def mb(size):
    return f"{size / 1048576:,.0f} MB".replace(",", ".")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dry-run", action="store_true", help="elenca le voci da cancellare senza cancellarle")
    args = parser.parse_args()
    repo = os.environ["GITHUB_REPOSITORY"]

    count_before, size_before = usage(repo)
    entries = json.loads(gh("cache", "list", "--repo", repo, "--limit", "1000",
                            "--json", "id,key,ref,sizeInBytes,createdAt,lastAccessedAt"))
    to_delete = plan(entries, datetime.now(timezone.utc))

    failed = []
    if not args.dry_run:
        for e, _ in to_delete:
            try:
                gh("cache", "delete", str(e["id"]), "--repo", repo)
            except subprocess.CalledProcessError as err:
                failed.append(e["key"])
                print(f"::warning::cache {e['key']} non cancellata: {err.stderr.strip()}")
    deleted = [(e, why) for e, why in to_delete if e["key"] not in failed]
    freed = sum(e["sizeInBytes"] for e, _ in deleted)

    if args.dry_run:
        count_after, size_after = count_before - len(deleted), size_before - freed
        title = "### Pulizia delle cache (prova, nessuna voce cancellata)"
    else:
        count_after, size_after = usage(repo)
        title = "### Pulizia delle cache"
    lines = [
        title,
        "",
        f"Prima: {count_before} voci, {mb(size_before)}. "
        f"{'Dopo la pulizia (stima)' if args.dry_run else 'Dopo'}: {count_after} voci, {mb(size_after)}.",
        "",
    ]
    if deleted:
        lines += ["| Voce | Ref | Dimensione | Ultimo uso | Motivo |", "|---|---|---|---|---|"]
        # "|" nelle chiavi di Gradle (gradle-home-v2|Linux-X64|...) chiuderebbe la cella della tabella.
        lines += [f"| `{cell(e['key'])}` | {e['ref']} | {mb(e['sizeInBytes'])} | {e['lastAccessedAt'][:16]} | {cell(why)} |"
                  for e, why in deleted]
    else:
        lines.append("Nessuna voce da cancellare.")
    if failed:
        lines += ["", "Non cancellate (riprova la prossima run): " + ", ".join(f"`{k}`" for k in failed)]
    text = "\n".join(lines) + "\n"
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
