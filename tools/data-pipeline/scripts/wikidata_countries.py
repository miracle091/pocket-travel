#!/usr/bin/env python3
"""Dati dei paesi da Wikidata (CC0) per i Fatti rapidi, in un TSV normalizzato con una riga per regione di regions.sh.

Uso: wikidata_countries.py --out tools/data-pipeline/content/src/main/resources/countries.tsv [--user-agent UA]

Il TSV e' un file curato, letto da GenerateCountryFacts.kt per i Fatti rapidi delle guide: si rigenera a mano (non nel
job delle guide) e si rivede il diff prima del commit, cosi' una modifica sbagliata su Wikidata non arriva da sola
nelle guide. Gli errori trovati vanno in CORREZIONI, che sopravvive alle rigenerazioni.

Colonne del TSV, dopo le righe di commento "#" (campo vuoto = dato assente; piu' valori separati da "; "):
regionId, iso2, capital_it, capital_en, currency (codici ISO 4217), currency_it, currency_en, driving (right|left),
calling_code (+39, +1 340), languages_it, languages_en (lingue ufficiali), timezones (UTC+01:00, UTC-03:30: ora solare,
senza l'ora legale), emergency (numeri), plugs (lettere A-N delle prese), voltage (volt).

Una regione eredita i dati del paese del suo flagCode (ottavo campo di regions.sh). Le regioni che sono una parte del
paese (flagCode condiviso con altre regioni: stati USA, province di Canada e Cina, Russia europea, Francia
metropolitana, Bonaire e Sint Eustatius e Saba; o le Canarie, flagCode "ic", codice riservato che su Wikidata non e'
un paese e che eredita dalla Spagna) prendono capitale e fusi dall'elemento della loro pagina Wikivoyage EN (titolo
di regions.sh), se li ha: senza una capitale propria restano senza capitale, senza fusi propri prendono quelli del
paese; gli altri campi restano quelli del paese. Il paese di un flagCode e' l'elemento con quel
codice ISO 3166-1 alpha-2 (P297) senza data di fine (P576); se sono piu' d'uno, quello col Q-id piu' basso.

Di ogni proprieta' si tengono gli enunciati di rango preferito, o normale se non ce ne sono, senza data di fine
passata (P582). Le etichette sono italiane e inglesi, con ripiego sull'etichetta multilingue ("mul") e, per
l'italiano, sull'inglese. Normalizzazioni:
- fusi (P421): su Wikidata convivono "UTC+01:00", nomi ("Central European Time", con lo scarto in P2907) e nomi
  IANA ("Europe/Rome", spesso senza P2907, risolti con zoneinfo); si scartano le ore legali, sia come fuso a se'
  ("Central European Summer Time") sia come enunciato valido in estate (qualificatore P1264 = Q36669, ora legale);
- prese (P2853): nomi di norme diverse ("Europlug", "Schuko", "NEMA 5-15", "BS 1363") diventano le lettere della
  classificazione IEC, dall'alias "Type X" o dalla tabella PRESE;
- prefisso (P474): "+" e gruppi di cifre separati da uno spazio, senza lo "00" iniziale; numeri di emergenza (P2852): l'etichetta, se e'
  un numero; tensione (P2884): volt interi plausibili.
Le religioni non ci sono: su Wikidata le hanno solo 25-29 paesi.

Esce con codice 1 se Wikidata non risponde o il risultato e' sospettosamente piccolo.
"""

import argparse
import os
import re
import sys
import time
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wikidata_missions  # noqa: E402
from wikidata_missions import pulisci, qid, qid_key  # noqa: E402

USER_AGENT = "pocket-travel-countries/1.0 (https://github.com/miracle091/pocket-travel)"
REGIONS_SH = Path(__file__).with_name("regions.sh")
# Sotto questa soglia di regioni con la capitale il risultato e' un errore (reali: circa 360 regioni).
MIN_ROWS = 300
DEADLINE_SECONDS = 15 * 60
SEP = "; "

