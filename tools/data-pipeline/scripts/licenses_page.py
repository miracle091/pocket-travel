#!/usr/bin/env python3
"""Pagine delle licenze di Pocket Travel su GitHub Pages, collegate dal README.

Uso: python3 licenses_page.py <cartella del sito>

Scrive licenses.html (italiano) e licenses-en.html (inglese) nella cartella del sito: software, modelli e
dati fissi dalle tabelle qui sotto (da tenere allineate alla schermata Licenze dell'app, LicenseData.kt),
fonti dei numeri civici e reti dei mezzi pubblici da address-grid.json e transit.json della stessa
pubblicazione. Un indice mancante lascia vuota la sua sezione.
"""

import datetime
import html
import json
import os
import sys

REPO_URL = "https://github.com/miracle091/pocket-travel"
MONTHS_IT = ["gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic"]
MONTHS_EN = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

MIT = ("MIT", "https://opensource.org/license/mit")
APACHE = ("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")
BSD2 = ("BSD-2-Clause", "https://opensource.org/license/bsd-2-clause")
BSD3 = ("BSD-3-Clause", "https://opensource.org/license/bsd-3-clause")
ZERO_BSD = ("0BSD", "https://opensource.org/license/0bsd")
UNLICENSE = ("Unlicense", "https://unlicense.org/")
OFL = ("SIL OFL 1.1", "https://openfontlicense.org/open-font-license-official-text/")
CC_BY_SA = ("CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/")
CC_BY = ("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")
CC0 = ("CC0 1.0", "https://creativecommons.org/publicdomain/zero/1.0/")
ODBL = ("ODbL 1.0", "https://opendatacommons.org/licenses/odbl/1-0/")
OGL_CANADA = ("OGL - Canada 2.0", "https://open.canada.ca/en/open-government-licence-canada")
OGL_UK = ("OGL v3.0", "https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/")
LICENCE_OUVERTE = ("Licence Ouverte 2.0", "https://www.etalab.gouv.fr/licence-ouverte-open-licence/")

# Note di attribuzione richieste dalle Open Government Licence, uguali nelle due lingue.
OGL_NOTES = [
    "Contains information licensed under the Open Government Licence – Canada.",
    "Contains public sector information licensed under the Open Government Licence v3.0.",
]

