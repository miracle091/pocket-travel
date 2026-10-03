#!/usr/bin/env python3
"""Controllo deriva dei dati vaccinali: dice se le fonti sono cambiate dopo l'ultima curatela dei TSV in
content/src/main/resources/vaccinations/. Non modifica mai i TSV: chi li aggiorna lo fa a mano.

Confronta con l'istantanea vaccinations-drift.tsv (accanto a transit-feeds.tsv):
- Travel.gc.ca (Open Government Licence - Canada), per ogni paese: rischio e requisito d'ingresso della
  febbre gialla, primo paragrafo del blocco polio, elenco dei vaccini con un blocco proprio;
- l'hash del PDF dei requisiti sanitari per Hajj e Umrah del Ministero della salute saudita.
Controlla inoltre che la pagina del Polio IHR Emergency Committee dell'OMS non elenchi uno statement
piu' recente di quello in polio-status.tsv (colonna statement, "IHR EC <numero>, <data>").

Uso:
  python vaccinations_drift.py [--report FILE] [--cache DIR]   controllo; uscita 2 se trova differenze
  python vaccinations_drift.py --update [--cache DIR]          riscrive l'istantanea dopo la curatela
Il resoconto (Markdown) va su stdout e, con --report, anche nel file indicato.
"""
import argparse
import hashlib
import json
import re
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

import vaccinations_draft as vd

PIPELINE = Path(__file__).resolve().parent.parent
BASELINE = PIPELINE / "vaccinations-drift.tsv"
POLIO_STATUS = PIPELINE / "content/src/main/resources/vaccinations/polio-status.tsv"
WHO_COMMITTEE = "https://www.who.int/groups/poliovirus-ihr-emergency-committee"
HAJJ_PDF = "https://www.moh.gov.sa/HealthAwareness/Pilgrims-Health/Documents/Hajj-Health-Requirements-English-language.pdf"

# Tipo di riga dell'istantanea -> TSV da ricontrollare quando cambia.
TSV_TO_CHECK = {
    "yf": "yf-entry.tsv, yf-risk.tsv",
    "polio": "polio-status.tsv, polio-entry.tsv",
    "vaccini": "recommended.tsv",
    "hajj-pdf": "special-entry.tsv, polio-entry.tsv",
}

UNITS = {"first": 1, "second": 2, "third": 3, "fourth": 4, "fifth": 5, "sixth": 6, "seventh": 7, "eighth": 8, "ninth": 9}
TEENS = {"tenth": 10, "eleventh": 11, "twelfth": 12, "thirteenth": 13, "fourteenth": 14, "fifteenth": 15,
         "sixteenth": 16, "seventeenth": 17, "eighteenth": 18, "nineteenth": 19}