# flagCode che non sono un paese su Wikidata -> paese da cui ereditano.
PAESE_DEL_FLAG = {"ic": "es"}
GUIDA = {"Q14565199": "right", "Q11920728": "left"}
# Lettere delle norme senza alias "Type X" su Wikidata (nomi in minuscolo). BS 546 copre le prese D (5 A) e M (15 A).
PRESE = {
    "europlug": "C", "cee 7/16": "C", "schuko": "F", "cee 7/4": "F", "cee 7/6": "E", "cee 7/7": "EF",
    "nema 1-15": "A", "nema 5-15": "B", "bs 1363": "G", "bs 546": "DM", "as/nzs 3112": "I", "as 3112": "I",
    "sn 441011": "J", "cei 23-50": "L", "iec 60906-1": "N", "si 32": "H",
}
TIPO_PRESA = re.compile(r"\btype ([a-n])\b", re.I)
# Fusi con uno scarto sbagliato su Wikidata (Greenwich Mean Time ha P2907 = 12): scarto in minuti.
FUSI_NOTI = {"Q30192": 0}
UTC = re.compile(r"(?:UTC|GMT)\s*(?:([+\-−±])\s*(\d{1,2})(?::?(\d{2}))?)?")
IANA = re.compile(r"[A-Z][A-Za-z_-]+(?:/[A-Za-z_ -]+)+")
ORA_LEGALE = re.compile(r"summer|daylight", re.I)
NUMERO = re.compile(r"\d[\d-]*")
QID_RE = re.compile(r"Q\d+")
COLONNE = ["regionId", "iso2", "capital_it", "capital_en", "currency", "currency_it", "currency_en", "driving",
           "calling_code", "languages_it", "languages_en", "timezones", "emergency", "plugs", "voltage"]
