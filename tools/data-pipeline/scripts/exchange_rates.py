#!/usr/bin/env python3
"""Prepara exchange-rates.json, i tassi di cambio indicativi del fatto rapido "Valuta" delle guide.

I tassi vengono da Frankfurter v2 (https://frankfurter.dev, codice MIT): `GET /v2/rates?base=EUR` da' i tassi
"blended", la media che Frankfurter calcola dai tassi di riferimento delle banche centrali ("Blended rates are
Frankfurter's own calculation and free to use"). Restano solo le valute (`type` "currency" in `/v2/currencies`):
criptovalute e metalli preziosi no. Ogni valuta tiene la sua data, perche' le banche centrali pubblicano in giorni
diversi: l'app nasconde il tasso di una valuta dopo 14 giorni senza aggiornamento.

Il peso cubano (CUP) non prende il tasso blended (il cambio ufficiale, circa 30 volte piu' basso di quello che si
trova a Cuba) ma quello della Banca Centrale di Cuba (`/v2/rates?providers=BCC&base=EUR&quotes=CUP`, nessuna
condizione d'uso pubblicata): senza quel tasso, CUP resta fuori.

Senza abbastanza valute (MIN_CURRENCIES) lo script fallisce e il workflow non pubblica: resta il file precedente.

Uso: python exchange_rates.py <rates.json> <bcc.json> <currencies.json> <uscita>
"""
import json
import math
import re
import sys

BASE = "EUR"
# Oggi Frankfurter ne da' circa 160: molte meno vuol dire una risposta rotta o un'API cambiata.
MIN_CURRENCIES = 100
CODE = re.compile(r"\A[A-Z]{3}\Z")
DATE = re.compile(r"\A\d{4}-\d{2}-\d{2}\Z")


def valid_rows(rows):
    """Le righe {date, base, quote, rate} con base EUR, codice ISO, data e tasso positivo; le altre si scartano."""
    if not isinstance(rows, list):
        return []
    return [
        row for row in rows
        if isinstance(row, dict) and row.get("base") == BASE
        and isinstance(row.get("quote"), str) and CODE.match(row["quote"]) and row["quote"] != BASE
        and isinstance(row.get("date"), str) and DATE.match(row["date"])
        and isinstance(row.get("rate"), (int, float)) and not isinstance(row["rate"], bool)
        and math.isfinite(row["rate"]) and row["rate"] > 0
    ]


def exchange_rates(rates, bcc, currencies):
    """{"base": "EUR", "rates": {codice: {"rate", "date"}}} con le sole valute, CUP dalla Banca Centrale di Cuba."""
    fiat = {c.get("iso_code") for c in currencies if isinstance(c, dict) and c.get("type") == "currency"}
    result = {row["quote"]: {"rate": row["rate"], "date": row["date"]} for row in valid_rows(rates) if row["quote"] in fiat}
    result.pop("CUP", None)
    cup = [row for row in valid_rows(bcc) if row["quote"] == "CUP"]
    if cup:
        result["CUP"] = {"rate": cup[0]["rate"], "date": cup[0]["date"]}
    return {"base": BASE, "rates": dict(sorted(result.items()))}


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main(argv):
    if len(argv) != 5:
        sys.exit(__doc__)
    rates, currencies = load(argv[1]), load(argv[3])
    try:
        bcc = load(argv[2])
    except (OSError, ValueError):
        bcc = []
    result = exchange_rates(rates, bcc, currencies if isinstance(currencies, list) else [])
    count = len(result["rates"])
    if "CUP" not in result["rates"]:
        print("CUP senza il tasso della Banca Centrale di Cuba: resta fuori")
    if count < MIN_CURRENCIES:
        sys.exit(f"solo {count} valute (minimo {MIN_CURRENCIES}): Frankfurter e' cambiato? Non pubblico")
    print(f"{count} valute, date {min(r['date'] for r in result['rates'].values())}..{max(r['date'] for r in result['rates'].values())}")
    with open(argv[4], "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")


if __name__ == "__main__":
    main(sys.argv)
