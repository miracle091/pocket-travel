#!/usr/bin/env python3
"""Numeri nelle risposte dell'assistente che non compaiono nel contesto: telefoni, prezzi, orari, date, distanze, anni.
Sulle risposte gia' salvate da eval_gguf.py --answers, senza modello ne' GPU. Le righe di eval_gguf.py --contexts hanno
gia' il contesto; per quelle del test (--extended) il contesto viene dalla riga del test con la stessa chiave (tipo,
regione, domanda), come in eval_answers.py. I numeri della domanda contano come presenti; i rifiuti non si controllano.

Prima del confronto i formati si normalizzano: virgola o punto decimale ("2,5" e "2.5"), separatori delle migliaia
("1.200", "1,200", "1 200"), zeri iniziali, orari ("15:00", "15.00", "3 pm"), telefoni senza spazi, trattini, prefisso
internazionale e zeri iniziali (uguali se uno finisce con l'altro). Un numero ambiguo ("1.200": milleduecento o 1,2) e'
presente se lo e' una delle sue letture. Nel contesto contano anche i numeri da zero a dodici scritti in lettere.

Uso: python check_answer_numbers.py <risposte.jsonl> [...] [--test <eval_extended.jsonl>] [--out <segnalate.jsonl>]
"""
import argparse
import json
import re
from decimal import Decimal, InvalidOperation
from pathlib import Path

from eval_answers import key
from eval_common import is_refusal, question_of

KINDS = ("telefono", "prezzo", "orario", "data", "distanza", "anno", "altro")

# Telefono: col + o lo 0 iniziale almeno 7 cifre, altrimenti almeno 9 (cosi' due anni di fila o un intervallo di orari
# non sembrano un telefono); gruppi separati da uno spazio, un trattino, una barra o le parentesi.
PHONE = re.compile(r"(?<![\w.,])(?:\+|00)?\(?\d[\d()]*(?:[ \-/]\(?\d[\d()]*)+|(?<![\w.,])\+\d{7,}")
TIME = re.compile(r"(?<![\d.,])(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)(?![a-z])|(?<![\d.,])(\d{1,2})[:.](\d{2})(?![\d])",
                  re.IGNORECASE)
NUMBER = re.compile(r"\d{1,3}(?:[.,'’]\d{3})+(?:[.,]\d+)?(?!\d)|\d{1,3}(?: \d{3})+(?![\d.,])|\d+(?:[.,]\d+)?")
CURRENCY = re.compile(r"[€$£¥]|\b(?:eur|euro|usd|chf|gbp|kn|kuna|lek|dollar[io]?|dollars?|franchi|francs?|sterline|pounds?)\b",
                      re.IGNORECASE)
DISTANCE = re.compile(r"^\s*(?:km|chilometri|kilometers|kilometres|m|metri|meters|metres|mi|miglia|miles|ft|feet|piedi)\b",
                      re.IGNORECASE)
MONTHS = ("gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre|"
          "january|february|march|april|may|june|july|august|september|october|november|december|"
          "jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec")
DATE_AFTER = re.compile(rf"^(?:st|nd|rd|th)?\s*(?:{MONTHS})\b|^[/.\-]\d{{1,2}}\b", re.IGNORECASE)
DATE_BEFORE = re.compile(rf"\b(?:{MONTHS})\.?\s*$|\b\d{{1,2}}[/.\-]$", re.IGNORECASE)
WORDS = {w: n for n, ws in enumerate((
    "zero", "uno una un one", "due two", "tre three", "quattro four", "cinque five", "sei six", "sette seven",
    "otto eight", "nove nine", "dieci ten", "undici eleven", "dodici twelve")) for w in ws.split()}
WORD = re.compile(r"\b(" + "|".join(WORDS) + r")\b", re.IGNORECASE)


def _value(text):
    """'12,50' -> '12.5': il numero come stringa normalizzata (zeri iniziali e finali tolti)."""
    try:
        d = Decimal(text).normalize()
    except InvalidOperation:
        return None
    return format(d, "f") if d == d.to_integral() else format(d, "f").rstrip("0")