# Correzioni a lacune ed errori di Wikidata (verificati l'8 ottobre 2026), per codice del paese e poi per regionId: valgono
# su tutto quello che viene da Wikidata, regioni comprese. Fonti: tzdata (fusi), worldstandards.eu (prese e tensione).
# Senza capitale per scelta: Hong Kong, Macao e Tokelau non ne hanno una; le municipalita' cinesi sono citta'.
_FUSI_RUSSIA = {
    "russia-kaliningrad": "UTC+02:00", "russia-volga": "UTC+03:00; UTC+04:00", "russia-urali-europei": "UTC+04:00; UTC+05:00",
    "russia-siberia": "UTC+05:00; UTC+06:00; UTC+07:00; UTC+08:00; UTC+09:00",
    "russia-estremo-oriente": "UTC+09:00; UTC+10:00; UTC+11:00; UTC+12:00",
}
_FUSI_REGIONI = {
    # Canada: le province con un fuso "con nome" hanno su Wikidata lo scarto dell'ora legale.
    "canada-alberta": "UTC-07:00", "canada-columbia-britannica": "UTC-08:00; UTC-07:00", "canada-manitoba": "UTC-06:00",
    "canada-territori-del-nord-ovest": "UTC-07:00", "canada-ontario": "UTC-06:00; UTC-05:00",
    "canada-quebec": "UTC-05:00; UTC-04:00",
    # Stati USA con una parte in un altro fuso; l'Indiana su Wikidata non ha fusi.
    "stati-uniti-indiana": "UTC-06:00; UTC-05:00", "stati-uniti-florida": "UTC-06:00; UTC-05:00",
    "stati-uniti-kentucky": "UTC-06:00; UTC-05:00", "stati-uniti-texas": "UTC-07:00; UTC-06:00",
    "stati-uniti-kansas": "UTC-07:00; UTC-06:00", "stati-uniti-oregon": "UTC-08:00; UTC-07:00",
    "stati-uniti-distretto-di-columbia": "UTC-05:00",
    "isole-canarie": "UTC+00:00",
    # Fusi con l'ora legale come secondo valore, o cambiati (Giordania +3 tutto l'anno dal 2022, Tokelau +13).
    "israele": "UTC+02:00", "palestina": "UTC+02:00", "giordania": "UTC+03:00", "turks-caicos": "UTC-05:00",
    "isola-norfolk": "UTC+11:00", "tokelau": "UTC+13:00",
}
_PRESE = {  # codice: (prese, tensione)
    "va": ("C, F, L", "230"), "cx": ("I", "230"), "cc": ("I", "230"), "nf": ("I", "230"), "mp": ("A, B", "120"),
    "mh": ("A, B", "120"), "pn": ("I", "230"), "vi": ("A, B", "110"), "yt": ("C, E", "230"), "pf": ("C, E", "230"),
    "bl": ("C, E", "230"), "pm": ("C, E", "230"), "sx": ("A, B", "110"), "ss": ("C, D, G", "230"), "sj": ("C, F", "230"),
    "tk": ("I", "230"), "wf": ("C, E", "230"), "bonaire": ("A, B, C, F", "127; 220"), "sint-eustatius-saba": ("A, B", "110"),
}
CORREZIONI = {
    "in": {"timezones": "UTC+05:30"},
    "cl": {"timezones": "UTC-06:00; UTC-04:00; UTC-03:00"},
    "gi": {"timezones": "UTC+01:00"},
    "hk": {"timezones": "UTC+08:00"},
    "mo": {"timezones": "UTC+08:00", "currency": "MOP", "currency_it": "pataca di Macao", "currency_en": "Macanese pataca"},
    "cn": {"timezones": "UTC+08:00", "languages_it": "cinese mandarino", "languages_en": "Mandarin Chinese"},
    "ru": {"timezones": "UTC+03:00"},
    "au": {"timezones": "UTC+08:00; UTC+09:30; UTC+10:00"},
    "mx": {"timezones": "UTC-08:00; UTC-07:00; UTC-06:00; UTC-05:00"},
    "pt": {"timezones": "UTC-01:00; UTC+00:00"},
    "es": {"timezones": "UTC+01:00"},
    # Su Wikidata le lingue degli USA sono quelle dei territori; l'inglese e' ufficiale dal 2025 (ordine esecutivo 14224).
    "us": {"languages_it": "inglese", "languages_en": "English"},
    "stati-uniti-hawaii": {"languages_it": "inglese; hawaiano", "languages_en": "English; Hawaiian"},
    # Valute e fusi del Regno dei Paesi Bassi e della Francia d'oltremare, non dei paesi europei.
    "nl": {"timezones": "UTC+01:00", "currency": "EUR", "currency_it": "euro", "currency_en": "euro"},
    "fr": {"currency": "EUR", "currency_it": "euro", "currency_en": "euro"},
    "pl": {"currency": "PLN", "currency_it": "złoty", "currency_en": "złoty"},
    "zw": {"currency": "USD; ZWG", "currency_it": "dollaro degli Stati Uniti; Zimbabwe Gold",
           "currency_en": "United States dollar; Zimbabwe Gold"},
    "fo": {"currency": "DKK"},
    "pk": {"capital_it": "Islamabad", "capital_en": "Islamabad"},
    "tw": {"languages_it": "cinese mandarino", "languages_en": "Mandarin Chinese"},
    "af": {"languages_it": "pashtu; dari", "languages_en": "Pashto; Dari"},
    "gp": {"languages_it": "francese", "languages_en": "French"},
    "mq": {"languages_it": "francese", "languages_en": "French"},
    "uy": {"languages_it": "spagnolo", "languages_en": "Spanish"},
    "sj": {"capital_it": "Longyearbyen", "capital_en": "Longyearbyen", "calling_code": "+47"},
    "cx": {"calling_code": "+61"},
    "cc": {"calling_code": "+61 8 9162"},
    "nf": {"calling_code": "+672 3"},
    "cw": {"calling_code": "+599 9"},
    # Etichette italiane mancanti (ripiego sull'inglese) o sbagliate, valute di sola collezione.
    "gn": {"languages_it": "francese", "languages_en": "French"},
    "dz": {"languages_it": "arabo; tamazight", "languages_en": "Arabic; Tamazight"},
    "ve": {"currency_it": "bolívar sovrano", "currency_en": "sovereign bolívar", "languages_it": "spagnolo",
           "languages_en": "Spanish"},
    "ni": {"capital_it": "Managua"},
    "pe": {"currency_it": "sol peruviano", "currency_en": "Peruvian sol"},
    "mh": {"currency": "USD", "currency_it": "dollaro degli Stati Uniti", "currency_en": "United States dollar"},
    "ki": {"currency": "AUD", "currency_it": "dollaro australiano", "currency_en": "Australian dollar"},
    # La pagina Wikivoyage EN "Georgia_(U.S._state)" non ha un sitelink (il titolo e' un redirect).
    "stati-uniti-georgia": {"capital_it": "Atlanta", "capital_en": "Atlanta", "timezones": "UTC-05:00"},
    # Le due regioni di Kiribati hanno la pagina Wikivoyage del paese intero.
    "kiribati-gilbert": {"timezones": "UTC+12:00"},
    "kiribati-line": {"capital_it": "", "capital_en": "", "timezones": "UTC+14:00"},
    "cina-pechino": {"capital_it": "Pechino", "capital_en": "Beijing"},
    "cina-shanghai": {"capital_it": "", "capital_en": ""},
    "cina-tianjin": {"capital_it": "", "capital_en": ""},
    "cina-chongqing": {"capital_it": "", "capital_en": ""},
    **{rid: {"timezones": tz} for rid, tz in {**_FUSI_RUSSIA, **_FUSI_REGIONI}.items()},
}
for _chiave, (_prese, _volt) in _PRESE.items():
    CORREZIONI.setdefault(_chiave, {}).update({"plugs": _prese, "voltage": _volt})