TEXTS = {
    "it": {
        "file": "licenses.html",
        "title": "Licenze di Pocket Travel",
        "intro": "Il codice di Pocket Travel è sotto {mit}. Software, modelli e dati di terze parti hanno le "
                 "licenze qui sotto; fonti dei numeri civici e reti dei mezzi pubblici sono quelle dei dati "
                 "pubblicati il {date}.",
        "mit": "licenza MIT",
        "other": "English",
        "software_title": "Software e modelli", "components": "Componenti",
        "software": [
            (MIT, "BRouter, llama.cpp, nlohmann/json, cpp-httplib"),
            (APACHE, "Jetpack (Compose, Room, WorkManager, Hilt), kotlinx.serialization, OkHttp, Planetiler, "
                     "KleidiAI, icone Material Symbols, modelli Qwen3.5 e Qwen3"),
            (BSD2, "MapLibre GL"),
            (BSD3, "pmtiles-reader, basemap Protomaps"),
            (ZERO_BSD, "XZ for Java"),
            (UNLICENSE, "subprocess.h"),
            (OFL, "font Noto Sans (etichette della mappa)"),
            (CC_BY_SA, "modelli Pocket Travel, addestrati su testi di Wikivoyage e Wikipedia"),
            (("Pubblico dominio", None), "Natural Earth (confini), OurAirports (aeroporti), bandiere (in gran parte)"),
        ],
        "data_title": "Dati", "use": "Uso nell'app",
        "data": [
            (("OpenStreetMap", "https://www.openstreetmap.org/copyright"), ODBL, "mappe, punti di interesse, civici, ambasciate"),
            (("Wikivoyage", "https://www.wikivoyage.org"), CC_BY_SA, "guide (adattate, alcune sezioni tradotte automaticamente)"),
            (("Wikipedia", "https://www.wikipedia.org"), CC_BY_SA, "storia e clima delle città"),
            (("Wikidata", "https://www.wikidata.org"), CC0, "fatti rapidi, ambasciate, compagnie aeree"),
            (("Open-Meteo e GeoNames", "https://open-meteo.com"), CC_BY, "meteo e coordinate delle città, uso non commerciale"),
            (("Travel.gc.ca", "https://travel.gc.ca"), OGL_CANADA, "numeri di emergenza, consigli di viaggio, vaccinazioni"),
            (("gov.uk (FCDO)", "https://www.gov.uk/foreign-travel-advice"), OGL_UK,
             "numeri di emergenza, consigli di viaggio di Palestina, Pitcairn e Wallis e Futuna, requisiti polio"),
            (("TravelHealthPro (UKHSA, NaTHNaC)", "https://travelhealthpro.org.uk"), OGL_UK, "vaccinazioni"),
            (("France Diplomatie", "https://www.diplomatie.gouv.fr/fr/conseils-aux-voyageurs/"), LICENCE_OUVERTE,
             "requisito polio dell'Egitto"),
        ],
        "gov_note": "I testi governativi sono adattati e riscritti, e non sono approvati dai governi che li pubblicano.",
        "addr": "Numeri civici",
        "addr_text": "Da OpenStreetMap e dai registri ufficiali raccolti da Overture Maps; le attribuzioni "
                     "complete sono nella schermata Licenze dell'app.",
        "transit": "Mezzi pubblici",
        "transit_text": "Dai feed GTFS degli enti di trasporto, con una licenza aperta; le attribuzioni "
                        "complete sono nella schermata Licenze dell'app e sotto il tabellone delle partenze.",
        "source": "Fonte", "license": "Licenza", "license_text": "Testo della licenza",
        "empty": "Nessun dato pubblicato",
    },
    "en": {
        "file": "licenses-en.html",
        "title": "Pocket Travel licenses",
        "intro": "The Pocket Travel code is under the {mit}. Third-party software, models and data have the "
                 "licenses below; the house number sources and public transport networks are those of the data "
                 "published on {date}.",
        "mit": "MIT license",
        "other": "Italiano",
        "software_title": "Software and models", "components": "Components",
        "software": [
            (MIT, "BRouter, llama.cpp, nlohmann/json, cpp-httplib"),
            (APACHE, "Jetpack (Compose, Room, WorkManager, Hilt), kotlinx.serialization, OkHttp, Planetiler, "
                     "KleidiAI, Material Symbols icons, Qwen3.5 and Qwen3 models"),
            (BSD2, "MapLibre GL"),
            (BSD3, "pmtiles-reader, Protomaps basemap"),
            (ZERO_BSD, "XZ for Java"),
            (UNLICENSE, "subprocess.h"),
            (OFL, "Noto Sans font (map labels)"),
            (CC_BY_SA, "Pocket Travel models, trained on Wikivoyage and Wikipedia texts"),
            (("Public domain", None), "Natural Earth (borders), OurAirports (airports), flags (mostly)"),
        ],
        "data_title": "Data", "use": "Use in the app",
        "data": [
            (("OpenStreetMap", "https://www.openstreetmap.org/copyright"), ODBL, "maps, points of interest, house numbers, embassies"),
            (("Wikivoyage", "https://www.wikivoyage.org"), CC_BY_SA, "guides (adapted, some sections machine-translated)"),
            (("Wikipedia", "https://www.wikipedia.org"), CC_BY_SA, "city history and climate"),
            (("Wikidata", "https://www.wikidata.org"), CC0, "quick facts, embassies, airlines"),
            (("Open-Meteo and GeoNames", "https://open-meteo.com"), CC_BY, "weather and city coordinates, non-commercial use"),
            (("Travel.gc.ca", "https://travel.gc.ca"), OGL_CANADA, "emergency numbers, travel advice, vaccinations"),
            (("gov.uk (FCDO)", "https://www.gov.uk/foreign-travel-advice"), OGL_UK,
             "emergency numbers, travel advice for Palestine, Pitcairn and Wallis and Futuna, polio requirements"),
            (("TravelHealthPro (UKHSA, NaTHNaC)", "https://travelhealthpro.org.uk"), OGL_UK, "vaccinations"),
            (("France Diplomatie", "https://www.diplomatie.gouv.fr/fr/conseils-aux-voyageurs/"), LICENCE_OUVERTE,
             "Egypt polio requirement"),
        ],
        "gov_note": "Government texts are adapted and rewritten, and are not endorsed by the governments that publish them.",
        "addr": "House numbers",
        "addr_text": "From OpenStreetMap and the official registries collected by Overture Maps; the full "
                     "attributions are in the app's Licenses screen.",
        "transit": "Public transport",
        "transit_text": "From the GTFS feeds of transport agencies, under an open license; the full "
                        "attributions are in the app's Licenses screen and under the departure board.",
        "source": "Source", "license": "License", "license_text": "License text",
        "empty": "No data published",
    },
}