def number_values(token):
    """Le letture di un numero scritto: '2,5' -> {'2.5'}; '1.200' -> {'1200', '1.2'}; '1.234,5' -> {'1234.5'}."""
    if " " in token:
        return {_value(token.replace(" ", ""))}
    seps = [c for c in token if not c.isdigit()]
    if not seps:
        return {_value(token)}
    if len(set(seps)) > 1:  # "1.234,5" o "1,234.5": l'ultimo separatore e' quello decimale
        last = max(token.rfind("."), token.rfind(","))
        return {_value(re.sub(r"\D", "", token[:last]) + "." + token[last + 1:])}
    groups = re.split(r"[.,'’]", token)
    out = set()
    if all(len(g) == 3 for g in groups[1:]):
        out.add(_value("".join(groups)))
    if len(groups) == 2 and seps[0] in ".,":
        out.add(_value(groups[0] + "." + groups[1]))
    return out - {None}


def phone_digits(token):
    """'+39 06 1234-5678' -> '39612345678': solo cifre, senza zeri iniziali (prefisso internazionale compreso)."""
    return re.sub(r"\D", "", token).lstrip("0")


def _is_phone(token):
    digits = re.sub(r"\D", "", token)
    lead = token.lstrip("(")[:1] in ("+", "0")
    return len(digits) >= (7 if lead else 9)


def extract(text):
    """I numeri di un testo nell'ordine in cui compaiono: lista di (testo, tipo, chiavi). Le chiavi sono 'tel:<cifre>',
    'ora:HH:MM' e 'num:<valore>'; un orario a ore piene ha anche la chiave dell'ora come numero ('alle 9' e '9:00'
    coincidono)."""
    out, taken = [], []

    def free(span):
        return all(span[1] <= s or span[0] >= e for s, e in taken)

    for m in PHONE.finditer(text):
        token = m.group().rstrip(" -/(")
        if _is_phone(token):
            out.append((m.start(), token, "telefono", {"tel:" + phone_digits(token)}))
            taken.append((m.start(), m.start() + len(token)))
    for m in TIME.finditer(text):
        if not free(m.span()):
            continue
        if m.group(3):
            hour, minute, ampm = int(m.group(1)), int(m.group(2) or 0), m.group(3).lower().replace(".", "")
            hour = hour % 12 + (12 if ampm == "pm" else 0)
        else:
            hour, minute = int(m.group(4)), int(m.group(5))
            if hour > 24 or minute > 59:
                continue
        keys = {f"ora:{hour:02d}:{minute:02d}"}
        if not m.group(3) and "." in m.group():  # "2.50" puo' anche essere un prezzo
            keys |= {"num:" + v for v in number_values(m.group())}
        if minute == 0:
            keys |= {f"num:{hour}", f"num:{hour % 12 or 12}"}
        out.append((m.start(), m.group(), "orario", keys))
        taken.append(m.span())
    for m in NUMBER.finditer(text):
        if not free(m.span()):
            continue
        keys = {"num:" + v for v in number_values(m.group())}
        out.append((m.start(), m.group(), _kind(text, m), keys))
    return [n[1:] for n in sorted(out, key=lambda n: n[0])]


def _kind(text, m):
    before, after = text[max(0, m.start() - 12):m.start()], text[m.end():m.end() + 12]
    if CURRENCY.search(before[-5:]) or CURRENCY.match(after.lstrip()[:7]) or re.match(r"^\s*(?:€|\$|£)", after):
        return "prezzo"
    if DISTANCE.match(after):
        return "distanza"
    if DATE_AFTER.match(after) or DATE_BEFORE.search(before):
        return "data"
    if re.fullmatch(r"1\d{3}|20\d{2}", m.group()):
        return "anno"
    return "altro"


def known_keys(text):
    """Le chiavi dei numeri di un testo, numeri in lettere da zero a dodici compresi."""
    keys = set().union(*(k for _, _, k in extract(text)))
    keys |= {f"num:{WORDS[w.lower()]}" for w in WORD.findall(text)}
    return keys


def _phone_known(digits, keys):
    phones = [k[4:] for k in keys if k.startswith("tel:")]
    return any(len(min(digits, p, key=len)) >= 6 and (digits.endswith(p) or p.endswith(digits)) for p in phones)