# Proprieta': (variabile extra sul valore, etichette).
PROPRIETA = {
    "P36": ("", True),                                                       # capitale
    "P38": ("OPTIONAL { ?v wdt:P498 ?x }", True),                            # valuta, codice ISO 4217
    "P1622": ("", False),                                                    # lato di guida
    "P474": ("", False),                                                     # prefisso telefonico
    "P37": ("", True),                                                       # lingua ufficiale
    "P421": ("OPTIONAL { ?v wdt:P2907 ?x }", True),                          # fuso orario
    "P2852": ("", True),                                                     # numero di emergenza
    "P2853": ('OPTIONAL { ?v skos:altLabel ?x FILTER(lang(?x) = "en") }', True),  # tipo di presa
    "P2884": ("", False),                                                    # tensione di rete
}


def regioni(text):
    """[(regionId, flagCode, titolo Wikivoyage)] dalle righe di ALL_REGIONS in regions.sh (quelle di REPLACED_REGIONS
    hanno 2 campi)."""
    out = []
    for row in re.findall(r'^\s*"([^"]+\|[^"]+)"\s*$', text, re.M):
        f = row.split("|")
        if len(f) > 7 and f[7]:
            out.append((f[0], f[7].lower(), f[6]))
    return out


def query_paesi():
    return "SELECT ?c ?iso WHERE { ?c wdt:P297 ?iso . FILTER NOT EXISTS { ?c wdt:P576 ?fine } }"


def query_sitelink(titoli):
    """Elementi delle pagine di Wikivoyage EN con questi titoli (quelli di regions.sh, con "_" al posto degli spazi)."""
    values = " ".join('"%s"@en' % t.replace("_", " ").replace("\\", "\\\\").replace('"', '\\"') for t in sorted(titoli))
    return f"""
SELECT ?item ?name WHERE {{
  VALUES ?name {{ {values} }}
  ?a schema:about ?item ; schema:isPartOf <https://en.wikivoyage.org/> ; schema:name ?name .
}}"""