TENS = {"twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90}
ROUND_TENS = {"twentieth": 20, "thirtieth": 30, "fortieth": 40, "fiftieth": 50, "sixtieth": 60, "seventieth": 70,
              "eightieth": 80, "ninetieth": 90}


def one_line(text: str) -> str:
    return re.sub(r"\s+", " ", text.replace("\n", " / ")).strip()


def fingerprints_of(iso2: str, health: str) -> dict[tuple[str, str], str]:
    """Le frasi di una scheda Travel.gc.ca che i TSV riassumono, una per tipo."""
    blocks = vd.blocks_of(health)
    yf_block = next((v for k, v in blocks.items() if k.lower().startswith("yellow fever")), "")
    polio_block = next((v for k, v in blocks.items() if k.lower().startswith("polio")), "")
    vaccines = {code for title in blocks for key, code in vd.VACCINE_TITLES.items() if title.lower().startswith(key)}
    yf = vd.yf_parts(yf_block) if yf_block else {"risk": "", "entry": ""}
    return {
        ("yf", iso2): one_line(f"{yf['risk']} | {yf['entry']}") if yf_block else "",
        ("polio", iso2): one_line(polio_block.split("\n")[0]) if polio_block else "",
        ("vaccini", iso2): " ".join(sorted(vaccines)),
    }


def download(url: str) -> bytes | None:
    request = urllib.request.Request(url, headers={"User-Agent": vd.USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def collect(cache: Path) -> dict[tuple[str, str], str]:
    index = json.loads(vd.fetch("index-alpha-eng.json", cache))["data"]
    result = {}
    for iso2 in sorted(code.lower() for code in index if "-" not in code):
        body = vd.fetch(f"cta-cap-{iso2}.json", cache)
        if body:
            result.update(fingerprints_of(iso2, json.loads(body)["data"]["eng"].get("health") or ""))
    pdf = download(HAJJ_PDF)
    result[("hajj-pdf", "sa")] = hashlib.sha256(pdf).hexdigest() if pdf is not None else "HTTP 404"
    return result


def read_baseline(path: Path) -> dict[tuple[str, str], str]:
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            kind, key, value = (line.split("\t") + ["", ""])[:3]
            result[(kind, key)] = value
    return result


def write_baseline(path: Path, values: dict[tuple[str, str], str]) -> None:
    lines = [
        "# Istantanea delle fonti dei dati vaccinali all'ultima curatela dei TSV in content/src/main/resources/vaccinations/,",
        "# letta da scripts/vaccinations_drift.py (job vaccinations-check di publish-regions). Generata, non modificare a mano: dopo aver",
        "# aggiornato i TSV rilanciare `python scripts/vaccinations_drift.py --update`.",
        "# Testi da Travel.gc.ca, Country Travel Advice and Advisories, Governo del Canada, Open Government Licence - Canada.",
        "# tipo\tchiave\tvalore",
    ]
    lines += [f"{kind}\t{key}\t{value}" for (kind, key), value in sorted(values.items())]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def compare(old: dict[tuple[str, str], str], new: dict[tuple[str, str], str]) -> list[str]:
    """Una voce Markdown per ogni chiave cambiata, comparsa o sparita."""
    entries = []
    for kind, key in sorted(old.keys() | new.keys()):
        before, after = old.get((kind, key)), new.get((kind, key))
        if before == after:
            continue
        label = f"**{kind} `{key}`** (ricontrollare {TSV_TO_CHECK.get(kind, '?')})"
        if before is None:
            entries.append(f"- {label}: nuovo nella fonte: {after or '(vuoto)'}")
        elif after is None:
            entries.append(f"- {label}: non piu' nella fonte (era: {before or '(vuoto)'})")
        else:
            entries.append(f"- {label}\n  - prima: {before or '(vuoto)'}\n  - ora: {after or '(vuoto)'}")
    return entries


def ordinal(word: str) -> int | None:
    word = word.lower()
    if word in UNITS or word in TEENS or word in ROUND_TENS:
        return UNITS.get(word) or TEENS.get(word) or ROUND_TENS[word]
    tens, _, unit = word.partition("-")
    return TENS[tens] + UNITS[unit] if tens in TENS and unit in UNITS else None


def meeting_numbers(page: str) -> set[int]:
    """Numeri delle riunioni citate nella pagina del comitato, in lettere ("Forty-fifth") o in cifre ("45th")."""
    numbers = set()
    for match in re.finditer(r"([a-z]+(?:-[a-z]+)?|\d+(?:st|nd|rd|th))\s+meeting of the polio ihr emergency committee",
                             page, re.I):
        word = match.group(1)
        number = int(word[:-2]) if word[0].isdigit() else ordinal(word)
        if number:
            numbers.add(number)
    return numbers


def current_statement(polio_status: Path) -> int:
    rows = [line for line in polio_status.read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
    numbers = {int(m.group(1)) for line in rows if (m := re.search(r"IHR EC (\d+)", line))}
    if len(numbers) != 1:
        raise ValueError(f"polio-status.tsv: attesi uno statement, trovati {sorted(numbers)}")
    return numbers.pop()


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--update", action="store_true")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--cache", type=Path)
    args = parser.parse_args(argv)
    cache = args.cache or Path(tempfile.mkdtemp(prefix="vaccinations-drift-"))
    cache.mkdir(parents=True, exist_ok=True)
    current = collect(cache)
    if args.update:
        write_baseline(BASELINE, current)
        print(f"{BASELINE.name}: {len(current)} righe")
        return 0

    report = []
    statement = current_statement(POLIO_STATUS)
    page = download(WHO_COMMITTEE)
    newer = sorted(n for n in meeting_numbers(page.decode("utf-8", "replace")) if n > statement) if page else []
    if page is None:
        report.append(f"- **Statement polio**: la pagina del comitato OMS non risponde ({WHO_COMMITTEE})")
    elif newer:
        report.append(f"- **Statement polio**: la pagina del comitato OMS cita la riunione {newer[-1]}, polio-status.tsv "
                      f"e' allo statement {statement}: rifare gli elenchi di polio-status.tsv ({WHO_COMMITTEE})")
    report += compare(read_baseline(BASELINE), current)
    if not report:
        print("Nessuna differenza dalle fonti.")
        return 0
    text = ("Le fonti dei dati vaccinali sono cambiate dall'ultima curatela. Ricontrollare a mano i TSV indicati in "
            "`tools/data-pipeline/content/src/main/resources/vaccinations/`, poi rilanciare "
            "`python tools/data-pipeline/scripts/vaccinations_drift.py --update` e committare TSV e istantanea.\n\n"
            + "\n".join(report) + "\n")
    print(text)
    if args.report:
        args.report.write_text(text, encoding="utf-8")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
