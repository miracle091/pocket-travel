#!/usr/bin/env python3
"""Carica un modello fine-tunato su HuggingFace — SOLO su richiesta esplicita.

Non fa parte della pipeline di training: va lanciato a mano. Senza --push e' un dry-run
(mostra repo, file e model card, non contatta HuggingFace). Il repo nasce PRIVATO: per
pubblicarlo serve anche --public. Il token si legge da HF_TOKEN (mai stampato).

Uso:
  python upload_hf.py <cartella modello> --repo utente/nome \\
      --base-model HuggingFaceTB/SmolLM2-135M-Instruct --base-license apache-2.0 [--push] [--public] \\
      [--update-app-status]

Con --update-app-status, dopo il caricamento: calcola sha256 e dimensione dei .gguf della cartella, li
confronta con quelli che HuggingFace riporta, avvia su main il job update-app-status-models di
publish-apk.yml (app-status.json firmato con le impronte nuove: le app dalla 0.9.1 scaricano subito il
modello ricaricato, senza un rilascio) e, se il run riesce, scrive le impronte in LlmModelCatalog.kt
(riserva per quando l'app non raggiunge app-status.json). Senza --push mostra solo le impronte.

POLICY Viaggiare Sicuri (vedi generate_sft_dataset.py): con --vs il dataset
include Viaggiare Sicuri (Farnesina), licenza non verificata; siccome il training e' estrattivo, un modello
addestrato con VS puo' rigenerare quel testo se interrogato. Solo il dataset di default (senza --vs) e'
quindi pubblicabile. Questo script rifiuta --public se ATTRIBUTION.tsv contiene righe VS (vedi check_no_vs
sotto); in locale/privato resta comunque possibile, la scelta e' dell'utente.
ATTRIBUTION.tsv usato per il controllo e per l'upload: quello dentro <cartella modello> se presente (scritto
da train_lora.py, riflette il dataset usato per QUESTO training) — altrimenti quello di data/sft/, con un
avviso (riflette solo l'ultimo dataset generato, non necessariamente quello di questi pesi).

Model card generata: elenca le fonti che alimentano le risposte (Wikivoyage e Wikipedia IT/EN, traduzioni MarianMT,
dati Open Government Licence) separate da quelle usate solo per le domande. Licenza dichiarata
cc-by-sa-4.0 di default (modificabile con --license); ATTRIBUTION.tsv viene caricato accanto ai pesi.
Prima di --public conviene comunque una verifica legale.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
GLOBAL_ATTRIBUTION = HERE.parent / "data" / "sft" / "ATTRIBUTION.tsv"
CATALOG = HERE.parents[2] / "feature" / "ai" / "src" / "main" / "kotlin" / "com" / "pockettravel" / "feature" / "ai" / "LlmModelCatalog.kt"
WORKFLOW = "publish-apk.yml"
VS_LICENSE = "licenza non verificata (Farnesina)"  # stessa stringa di generate_sft_dataset.py

CARD = """---
license: {license}
base_model: {base_model}
language:
- it
- en
tags:
- travel
- lora
- pocket-travel
---

# {name}

