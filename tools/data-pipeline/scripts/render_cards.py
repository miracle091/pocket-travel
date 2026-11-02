#!/usr/bin/env python3
"""Model card dei modelli addestrati sulle guide, generate da model-cards/template.md e model-cards/models.json.

Il modello ha le parti comuni alle sei card; i blocchi fra le righe "<!-- it -->" e "<!-- /it -->" (o "en") restano
solo nelle card di quella lingua, e i segnaposto "{{nome}}" prendono i dati del modello. La fascia di RAM e la
percentuale vengono dalle costanti dell'app (GgufMetadata.kt, DeviceAiCapability.kt) con la regola di minRamTierFor,
mai scritte a mano. La card generata e' quella che upload_hf.py --card carica come README.md.

Uso:
  python render_cards.py                 scrive le card in model-cards/<repo>.md
  python render_cards.py --check         esce con 1 se una card nel repository non e' quella generata
  python render_cards.py --results <repo> <log del modello> <log del modello base>
                                         copia in models.json le percentuali di rifiuto dei log di
                                         eval_gguf.py --extended (righe "tipo <gruppo>: N righe, rifiuta X%")
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CARDS = ROOT / "tools" / "data-pipeline" / "model-cards"
TEMPLATE = CARDS / "template.md"
MODELS = CARDS / "models.json"
AI_SOURCES = ROOT / "feature" / "ai" / "src" / "main" / "kotlin" / "com" / "pockettravel" / "feature" / "ai"

# Gruppi del test esteso, nell'ordine della tabella: prima quelli da rifiutare, poi quelli con la risposta nel contesto.
REFUSE = [
    ("neg_empty", "Empty context, no guide text found"),
    ("neg_off", "Off-topic questions"),
    ("neg_para", "Country questions whose topic is missing from the context"),
    ("neg_city", "City questions whose topic is missing from the page"),
    ("neg_wiki", "City history or climate questions the context does not cover"),
    ("neg_near", '"What is near me" questions with no matching place nearby'),
    ("neg_dep", "Departure questions with no matching departure"),
]
ANSWER = [
    ("pos_para", "Country questions"),
    ("pos_city", "City questions"),
    ("pos_wiki", "City history or climate questions"),
    ("pos_near", '"What is near me" questions'),
    ("pos_dep", "Departure questions"),
]
GROUPS = [kind for kind, _ in REFUSE + ANSWER]
LANGUAGES = ("it", "en")


def app_ram_rule(sources=AI_SOURCES):
    """(MiB dell'app, MiB lasciati liberi, fasce in GB) letti dal codice dell'app."""
    gguf = (sources / "GgufMetadata.kt").read_text(encoding="utf-8")
    device = (sources / "DeviceAiCapability.kt").read_text(encoding="utf-8")

    def mib(name):
        m = re.search(rf"\b{name} = ([\d_]+) \* MIB\b", gguf)
        if not m:
            sys.exit(f"GgufMetadata.kt: {name} non trovato")
        return int(m[1].replace("_", ""))

    tiers = sorted(int(n) for n in re.findall(r"const val \w+_RAM_GB = (\d+)", device))
    if not tiers:
        sys.exit("DeviceAiCapability.kt: nessuna soglia *_RAM_GB")
    return mib("APP_BYTES"), mib("SYSTEM_FREE_BYTES"), tiers


def ram_tier(memory_mib, rule):
    """Fascia in GB come minRamTierFor: la piu' bassa in cui modello, app e RAM libera stanno nella RAM."""
    app, free, tiers = rule
    return next((gb for gb in tiers if memory_mib + app + free <= gb * 1024), tiers[-1])


def percent(x):
    return f"{x:.1f}".rstrip("0").rstrip(".") + "%"


def results_table(model):
    ours, base = model["results"]["model"], model["results"]["base"]
    missing = [k for k in GROUPS if k not in ours or k not in base]
    if missing:
        sys.exit(f"{model['repo']}: mancano i risultati di {', '.join(missing)}")
    for kind in GROUPS:
        if ours[kind][0] != base[kind][0]:
            sys.exit(f"{model['repo']} {kind}: {ours[kind][0]} domande per il modello, {base[kind][0]} per il base")
    rows = ["| Group (questions) | This model | Base model ({}) |".format(model["base_model"].split("/")[-1]), "|---|---|---|",
            "| **Refuses** (higher is better) | | |"]
    rows += [f"| {label} ({ours[k][0]}) | {percent(ours[k][1])} | {percent(base[k][1])} |" for k, label in REFUSE]
    rows.append("| **Wrongly refuses answerable questions** (lower is better) | | |")
    rows += [f"| {label} ({ours[k][0]}) | {percent(ours[k][1])} | {percent(base[k][1])} |" for k, label in ANSWER]
    return "\n".join(rows), sum(ours[k][0] for k in GROUPS)


def overall(results, groups):
    """Percentuale di rifiuto sull'insieme dei gruppi, pesata col numero di domande."""
    n = sum(results[k][0] for k, _ in groups)
    return sum(results[k][0] * results[k][1] for k, _ in groups) / n


def files_table(model, rule):
    """Il file attuale e quelli superati ancora nella repo, con memoria, fascia di RAM e percentuali di rifiuto
    complessive dello stesso test."""
    current = {"file": model["file"], "version": "**current**", "quantization": "Q5_K_M with importance matrix",
               "file_mib": model["file_mib"], "memory_mib": model["memory_mib"], "results": model["results"]["model"]}
    outdated = [dict(f, version="outdated") for f in model.get("outdated_files", [])]
    rows = ["| File | Version | Quantization | Size | Memory in use | Suggested device RAM | Refuses unanswerable "
            "questions | Wrongly refuses answerable questions |", "|---|---|---|---|---|---|---|---|"]
    for f in [current] + outdated:
        rows.append(f"| `{f['file']}` | {f['version']} | {f['quantization']} | {f['file_mib']:,} MiB | "
                    f"{f['memory_mib']:,} MiB | {ram_tier(f['memory_mib'], rule)} GB or more | "
                    f"{percent(round(overall(f['results'], REFUSE), 1))} | {percent(round(overall(f['results'], ANSWER), 1))} |")
    return "\n".join(rows)


def outdated_note(model):
    """Avviso sui file superati, vuoto se la repo ha solo il file attuale."""
    files = model.get("outdated_files", [])
    if not files:
        return ""
    names = ", ".join(f"`{f['file']}`" for f in files)
    return (f"> **Outdated file, for tests only.** {names} is the previous version of this model, trained on an older\n"
            f"> dataset: it wrongly refuses more of the questions it could answer (see the table) and will not be updated.\n"
            f"> It stays in the repository only because Pocket Travel {files[0]['kept_for']} download it, and to compare\n"
            f"> the two versions. For anything else use `{model['file']}`.")


def quant_reason(model, data):
    """Perche' Q5_K_M invece del Q4_K_M dei modelli precedenti: KL medio dal modello a 16 bit misurato sugli stessi
    pesi (quelli della versione precedente). Per i modelli senza misura, l'intervallo misurato sugli altri."""
    def drop(m):
        q4, q5 = m["quant_kl"]["Q4_K_M"], m["quant_kl"]["Q5_K_M"]
        return round((1 - q5 / q4) * 100)

    def growth(m):
        return round((m["file_mib"] / m["outdated_files"][0]["file_mib"] - 1) * 100)

    head = "**Why Q5_K_M.** The previous Pocket Travel models were Q4_K_M."
    if "quant_kl" in model:
        kl = model["quant_kl"]
        return (f"{head} Measured on the same weights (those of the previous version), this Q5_K_M stays closer to the\n"
                f"unquantized 16-bit model: the mean KL divergence on held-out guide text drops from {kl['Q4_K_M']:.3f} to\n"
                f"{kl['Q5_K_M']:.3f} ({drop(model)}% less), for a file {growth(model)}% larger, and the evaluation results are not\n"
                f"worse. The gap in refusal rates between the two files in the table comes from the newer dataset.")
    measured = [m for m in data["models"] if "quant_kl" in m]
    drops, growths = sorted(drop(m) for m in measured), sorted(growth(m) for m in measured)
    return (f"{head} On the smaller models, measured on the same weights, this Q5_K_M lowers the mean KL divergence\n"
            f"from the unquantized 16-bit model by {drops[0]}-{drops[-1]}% on held-out guide text, for files "
            f"{growths[0]}-{growths[-1]}% larger, with\nevaluation results that are not worse; this model uses the same recipe.")


def keep_language(text, language):
    """Toglie i blocchi delle altre lingue e le righe che li delimitano."""
    out, inside = [], None
    for line in text.split("\n"):
        m = re.fullmatch(r"<!-- (/?)(\w+) -->", line)
        if m and m[2] in LANGUAGES:
            if m[1] and inside != m[2] or not m[1] and inside:
                sys.exit(f"template.md: blocco {line} fuori posto")
            inside = None if m[1] else m[2]
            continue
        if inside in (None, language):
            out.append(line)
    if inside:
        sys.exit(f"template.md: blocco {inside} non chiuso")
    return "\n".join(out)


def render(model, data, template, rule):
    lang = data["languages"][model["language"]]
    table, questions = results_table(model)
    tier = ram_tier(model["memory_mib"], rule)
    app, _, _ = rule
    values = {
        "repo": model["repo"],
        "language": model["language"],
        "language_name": lang["name"],
        "base_model": model["base_model"],
        "base_gguf": model["base_gguf"],
        "base_role": model.get("base_role") or "the Official model the app offers in the same RAM tier",
        "collection": data["collection"],
        "file": model["file"],
        "files_table": files_table(model, rule),
        "ram_percent": str(round((model["memory_mib"] + app) / (tier * 1024) * 100)),
        "test_questions": f"{questions:,}",
        "results_table": table,
        "dataset_rows": f"{lang['dataset_rows']:,}",
        "dataset_refusals": str(lang["dataset_refusals_percent"]),
    }
    text = keep_language(template, model["language"])
    # Paragrafi facoltativi: senza valore spariscono con la riga vuota che li precede.
    for name, value in (("results_note", model.get("results_note", "")), ("outdated_note", outdated_note(model)),
                        ("quant_reason", quant_reason(model, data))):
        text = text.replace("{{" + name + "}}", value) if value else text.replace("\n\n{{" + name + "}}", "")

    def fill(m):
        if m[1] not in values:
            sys.exit(f"template.md: segnaposto {{{{{m[1]}}}}} senza valore per {model['repo']}")
        return values[m[1]]

    return re.sub(r"\{\{(\w+)\}\}", fill, text)


def render_all(cards=CARDS, sources=AI_SOURCES):
    data = json.loads((cards / "models.json").read_text(encoding="utf-8"))
    template = (cards / "template.md").read_text(encoding="utf-8")
    rule = app_ram_rule(sources)
    return {cards / f"{m['repo']}.md": render(m, data, template, rule) for m in data["models"]}


def read_results(log):
    """Percentuali di rifiuto per gruppo da un log di eval_gguf.py --extended (UTF-8 o UTF-16 di PowerShell)."""
    raw = log.read_bytes()
    text = raw.decode("utf-16") if raw[:2] in (b"\xff\xfe", b"\xfe\xff") else raw.decode("utf-8", "replace")
    found = {m[0]: [int(m[1]), float(m[2])] for m in re.findall(r"^tipo (\w+): (\d+) righe, rifiuta ([\d.]+)%", text, re.M)}
    missing = [k for k in GROUPS if k not in found]
    if missing:
        sys.exit(f"{log}: mancano i gruppi {', '.join(missing)}")
    return {k: found[k] for k in GROUPS}


def dump_json(data):
    """JSON con le coppie [domande, percentuale] su una riga."""
    text = json.dumps(data, indent=2, ensure_ascii=False)
    return re.sub(r"\[\s+(\d+),\s+([\d.]+)\s+\]", r"[\1, \2]", text) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--check", action="store_true", help="controlla le card invece di scriverle")
    ap.add_argument("--results", nargs=3, metavar=("REPO", "LOG_MODELLO", "LOG_BASE"),
                    help="copia in models.json i risultati del test esteso di un modello")
    a = ap.parse_args(argv)
    if a.results:
        repo, ours, base = a.results
        data = json.loads(MODELS.read_text(encoding="utf-8"))
        model = next((m for m in data["models"] if m["repo"] == repo), None)
        if model is None:
            sys.exit(f"models.json: nessun modello {repo}")
        model["results"] = {"model": read_results(Path(ours)), "base": read_results(Path(base))}
        MODELS.write_bytes(dump_json(data).encode("utf-8"))
        return 0
    stale = []
    for path, text in render_all().items():
        if a.check:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                stale.append(path.name)
        else:
            path.write_bytes(text.encode("utf-8"))
    if stale:
        print("Card diverse da quelle generate (python render_cards.py): " + ", ".join(stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