STYLE = """
:root { color-scheme: light dark; --bg: #ffffff; --fg: #1f2328; --muted: #59636e; --line: #d1d9e0; --link: #0969da; }
@media (prefers-color-scheme: dark) { :root { --bg: #0d1117; --fg: #e6edf3; --muted: #9198a1; --line: #3d444d; --link: #4493f8; } }
body { margin: 0 auto; max-width: 1100px; padding: 24px 16px; background: var(--bg); color: var(--fg);
       font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
a { color: var(--link); }
p { color: var(--muted); }
.table { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; }
th, td { border-bottom: 1px solid var(--line); padding: 6px 8px; text-align: left; vertical-align: top; }
details { border-bottom: 1px solid var(--line); padding: 6px 0; }
summary { cursor: pointer; }
summary img { width: 20px; vertical-align: -3px; margin-right: 6px; }
details p { margin: 6px 0 0 20px; }
"""


def load(site_dir, name):
    path = os.path.join(site_dir, name)
    if not os.path.isfile(path) or os.path.getsize(path) == 0:
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def country_code(region_id, regions):
    """Codice del paese di una regione; una nazione divisa in regioni (es. "francia") prende quello delle sue parti."""
    if region_id in regions:
        return regions[region_id]
    for rid, code in regions.items():
        if rid.startswith(region_id + "-"):
            return code
    return ""


def short_source(source):
    """Nome di una fonte dei civici senza la nota di attribuzione: "(c) BEV; CC BY 4.0; modificato" -> "BEV"."""
    name = source.split(";")[0].strip()
    for prefix in ("(c)", "©"):
        if name.lower().startswith(prefix):
            name = name[len(prefix):].strip()
    return name


def license_url(license_id, source_url):
    """Pagina di una licenza dei civici: SPDX per gli identificativi standard, la pagina della fonte (per
    Overture quella delle attribuzioni, con i termini di ogni registro) per LicenseRef-* e "varie"."""
    if not license_id or license_id.startswith("LicenseRef-") or license_id == "varie":
        return source_url
    return f"https://spdx.org/licenses/{license_id}.html"


def format_date(day, lang):
    if lang == "it":
        return f"{day.day} {MONTHS_IT[day.month - 1]} {day.year}"
    return f"{MONTHS_EN[day.month - 1]} {day.day}, {day.year}"


def link(text, url):
    if not url:
        return html.escape(text)
    return f'<a href="{html.escape(url)}">{html.escape(text)}</a>'


