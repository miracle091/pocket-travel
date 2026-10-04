#!/usr/bin/env bash
# Converte un modello di traduzione OPUS-MT (Helsinki-NLP, CC BY 4.0) nel formato di CTranslate2, quantizzato int8, per
# translate_guides.py: <outputDir> contiene model.bin, shared_vocabulary.json e i modelli SentencePiece source.spm e
# target.spm. La revisione di Hugging Face e' fissata qui sotto, cosi' il modello convertito (e la cache di Actions che lo
# tiene, vedi la chiave in .github/actions/setup-translation) cambia solo quando la si cambia a mano.
#
# Uso: convert-translation-model.sh <en-it|it-en> <outputDir>
# Richiede, con le versioni di requirements-translate-convert.txt: torch, transformers e ctranslate2 (python da $PYTHON,
# default python3).
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Uso: $0 <en-it|it-en> <outputDir>" >&2
  exit 1
fi

case "$1" in
  en-it) MODEL="Helsinki-NLP/opus-mt-tc-big-en-it"; REVISION="592d2cfb0797867f1dd223e49141de051faa65c7" ;;
  it-en) MODEL="Helsinki-NLP/opus-mt-tc-big-it-en"; REVISION="5009c4525f89c23e195873e918ba6827777d1a27" ;;
  *) echo "Direzione non supportata: $1" >&2; exit 1 ;;
esac
OUTPUT_DIR="$2"
PYTHON="${PYTHON:-python3}"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

# Il modello va letto e salvato in locale prima di convertirlo: transformers 5 non lega lm_head agli embedding quando i
# pesi sono solo in pytorch_model.bin (opus-mt-tc-big-it-en) e senza il legame il modello genera parole a caso (stessa
# correzione di translate_sections.py).
"$PYTHON" - "$MODEL" "$REVISION" "$WORKDIR/hf" <<'PY'
import sys
from transformers import MarianMTModel, MarianTokenizer

name, revision, out = sys.argv[1:4]
model = MarianMTModel.from_pretrained(name, revision=revision)
if model.config.tie_word_embeddings:
    model.lm_head.weight = model.model.shared.weight
model.save_pretrained(out)
MarianTokenizer.from_pretrained(name, revision=revision).save_pretrained(out)
PY
"$PYTHON" -m ctranslate2.converters.transformers --model "$WORKDIR/hf" --quantization int8 \
  --copy_files source.spm target.spm --output_dir "$OUTPUT_DIR" --force
echo "== $MODEL@${REVISION:0:7} convertito in $OUTPUT_DIR =="
