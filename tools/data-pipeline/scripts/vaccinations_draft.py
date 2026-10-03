#!/usr/bin/env python3
"""Bozza dei dati vaccinali da Travel.gc.ca (Governo del Canada, Open Government Licence - Canada).

Scarica (con cache su disco) il JSON open data di ogni paese e ne estrae dal campo "health":
- il blocco "Yellow Fever - Country Entry Requirements" (rischio, requisito d'ingresso, raccomandazione);
- il blocco "Polio" (categoria OMS dichiarata);
- i titoli dei blocchi dei vaccini raccomandati, con le frasi che ne indicano il livello.

Produce, nella cartella di output, vaccinations-draft.json (i blocchi in chiaro, per la revisione a
mano) e le bozze yf-entry.draft.tsv, polio-status.draft.tsv, recommended.draft.tsv.
Le bozze NON vanno copiate cosi' nel repository: i TSV in content/src/main/resources/vaccinations/
sono curati a mano (note con parole nostre) dopo il confronto con TravelHealthPro (OGL v3) e, solo
come controllo, con OMS e CDC.

Uso: python vaccinations_draft.py <cartella-cache> <cartella-output>
"""
import html
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = "https://data.international.gc.ca/travel-voyage/"
USER_AGENT = "pocket-travel-data-pipeline/1.0 (+https://github.com/miracle091/pocket-travel; vaccinations draft, sequential requests)"
DELAY_SECONDS = 0.7

# Titolo del blocco Travel.gc.ca -> codice vaccino di recommended.tsv.
VACCINE_TITLES = {
    "hepatitis a": "HEPA",
    "hepatitis b": "HEPB",
    "typhoid": "TYPHOID",
    "rabies": "RABIES",
    "japanese encephalitis": "JE",
    "tick-borne encephalitis": "TBE",
    "cholera": "CHOLERA",
    "meningococcal": "MENACWY",
    "meningitis": "MENACWY",
    "dengue": "DENGUE",
    "chikungunya": "CHIK",
}


def fetch(name: str, cache: Path) -> str | None:
    target = cache / name
    if target.exists():
        return target.read_text(encoding="utf-8")
    request = urllib.request.Request(BASE + name, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        if error.code == 404:
            target.write_text("", encoding="utf-8")
            return None
        raise
    target.write_text(body, encoding="utf-8")
    time.sleep(DELAY_SECONDS)
    return body


def text_of(fragment: str) -> str:
    fragment = re.sub(r"<(br|/p|/li|/h3)[^>]*>", "\n", fragment)
    fragment = re.sub(r"<[^>]+>", "", fragment)
    fragment = html.unescape(fragment).replace("\xa0", " ")
    return re.sub(r"[ \t]+", " ", re.sub(r"\n\s*\n+", "\n", fragment)).strip()


def blocks_of(health: str) -> dict[str, str]:
    result = {}
    for match in re.finditer(r'<details[^>]*>\s*<summary[^>]*>(.*?)</summary>(.*?)</details>', health, re.S):
        result[text_of(match.group(1))] = text_of(match.group(2))
    return result


def yf_parts(block: str) -> dict[str, str]:
    parts = {"risk": "", "entry": "", "recommendation": ""}
    sections = re.split(r"^(Risk|Country Entry Requirement\*?|Recommendation)\s*$", block, flags=re.M)
    for index in range(1, len(sections) - 1, 2):
        name = sections[index].lower()
        key = "risk" if name.startswith("risk") else "entry" if name.startswith("country") else "recommendation"
        parts[key] = sections[index + 1].strip()
    return parts


def main() -> None:
    cache = Path(sys.argv[1])
    out = Path(sys.argv[2])
    cache.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    index = json.loads(fetch("index-alpha-eng.json", cache))["data"]
    countries = sorted(code.lower() for code in index if "-" not in code)
    drafts = {}
    for iso2 in countries:
        body = fetch(f"cta-cap-{iso2}.json", cache)
        if not body:
            continue
        data = json.loads(body)["data"]
        health = data["eng"].get("health") or ""
        blocks = blocks_of(health)
        yf_block = next((v for k, v in blocks.items() if k.lower().startswith("yellow fever")), "")
        polio_block = next((v for k, v in blocks.items() if k.lower().startswith("polio")), "")
        vaccines = {}
        for title, block in blocks.items():
            for key, code in VACCINE_TITLES.items():
                if title.lower().startswith(key):
                    vaccines[code] = block
        drafts[iso2] = {
            "name": data["eng"].get("name"),
            "published": data.get("date-published", {}).get("date") if isinstance(data.get("date-published"), dict) else None,
            "yf": yf_parts(yf_block) if yf_block else None,
            "polio": polio_block,
            "vaccines": vaccines,
            "otherTitles": [t for t in blocks if t not in ("",)],
        }
    (out / "vaccinations-draft.json").write_text(json.dumps(drafts, ensure_ascii=False, indent=1), encoding="utf-8")

    with open(out / "yf-entry.draft.tsv", "w", encoding="utf-8") as f:
        f.write("iso2\trisk\tentry\n")
        for iso2, d in drafts.items():
            yf = d["yf"]
            f.write(f"{iso2}\t{(yf['risk'] if yf else 'NO-BLOCK').replace(chr(10), ' / ')}\t{(yf['entry'] if yf else '').replace(chr(10), ' / ')}\n")
    with open(out / "polio-status.draft.tsv", "w", encoding="utf-8") as f:
        f.write("iso2\tpolio-first-paragraph\n")
        for iso2, d in drafts.items():
            f.write(f"{iso2}\t{d['polio'].split(chr(10))[0] if d['polio'] else ''}\n")
    with open(out / "recommended.draft.tsv", "w", encoding="utf-8") as f:
        f.write("iso2\tvaccine\tfirst-lines\n")
        for iso2, d in drafts.items():
            for code, block in d["vaccines"].items():
                f.write(f"{iso2}\t{code}\t{block.replace(chr(10), ' / ')[:600]}\n")
    print(f"{len(drafts)} paesi scaricati")


if __name__ == "__main__":
    main()