def unsupported(answer, context):
    """I numeri della risposta che non sono nel contesto: lista di {"text", "kind"}."""
    keys = known_keys(context)
    out = []
    for token, kind, token_keys in extract(answer):
        if token_keys & keys:
            continue
        if kind == "telefono" and _phone_known(next(iter(token_keys))[4:], keys):
            continue
        out.append({"text": token, "kind": kind})
    return out


def context_of_prompt(prompt):
    """Il contesto e la domanda dal prompt dell'app (on_device_prompt), senza le istruzioni in testa ("3 frasi")."""
    m = re.search(r"\n(?:CONTESTO|CONTEXT): ", prompt)
    return prompt[m.end():] if m else prompt


def checked(answers, test=None):
    """Le risposte non di rifiuto con "unsupported"; senza "context" il contesto viene dalla riga del test con la stessa
    chiave, nell'ordine in cui compaiono (come eval_answers.checked_rows)."""
    by_key = {}
    for row in test or []:
        by_key.setdefault(key(row["kind"], row["region"], question_of(row)), []).append(row)
    out = []
    for a in answers:
        lang = a.get("lang") or a.get("question_lang") or "it"
        if "context" in a:
            context = a["context"] + "\n" + a["question"]
        else:
            rows = by_key.get(key(a["kind"], a["region"], a["question"]))
            if not rows:
                raise SystemExit(f"riga del test non trovata per la risposta: {a['kind']} {a['region']} {a['question']}")
            context = context_of_prompt(rows.pop(0)["messages"][0]["content"])
        if is_refusal(a["got"], lang):
            continue
        # una risposta fermata al limite di token puo' finire a meta' di un numero ("1819 - 188"): quel pezzo non conta.
        # Le risposte salvate prima del campo "truncated" sono troncate se non finiscono con un segno di fine frase.
        truncated = a.get("truncated", not re.search(r"[.!?)\]»\"]\s*$", a["got"]))
        answer = re.sub(r"[+(]?\d[\d .,:/()\-]*$", "", a["got"]) if truncated else a["got"]
        out.append({**{k: a.get(k) for k in ("kind", "region", "series", "search", "question", "got")},
                    "lang": lang, "unsupported": unsupported(answer, context)})
    return out


def print_summary(rows):
    flagged = [r for r in rows if r["unsupported"]]
    with_numbers = sum(bool(extract(r["got"])) for r in rows)
    print(f"risposte controllate (senza i rifiuti): {len(rows)}, con numeri {with_numbers}, "
          f"con almeno un numero non nel contesto {len(flagged)} ({100 * len(flagged) / max(1, len(rows)):.1f}%)")
    counts = {k: sum(any(u["kind"] == k for u in r["unsupported"]) for r in rows) for k in KINDS}
    print("risposte per tipo di numero non nel contesto: " + ", ".join(f"{k} {n}" for k, n in counts.items() if n))
    by_kind = {}
    for r in rows:
        by_kind.setdefault(r["kind"], []).append(bool(r["unsupported"]))
    for kind, flags in sorted(by_kind.items()):
        print(f"  {kind}: {sum(flags)}/{len(flags)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("answers", type=Path, nargs="+")
    ap.add_argument("--test", type=Path, help="file del test con cui sono state generate le risposte senza contesto")
    ap.add_argument("--out", type=Path, help="JSONL con le risposte segnalate e i loro numeri non nel contesto")
    a = ap.parse_args()
    test = None
    if a.test:
        with open(a.test, encoding="utf-8") as f:
            test = [json.loads(line) for line in f]
    out = open(a.out, "w", encoding="utf-8") if a.out else None
    try:
        for path in a.answers:
            with open(path, encoding="utf-8") as f:
                answers = [json.loads(line) for line in f]
            print(f"== {path.name}: {len(answers)} risposte")
            rows = checked(answers, list(test) if test else None)
            print_summary(rows)
            for r in rows:
                if out and r["unsupported"]:
                    out.write(json.dumps({"file": path.name, **r}, ensure_ascii=False) + "\n")
    finally:
        if out:
            out.close()


if __name__ == "__main__":
    main()
