#!/usr/bin/env python3
"""Carica un modello fine-tunato su HuggingFace — SOLO su richiesta esplicita.

Non fa parte della pipeline di training: va lanciato a mano. Senza --push e' un dry-run
(mostra repo, file e model card, non contatta HuggingFace). Il repo nasce PRIVATO: per
pubblicarlo serve anche --public. Il token si legge da HF_TOKEN (mai stampato).

Uso:
  python upload_hf.py <cartella modello> --repo utente/nome \\
      --base-model HuggingFaceTB/SmolLM2-135M-Instruct --base-license apache-2.0 [--push] [--public]

POLICY Viaggiare Sicuri (vedi generate_sft_dataset.py): con --vs il dataset
include Viaggiare Sicuri (Farnesina), licenza non verificata; siccome il training e' estrattivo, un modello
addestrato con VS puo' rigenerare quel testo se interrogato. Solo il dataset di default (senza --vs) e'
quindi pubblicabile. Questo script rifiuta --public se ATTRIBUTION.tsv contiene righe VS (vedi check_no_vs
sotto); in locale/privato resta comunque possibile, la scelta e' dell'utente.
ATTRIBUTION.tsv usato per il controllo e per l'upload: quello dentro <cartella modello> se presente (scritto
da train_lora.py, riflette il dataset usato per QUESTO training) — altrimenti quello di data/sft/, con un
avviso (riflette solo l'ultimo dataset generato, non necessariamente quello di questi pesi).

Model card generata: elenca le fonti che alimentano le risposte (Wikivoyage IT, Wikipedia IT: CC BY-SA 4.0)
separate da quelle usate solo come contesto per i rifiuti (mai riprodotte nell'output). Licenza dichiarata
cc-by-sa-4.0 di default (modificabile con --license); ATTRIBUTION.tsv viene caricato accanto ai pesi.
Prima di --public conviene comunque una verifica legale.
"""
import argparse
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
GLOBAL_ATTRIBUTION = HERE.parent / "data" / "sft" / "ATTRIBUTION.tsv"
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
di viaggio offline per l'app Pocket Travel: risposte brevi in italiano ancorate al CONTESTO fornito e
rifiuto esplicito quando il contesto non basta.

## Dati di addestramento

Fonti che alimentano le risposte (il modello puo' riprodurne frasi letterali):
- Wikivoyage IT — CC BY-SA 4.0
- Wikipedia IT, articoli tematici per paese (cucina/cultura/telecomunicazioni/media) — CC BY-SA 4.0

Fonti usate solo come contesto per i rifiuti (mai riprodotte: il target e' sempre la frase di rifiuto, non
il testo della fonte): Wikivoyage EN/DE/FR (CC BY-SA 4.0), FCDO/gov.uk (OGL v3.0), World Factbook (CC0 1.0),
worldfactbooks.com (licenze miste per campo), Travel.gc.ca (Open Government Licence - Canada).

Elenco completo delle pagine sorgente in `ATTRIBUTION.tsv`, incluso in questo repo. Questa build non
include Viaggiare Sicuri (Farnesina): licenza non verificata, esclusa dai pesi pubblici.

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


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", type=Path, help="cartella del modello (es. .../merged)")
    ap.add_argument("--repo", required=True, help="utente/nome del repo HuggingFace")
    ap.add_argument("--base-model", required=True)
    ap.add_argument("--base-license", required=True, help="licenza del modello base, es. apache-2.0 o mit")
    ap.add_argument("--license", default="cc-by-sa-4.0", help="licenza dichiarata per il modello pubblicato")
    ap.add_argument("--public", action="store_true", help="repo pubblico (default: privato)")
    ap.add_argument("--push", action="store_true", help="carica davvero (default: dry-run)")
    a = ap.parse_args()

    if not a.folder.is_dir():
        sys.exit(f"Cartella non trovata: {a.folder}")
    attribution = resolve_attribution(a.folder)
    attribution_in_folder = attribution.parent == a.folder
    check_no_vs(attribution, a.public)
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


if __name__ == "__main__":
    main()