Fine-tuning LoRA (fuso nei pesi) di [{base_model}](https://huggingface.co/{base_model}) come assistente
di viaggio offline per l'app Pocket Travel: risposte brevi nella lingua del dataset (italiano o inglese)
ancorate al CONTESTO fornito e rifiuto esplicito quando il contesto non basta.

## Dati di addestramento

Fonti che alimentano le risposte (il modello puo' riprodurne frasi letterali), estratte dai testi e ripulite
dal markup (modificate rispetto all'originale):
- Wikivoyage IT ed EN — [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
- Wikipedia IT ed EN, paragrafi degli articoli tematici per paese e Storia e Clima delle citta' — CC BY-SA 4.0
- sezioni tradotte automaticamente dall'altra lingua con MarianMT (Helsinki-NLP opus-mt-tc-big, CC BY 4.0),
  indicate come tali in `ATTRIBUTION.tsv`
- numeri di emergenza e riassunti delle vaccinazioni da Travel.gc.ca (Open Government Licence - Canada 2.0) e
  gov.uk / TravelHealthPro (Open Government Licence v3.0), con Wikipedia e Wikidata (CC0)

Fonti usate solo per le domande (le risposte sono estratte dal contesto o sono la frase di rifiuto): domande di
viaggio reali (UltraChat, MIT) e domande fuori tema da dataset pubblici, elencati con la loro licenza.

Elenco completo delle pagine sorgente e delle licenze in `ATTRIBUTION.tsv`, incluso in questo repo. Questa build
non include Viaggiare Sicuri (Farnesina): licenza non verificata, esclusa dai pesi pubblici.

## Licenza
- Modello base: `{base_license}` (vedi la sua scheda; l'avviso di licenza originale resta valido).
- Per lo share-alike (CC BY-SA 4.0 di Wikivoyage/Wikipedia), questo modello derivato e' pubblicato sotto
  `{license}`.
"""

def resolve_attribution(folder):
    """ATTRIBUTION.tsv da usare per questi pesi: quello dentro `folder` (scritto da train_lora.py per
    QUESTO training) se presente, altrimenti quello globale di data/sft/ (meno affidabile: riflette
    l'ultimo dataset generato, non necessariamente quello usato per addestrare i pesi in `folder`)."""
    local = folder / "ATTRIBUTION.tsv"
    if local.exists():
        return local
    if GLOBAL_ATTRIBUTION.exists():
        print(f"ATTENZIONE: nessun ATTRIBUTION.tsv in {folder}, uso quello globale ({GLOBAL_ATTRIBUTION}): "
              "potrebbe non corrispondere al dataset usato per addestrare questi pesi (train_lora.py da "
              "prima di questa modifica non lo copiava nella cartella di output).", file=sys.stderr)
    return GLOBAL_ATTRIBUTION

def check_no_vs(attribution, push_public):
    """Rifiuta --public se `attribution` contiene righe Viaggiare Sicuri (licenza non verificata): solo
    il dataset di default (generate_sft_dataset.py senza --vs) e' pubblicabile. In locale/privato resta
    comunque possibile: qui si avvisa soltanto."""
    if not attribution.exists():
        if push_public:  # senza l'elenco delle fonti un repo pubblico non rispetterebbe le attribuzioni
            sys.exit(f"{attribution} non esiste: niente --public senza l'elenco delle fonti")
        return
    vs_rows = sum(1 for line in attribution.read_text(encoding="utf-8").splitlines() if VS_LICENSE in line)
    if not vs_rows:
        return
    msg = (f"{attribution} contiene {vs_rows} righe di Viaggiare Sicuri (licenza non verificata): "
           "questo dataset/modello non e' pubblicabile. Rigenera il dataset senza --vs "
           "(generate_sft_dataset.py) e riaddestra prima di caricare su un repo pubblico.")
    if push_public:
        sys.exit(msg)
    print(f"ATTENZIONE: {msg}", file=sys.stderr)


def catalog_blocks(catalog_text):
    """Blocchi `LlmModelDefinition(...)` del catalogo (le istanze, non la dichiarazione della classe)."""
    start = catalog_text.index("listOf(")
    return [(m.start(1) + start, m.end(1) + start, m.group(1))
            for m in re.finditer(r"LlmModelDefinition\((.*?)\n\s*\),", catalog_text[start:], re.S)]


def field(block, name):
    m = re.search(rf'\b{name}\s*=\s*"([^"]*)"', block)
    return m.group(1) if m else None


def catalog_targets(catalog_text, repo, file_names):
    """(modelId, nome file) dei GGUF caricati in `repo` che il catalogo dell'app scarica da li'. Un GGUF che il
    catalogo non conosce e' un errore: la sua impronta non servirebbe a nessuna app."""
    by_file = {}
    for _, _, block in catalog_blocks(catalog_text):
        url = field(block, "url") or ""
        if f"huggingface.co/{repo}/" in url:
            by_file[field(block, "fileName")] = field(block, "id")
    missing = [n for n in file_names if n not in by_file]
    if missing:
        sys.exit(f"{', '.join(missing)}: nessun modello del catalogo ({CATALOG.name}) li scarica da {repo}")
    return [(by_file[n], n) for n in file_names]


def kotlin_long(value):
    """529297120 -> 529_297_120L, come nel catalogo."""
    return f"{value:_}L"


def update_catalog(catalog_text, file_name, sha256, size):
    """Testo del catalogo con sha256 e sizeBytes nuovi per il modello che scarica `file_name`."""
    for begin, end, block in catalog_blocks(catalog_text):
        if field(block, "fileName") == file_name:
            block = re.sub(r'\bsha256\s*=\s*(?:"[^"]*"|null)', f'sha256 = "{sha256}"', block)
            block = re.sub(r"\bsizeBytes\s*=\s*[0-9_]+L", f"sizeBytes = {kotlin_long(size)}", block)
            return catalog_text[:begin] + block + catalog_text[end:]
    raise ValueError(f"{file_name} non e' nel catalogo")


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_on_hf(api, repo, models):
    """Le impronte calcolate in locale devono essere quelle del file che HuggingFace ora serve."""
    infos = {i.path: i for i in api.get_paths_info(repo, [m["fileName"] for m in models])}
    for m in models:
        info = infos.get(m["fileName"])
        remote = (info.lfs.sha256 if info and info.lfs else None, info.size if info else None)
        if remote != (m["sha256"], m["sizeBytes"]):
            sys.exit(f"{m['fileName']} su HuggingFace ha sha256 {remote[0]} e {remote[1]} byte, "
                     f"in locale {m['sha256']} e {m['sizeBytes']}: app-status.json non aggiornato")


UPDATE_JOB = "update-app-status-models"


def find_run(request_id):
    """Id del run il cui nome contiene `request_id` (run-name di publish-apk.yml), None se non c'e' ancora."""
    runs = json.loads(subprocess.run(
        ["gh", "run", "list", "--workflow", WORKFLOW, "--event", "workflow_dispatch", "--branch", "main",
         "--limit", "20", "--json", "databaseId,displayTitle"], check=True, capture_output=True, text=True).stdout)
    return next((str(r["databaseId"]) for r in runs if request_id in r["displayTitle"]), None)


def job_conclusion(run_id):
    """Esito del job che aggiorna app-status.json nel run (success, failure, cancelled...), None se non e' finito."""
    jobs = json.loads(subprocess.run(["gh", "run", "view", run_id, "--json", "jobs"],
                                     check=True, capture_output=True, text=True).stdout)["jobs"]
    return next((j.get("conclusion") or None for j in jobs if j["name"] == UPDATE_JOB), None)


def run_update_workflow(models):
    """Avvia su main il job update-app-status-models di publish-apk.yml e ne attende l'esito; True se riuscito.
    Il run si riconosce dal nome, che contiene un identificativo scelto qui: non si confonde con altri lanci."""
    payload = json.dumps([{k: m[k] for k in ("modelId", "sha256", "sizeBytes")} for m in models])
    request_id = uuid.uuid4().hex[:12]
    subprocess.run(["gh", "workflow", "run", WORKFLOW, "--ref", "main",
                    "-f", f"update_models={payload}", "-f", f"request_id={request_id}"], check=True)
    run_id = None
    for _ in range(40):  # il run compare nella lista qualche secondo dopo la richiesta
        time.sleep(3)
        run_id = find_run(request_id)
        if run_id:
            break
    if run_id is None:
        sys.exit(f"Run {request_id} di {WORKFLOW} non trovato: controlla su GitHub Actions, e se il job "
                 f"{UPDATE_JOB} e' riuscito aggiorna a mano {CATALOG.name} con le impronte sopra.")
    print(f"Workflow avviato (run {run_id}), attendo l'esito...")
    subprocess.run(["gh", "run", "watch", run_id])
    # L'esito del job, non quello di gh run watch: un'attesa interrotta non vuol dire un job fallito.
    conclusion = job_conclusion(run_id)
    if conclusion is None:
        sys.exit(f"Il run {run_id} non e' ancora finito: se il job {UPDATE_JOB} riesce, aggiorna a mano "
                 f"{CATALOG.name} con le impronte sopra.")
    return conclusion == "success"


def update_app_status(api, repo, folder, push):
    """--update-app-status: impronte dei GGUF caricati -> app-status.json firmato (workflow) -> catalogo locale."""
    ggufs = sorted(folder.glob("*.gguf"))
    if not ggufs:
        sys.exit(f"--update-app-status: nessun .gguf in {folder}")
    targets = catalog_targets(CATALOG.read_text(encoding="utf-8"), repo, [p.name for p in ggufs])
    models = [{"modelId": model_id, "fileName": path.name, "sha256": sha256_of(path), "sizeBytes": path.stat().st_size}
              for (model_id, _), path in zip(targets, ggufs)]
    for m in models:
        print(f"  {m['modelId']} ({m['fileName']}): {m['sha256']}, {m['sizeBytes']} byte")
    if not push:
        print("Dry-run: app-status.json e catalogo non aggiornati.")
        return
    check_on_hf(api, repo, models)
    if not run_update_workflow(models):
        sys.exit("Workflow fallito: catalogo locale non aggiornato. Dettagli su GitHub Actions.")
    text = CATALOG.read_text(encoding="utf-8")
    for m in models:
        text = update_catalog(text, m["fileName"], m["sha256"], m["sizeBytes"])
    CATALOG.write_text(text, encoding="utf-8", newline="")
    print(f"app-status.json aggiornato e {CATALOG.name} allineato. Il commit del catalogo non e' urgente: al "
          "rilascio publish-apk.yml tiene l'impronta che coincide con HuggingFace, dal catalogo o dal file online.")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", type=Path, help="cartella del modello (es. .../merged)")
    ap.add_argument("--repo", required=True, help="utente/nome del repo HuggingFace")
    ap.add_argument("--base-model", required=True)
    ap.add_argument("--base-license", required=True, help="licenza del modello base, es. apache-2.0 o mit")
    ap.add_argument("--license", default="cc-by-sa-4.0", help="licenza dichiarata per il modello pubblicato")
    ap.add_argument("--card", type=Path,
                    help="model card da caricare come README.md al posto di quella generata "
                         "(es. tools/data-pipeline/model-cards/<repo>.md)")
    ap.add_argument("--public", action="store_true", help="repo pubblico (default: privato)")
    ap.add_argument("--push", action="store_true", help="carica davvero (default: dry-run)")
    ap.add_argument("--update-app-status", action="store_true",
                    help="dopo il caricamento pubblica in app-status.json le impronte dei .gguf (workflow "
                         f"{WORKFLOW} su main, serve gh autenticato) e, se riesce, aggiorna LlmModelCatalog.kt")
    a = ap.parse_args()

    if not a.folder.is_dir():
        sys.exit(f"Cartella non trovata: {a.folder}")
    attribution = resolve_attribution(a.folder)
    attribution_in_folder = attribution.parent == a.folder
    check_no_vs(attribution, a.public)
    if a.card:
        card = a.card.read_text(encoding="utf-8")
    else:
        card = CARD.format(name=a.repo.split("/")[-1], license=a.license, base_model=a.base_model,
                           base_license=a.base_license)
    files = sorted(p for p in a.folder.rglob("*") if p.is_file())
    size = sum(p.stat().st_size for p in files) / 2**20

    print(f"Repo: {a.repo} ({'PUBBLICO' if a.public else 'privato'}) | licenza: {a.license}")
    attr_note = "" if attribution_in_folder else f" + {attribution.name} (da {attribution})"
    print(f"File da caricare da {a.folder}: {len(files)} ({size:.1f} MiB)" +
          (" + README.md" + attr_note if attribution.exists() else " + README.md (ATTRIBUTION.tsv assente!)"))
    for p in files[:15]:
        print("  ", p.relative_to(a.folder))
    if not a.push:
        print("\nDry-run: nulla e' stato caricato. Aggiungi --push per caricare.")
        if a.update_app_status:
            print("Impronte che --update-app-status pubblicherebbe:")
            update_app_status(None, a.repo, a.folder, push=False)
        return

    token = os.environ.get("HF_TOKEN")
    if not token:
        sys.exit("HF_TOKEN non impostato nell'ambiente.")
    from huggingface_hub import HfApi
    api = HfApi(token=token)
    api.create_repo(a.repo, private=not a.public, exist_ok=True)
    api.upload_folder(repo_id=a.repo, folder_path=str(a.folder), ignore_patterns=["training_args.bin"])
    api.upload_file(repo_id=a.repo, path_or_fileobj=card.encode("utf-8"), path_in_repo="README.md")
    if attribution.exists() and not attribution_in_folder:  # se era in a.folder, upload_folder l'ha gia' caricato
        api.upload_file(repo_id=a.repo, path_or_fileobj=str(attribution), path_in_repo="ATTRIBUTION.tsv")
    print(f"Caricato: https://huggingface.co/{a.repo}")
    if a.update_app_status:
        update_app_status(api, a.repo, a.folder, push=True)


if __name__ == "__main__":
    main()