def query_proprieta(prop, items):
    extra, etichette = PROPRIETA[prop]
    labels = "".join(f' OPTIONAL {{ ?v rdfs:label ?{l} FILTER(lang(?{l}) = "{l}") }}' for l in ("it", "en", "mul")) \
        if etichette else ""
    values = " ".join(f"wd:{q}" for q in sorted(items, key=qid_key))
    return f"""
SELECT ?c ?v ?rank ?it ?en ?mul ?x WHERE {{
  VALUES ?c {{ {values} }}
  ?c p:{prop} ?st . ?st ps:{prop} ?v ; wikibase:rank ?rank .
  FILTER(?rank != wikibase:DeprecatedRank)
  FILTER NOT EXISTS {{ ?st pq:P582 ?fine FILTER(?fine < NOW()) }}
  FILTER NOT EXISTS {{ ?st pq:P1264 wd:Q36669 }}
  {labels} {extra}
}}"""


def scegli_paesi(rows, codici):
    """{flagCode: Q-id del paese} per i codici chiesti: a parita' di codice vince il Q-id piu' basso."""
    out = {}
    for row in sorted(rows, key=lambda r: qid_key(qid(r["c"]))):
        iso = row["iso"].lower()
        if iso in codici:
            out.setdefault(iso, qid(row["c"]))
    return out


def valori(rows):
    """{paese: [valore]} con i soli enunciati del rango migliore; un valore e' un dict con v, it, en, x (insieme)."""
    per_paese = {}
    for row in rows:
        per_paese.setdefault(qid(row["c"]), []).append(row)
    out = {}
    for c, rs in per_paese.items():
        if any(r["rank"].endswith("PreferredRank") for r in rs):
            rs = [r for r in rs if r["rank"].endswith("PreferredRank")]
        vals = {}
        for r in rs:
            v = qid(r["v"]) if r["v"].startswith("http://www.wikidata.org/entity/") else r["v"]
            en = pulisci(r.get("en")) or pulisci(r.get("mul"))
            val = vals.setdefault(v, {"v": v, "it": pulisci(r.get("it")) or en, "en": en, "x": set()})
            if r.get("x"):
                val["x"].add(r["x"])
        out[c] = sorted(vals.values(), key=lambda val: (qid_key(val["v"]) if QID_RE.fullmatch(val["v"]) else 0, val["v"]))
    return out


def unici(items):
    return SEP.join(dict.fromkeys(i for i in items if i))


def formato_fuso(minuti):
    segno = "-" if minuti < 0 else "+"
    return f"UTC{segno}{abs(minuti) // 60:02d}:{abs(minuti) % 60:02d}"