def build_page(lang, attributions, feeds, regions, day):
    t = TEXTS[lang]
    other = TEXTS["en" if lang == "it" else "it"]
    mit = link(t["mit"], f"{REPO_URL}/blob/main/LICENSE")
    out = [
        "<!doctype html>",
        f'<html lang="{lang}"><head><meta charset="utf-8">',
        '<meta name="viewport" content="width=device-width, initial-scale=1">',
        f"<title>{html.escape(t['title'])}</title><style>{STYLE}</style></head><body>",
        f"<h1>{html.escape(t['title'])}</h1>",
        f'<p><a href="{other["file"]}">{other["other"]}</a></p>',
        f"<p>{t['intro'].format(date=format_date(day, lang), mit=mit)}</p>",
        f"<h2>{t['software_title']}</h2>",
        f'<div class="table"><table><tr><th>{t["license"]}</th><th>{t["components"]}</th></tr>',
    ]
    for lic, components in t["software"]:
        out.append(f"<tr><td>{link(*lic)}</td><td>{html.escape(components)}</td></tr>")
    out += ["</table></div>", f"<h2>{t['data_title']}</h2>",
            f'<div class="table"><table><tr><th>{t["source"]}</th><th>{t["license"]}</th><th>{t["use"]}</th></tr>']
    for source, lic, use in t["data"]:
        out.append(f"<tr><td>{link(*source)}</td><td>{link(*lic)}</td><td>{html.escape(use)}</td></tr>")
    out.append("</table></div>")
    out += [f"<p>{html.escape(n)}</p>" for n in OGL_NOTES + [t["gov_note"]]]
    out.append(f"<h2>{t['addr']} ({len(attributions)})</h2><p>{t['addr_text']}</p>")
    if attributions:
        by_license = {}
        source_urls = {}
        for a in attributions:
            lic = a.get("license", "")
            names = by_license.setdefault(lic, [])
            name = short_source(a.get("source", ""))
            if name not in names:
                names.append(name)
            source_urls.setdefault(lic, a.get("url"))
        for lic, names in sorted(by_license.items(), key=lambda r: (-len(r[1]), r[0])):
            url = license_url(lic, source_urls[lic])
            text = f"{link(t['license_text'], url)} · " if url else ""
            out.append(f"<details><summary><strong>{html.escape(lic)}</strong> ({len(names)})</summary>"
                       f"<p>{text}{html.escape(', '.join(names))}</p></details>")
    else:
        out.append(f"<p>{t['empty']}</p>")
    out.append(f"<h2>{t['transit']} ({len(feeds)})</h2><p>{t['transit_text']}</p>")
    if feeds:
        by_country = {}
        for f in feeds:
            by_country.setdefault(country_code((f.get("regions") or [""])[0], regions), []).append(f)
        for code in sorted(by_country):
            flag = f'<img src="assets/flags/{html.escape(code)}.svg" alt="">' if code else ""
            networks = ", ".join(
                f"{html.escape(f.get('name', ''))} ({link(f.get('license', ''), f.get('licenseUrl'))})"
                for f in sorted(by_country[code], key=lambda f: f.get("name", "").lower()))
            out.append(f"<details><summary>{flag}<strong>{html.escape(code.upper())}</strong> "
                       f"({len(by_country[code])})</summary><p>{networks}</p></details>")
    else:
        out.append(f"<p>{t['empty']}</p>")
    out.append("</body></html>")
    return "\n".join(out) + "\n"


def main(argv):
    if len(argv) != 2:
        print("Uso: licenses_page.py <cartella del sito>", file=sys.stderr)
        return 2
    site_dir = argv[1]
    manifest = load(site_dir, "manifest.json") or {}
    regions = {r["regionId"]: r.get("countryCode", "") for r in manifest.get("regions", [])}
    attributions = (load(site_dir, "address-grid.json") or {}).get("attributions", [])
    feeds = (load(site_dir, "transit.json") or {}).get("feeds", [])
    day = datetime.datetime.now(datetime.timezone.utc).date()
    for lang in ("it", "en"):
        with open(os.path.join(site_dir, TEXTS[lang]["file"]), "w", encoding="utf-8", newline="\n") as f:
            f.write(build_page(lang, attributions, feeds, regions, day))
    print(f"licenze: {len(attributions)} fonti dei civici, {len(feeds)} reti")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
