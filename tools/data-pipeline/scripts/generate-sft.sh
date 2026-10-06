#!/usr/bin/env bash
# Genera il dataset SFT dell'assistente (generate_sft.py) in italiano e inglese, i test estesi (generate_eval_set*.py) e
# ne controlla la qualita' (audit_sft.py --strict). Solo il dataset: nessun training.
#
# Uso: generate-sft.sh [--new | --old] --data <cartella> [--lang it|en] [--version <nome>] [--dump-dir <cartella>] [--no-eval]
#   --new   (default) la ricetta nuova (v10): citta', citta' vicine e partenze, distanze, Storia e Clima delle citta',
#           numeri di emergenza, domande reali (di default in generate_sft.py)
#   --old   la ricetta vecchia (v9): citta', fatti rapidi, note e vaccinazioni, senza le opzioni nuove. Le fonti sono
#           cambiate da allora, quindi il file non e' identico al v9 dei training: si chiama v9-rigenerato e non
#           sostituisce pocket_travel_sft.v9.*.jsonl (per farlo, --version v9)
#   --data  cartella con:
#             dumps/<AAAA-MM-GG>/     MediaWiki Content File Exports (download-wikimedia-dumps.sh); si usa il piu' recente
#             guides/                 guides.db e guides-en.db pubblicati (guides--<versione>--guides.db, guides-en--...)
#             cities-it/, cities-en/  cities.db e cities-en.db pubblicati, uno per regione (<regionId>--<versione>--...)
#             vaccinations.jsonl      riassunti di VaccinationSummaryExport
#   --lang      una sola lingua (default: it ed en)
#   --version   nome nel file di output (default: v10 con --new, v9-rigenerato con --old)
#   --dump-dir  un export preciso invece del piu' recente in <data>/dumps
#   --no-eval   senza rigenerare i test estesi (l'audit usa quelli che ci sono)
# Output in tools/data-pipeline/data/sft/; log in <data>/generate-sft.<versione>.<lang>.log.
# Richiede: Python con torch, transformers e sentencepiece (variabile PYTHON, default python3), Java e ANDROID_HOME
# (app_clean passa da Gradle), rete per Wikidata e HuggingFace la prima volta (poi in cache in data/sft/raw/).
set -euo pipefail

usage() { sed -n '2,/^set /{/^#/p}' "$0" >&2; exit 1; }
RECIPE="" DATA="" LANGS="it en" VERSION="" DUMP="" EVAL=1
while [ "$#" -gt 0 ]; do
  case "$1" in
    --new|--old) [ -z "$RECIPE" ] || usage; RECIPE="${1#--}" ;;
    --data) DATA="${2:?}"; shift ;;
    --lang) LANGS="${2:?}"; shift ;;
    --version) VERSION="${2:?}"; shift ;;
    --dump-dir) DUMP="${2:?}"; shift ;;
    --no-eval) EVAL="" ;;
    *) usage ;;
  esac
  shift
done
[ -n "$DATA" ] || usage
RECIPE="${RECIPE:-new}"
case "$LANGS" in it|en|"it en") ;; *) usage ;; esac

SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
SFT="$SCRIPTS/../data/sft"
PY="${PYTHON:-python3}"
if [ -z "${ANDROID_HOME:-}" ] && command -v wslpath >/dev/null; then  # WSL: l'SDK installato da Android Studio su Windows
  ANDROID_HOME="$(wslpath "$(cmd.exe /c echo %LOCALAPPDATA% 2>/dev/null | tr -d '\r')")/Android/Sdk"
fi
export ANDROID_HOME="${ANDROID_HOME:?ANDROID_HOME non impostata (serve a Gradle per app_clean)}"
if [ -z "$DUMP" ]; then
  DUMP="$(find "$DATA/dumps" -mindepth 1 -maxdepth 1 -type d -regextype posix-extended -regex '.*/[0-9]{4}-[0-9]{2}-[0-9]{2}' 2>/dev/null \
    | sort | tail -1)"
  [ -n "$DUMP" ] || { echo "nessun export in $DATA/dumps: lancia download-wikimedia-dumps.sh $DATA/dumps" >&2; exit 1; }
fi
VERSION="${VERSION:-$([ "$RECIPE" = new ] && echo v10 || echo v9-rigenerato)}"
echo "ricetta $RECIPE, versione $VERSION, export $DUMP"

# l'ultimo file che corrisponde a [pattern] in [dir], errore se non ce n'e'
latest() { local f; f="$(find "$1" -maxdepth 1 -name "$2" | sort | tail -1)"; [ -n "$f" ] || { echo "manca $1/$2" >&2; exit 1; }; echo "$f"; }

cd "$SCRIPTS"
for lang in $LANGS; do
  if [ "$lang" = it ]; then
    guides="$(latest "$DATA/guides" 'guides--*--guides.db')"; cities=("$DATA"/cities-it/*--cities.db)
    eval_script=generate_eval_set.py eval_file="$SFT/eval_extended.jsonl"
  else
    guides="$(latest "$DATA/guides" 'guides-en--*--guides.db')"; cities=("$DATA"/cities-en/*--cities-en.db)
    eval_script=generate_eval_set_en.py eval_file="$SFT/eval_extended.en.jsonl"
  fi
  [ -e "${cities[0]}" ] || { echo "nessun cities db per $lang in $DATA" >&2; exit 1; }
  args=(--lang "$lang" --dump-dir "$DUMP" --cities 4500 --guides-db "$guides" --vaccinations "$DATA/vaccinations.jsonl"
        --version "$VERSION")
  if [ "$RECIPE" = new ]; then
    args+=(--nearby 0.03 --distances 0.02 --emergency 0.01 --cities-db "${cities[@]}")
  else
    args+=(--real-questions 0)
  fi
  log="$DATA/generate-sft.$VERSION.$lang.log"
  "$PY" generate_sft.py "${args[@]}" 2>&1 | tee "$log"
  if [ -n "$EVAL" ]; then  # Storia e Clima solo delle citta' delle regioni di test: le altre le scarta lo script
    "$PY" "$eval_script" --dump-dir "$DUMP" --cities-db "${cities[@]}" 2>&1 | tee -a "$log"
  fi
  "$PY" audit_sft.py "$SFT/pocket_travel_sft.$VERSION.$lang.jsonl" --lang "$lang" --eval "$eval_file" --strict 2>&1 \
    | tee -a "$log"
done