def scarto_fuso(val):
    """Scarto dell'ora solare in minuti, o None (ora legale, fuso sconosciuto)."""
    if val["v"] in FUSI_NOTI:
        return FUSI_NOTI[val["v"]]
    nome = val["en"]
    if ORA_LEGALE.search(nome):
        return None
    m = UTC.fullmatch(nome)
    if m:
        if not m.group(1):
            return 0
        minuti = int(m.group(2)) * 60 + int(m.group(3) or 0)
        return -minuti if m.group(1) in "-−" else minuti
    if IANA.fullmatch(nome):
        try:
            gennaio = datetime(2026, 1, 15, 12, tzinfo=ZoneInfo(nome.replace(" ", "_")))
            return int((gennaio.utcoffset() - gennaio.dst()).total_seconds() // 60)
        except (ZoneInfoNotFoundError, ValueError):
            pass
    # Due scarti (fusi nordamericani con nome): il minore e' l'ora solare, quella legale aggiunge un'ora.
    scarti = [float(x) for x in val["x"] if re.fullmatch(r"[+-]?\d+(\.\d+)?", x)]
    return round(min(scarti) * 60) if scarti else None


def lettere_presa(val):
    nomi = [val["en"], *sorted(val["x"])]
    lettere = {m.group(1).upper() for n in nomi for m in TIPO_PRESA.finditer(n)}
    if not lettere:
        lettere = {l for n in nomi for l in PRESE.get(n.lower().strip(), "")}
    return lettere


def prefisso(value):
    gruppi = re.findall(r"\d+", value.lstrip("+0"))
    # Piano di numerazione nordamericano: "+1787" e' "+1 787" (paese 1, prefisso 787).
    if len(gruppi) == 1 and re.fullmatch(r"1\d{3}", gruppi[0]):
        gruppi = ["1", gruppi[0][1:]]
    return "+" + " ".join(gruppi) if gruppi and len("".join(gruppi)) <= 7 else ""


def volt(value):
    try:
        v = float(value)
    except ValueError:
        return ""
    return str(int(v)) if v.is_integer() and 90 <= v <= 260 else ""


def fusi(vals, avvisi):
    scarti = set()
    for val in vals:
        scarto = scarto_fuso(val)
        if scarto is None:
            if not ORA_LEGALE.search(val["en"]):
                avvisi.add(f"fuso non riconosciuto: {val['v']} {val['en']!r}")
        else:
            scarti.add(scarto)
    return unici(formato_fuso(m) for m in sorted(scarti))


def fatti(dati, c, avvisi):
    """Campi dalla colonna capital_it in poi per il paese c."""
    def vals(prop):
        return dati[prop].get(c, [])

    prese = set()
    for val in vals("P2853"):
        lettere = lettere_presa(val)
        if not lettere:
            avvisi.add(f"presa non riconosciuta: {val['v']} {val['en']!r}")
        prese |= lettere
    for val in vals("P1622"):
        if val["v"] not in GUIDA:
            avvisi.add(f"lato di guida non riconosciuto: {val['v']}")
    return [
        unici(v["it"] for v in vals("P36")), unici(v["en"] for v in vals("P36")),
        # Codici nello stesso ordine dei nomi delle due colonne dopo: le guide li accoppiano ("loti, rand (LSL, ZAR)").
        unici(x for v in vals("P38") for x in sorted(v["x"]) if re.fullmatch(r"[A-Z]{3}", x)),
        unici(v["it"] for v in vals("P38")), unici(v["en"] for v in vals("P38")),
        unici(GUIDA.get(v["v"], "") for v in vals("P1622")),
        unici(prefisso(v["v"]) for v in vals("P474")),
        # Etichette con ":" sono categorie ("Categoria:Lingue Senufo"), non lingue.
        unici(v["it"] for v in vals("P37") if ":" not in v["it"]), unici(v["en"] for v in vals("P37") if ":" not in v["it"]),
        fusi(vals("P421"), avvisi),
        unici(v["en"] for v in vals("P2852") if NUMERO.fullmatch(v["en"])),
        ", ".join(sorted(prese)),
        unici(sorted({volt(v["v"]) for v in vals("P2884")} - {""}, key=int)),
    ]


def divise(regions):
    """{regionId: titolo} delle regioni che sono una parte del paese: flagCode condiviso o di PAESE_DEL_FLAG."""
    conta = {}
    for _, flag, _ in regions:
        conta[flag] = conta.get(flag, 0) + 1
    return {rid: titolo for rid, flag, titolo in regions if conta[flag] > 1 or flag in PAESE_DEL_FLAG}


def dati_regioni(titoli, user_agent, run, avvisi):
    """{regionId: (capital_it, capital_en, timezones)} dall'elemento della pagina Wikivoyage EN della regione; campi
    vuoti se la regione non ha l'elemento o il dato (regioni solo di Wikivoyage come "Central Russia")."""
    per_titolo = {}
    for row in sorted(run(query_sitelink(set(titoli.values())), user_agent), key=lambda r: qid_key(qid(r["item"]))):
        per_titolo.setdefault(row["name"].replace(" ", "_"), qid(row["item"]))
    elementi = set(per_titolo.values())
    capitali = valori(run(query_proprieta("P36", elementi), user_agent)) if elementi else {}
    fusi_reg = valori(run(query_proprieta("P421", elementi), user_agent)) if elementi else {}
    out = {}
    for rid, titolo in titoli.items():
        q = per_titolo.get(titolo)
        if q is None:
            avvisi.add(f"nessun elemento Wikidata per la pagina Wikivoyage EN {titolo!r} ({rid}): correggere in CORREZIONI")
        cap = capitali.get(q, [])
        out[rid] = (unici(v["it"] for v in cap), unici(v["en"] for v in cap), fusi(fusi_reg.get(q, []), avvisi))
    return out


def raccogli(regions, user_agent, run=wikidata_missions.sparql):
    """Righe (liste di 15 campi) ordinate per regionId; avvisi su stderr per fusi e prese sconosciuti."""
    codici = {PAESE_DEL_FLAG.get(flag, flag) for _, flag, _ in regions}
    paesi = scegli_paesi(run(query_paesi(), user_agent), codici)
    for code in sorted(codici - set(paesi)):
        print(f"-- nessun paese su Wikidata per il codice {code!r}", file=sys.stderr)
    dati = {prop: valori(run(query_proprieta(prop, set(paesi.values())), user_agent)) for prop in PROPRIETA}
    avvisi = set()
    per_paese = {code: fatti(dati, c, avvisi) for code, c in paesi.items()}
    titoli = divise(regions)
    per_regione = dati_regioni(titoli, user_agent, run, avvisi) if titoli else {}
    for a in sorted(avvisi):
        print(f"-- {a}", file=sys.stderr)
    rows = []
    for region_id, flag, _ in sorted(regions):
        code = PAESE_DEL_FLAG.get(flag, flag)
        if code not in per_paese:
            continue
        row = dict(zip(COLONNE, [region_id, code, *per_paese[code]]))
        if region_id in per_regione:
            # La capitale di una parte del paese e' la sua, o nessuna: "Capitale: Mosca" nella guida della Siberia
            # sembrerebbe la capitale della regione.
            row["capital_it"], row["capital_en"], fusi_reg = per_regione[region_id]
        else:
            fusi_reg = ""
        if fusi_reg:
            row["timezones"] = fusi_reg
        row.update(CORREZIONI.get(code, {}))
        row.update(CORREZIONI.get(region_id, {}))
        rows.append([row[k] for k in COLONNE])
    return [[f.replace("\t", " ").replace("\n", " ") for f in row] for row in rows]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--user-agent", default=USER_AGENT)
    args = parser.parse_args()
    wikidata_missions._deadline = time.monotonic() + DEADLINE_SECONDS
    try:
        rows = raccogli(regioni(REGIONS_SH.read_text(encoding="utf-8")), args.user_agent)
    except Exception as e:  # noqa: BLE001 - qualunque errore di rete/parsing
        print(f"ERRORE: Wikidata non interrogabile: {e}", file=sys.stderr)
        return 1
    con_capitale = sum(1 for r in rows if r[3])
    if con_capitale < MIN_ROWS:
        print(f"ERRORE: solo {con_capitale} regioni con la capitale (attese oltre {MIN_ROWS}): risultato scartato",
              file=sys.stderr)
        return 1
    tmp = args.out + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Dati dei paesi da Wikidata (CC0) per i Fatti rapidi, generati da tools/data-pipeline/scripts/"
                "wikidata_countries.py\n# (colonne e correzioni descritte li'): non modificare a mano.\n")
        for row in rows:
            f.write("\t".join(row) + "\n")
    os.replace(tmp, args.out)
    print(f"-- dati dei paesi: {len(rows)} regioni in {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
