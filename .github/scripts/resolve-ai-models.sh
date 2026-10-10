#!/usr/bin/env bash
# Scrive in <output> il campo aiModels di app-status.json: per ogni modello di <catalogo> l'impronta (sha256,
# dimensione) che coincide con il file su HuggingFace, presa dal catalogo o dall'app-status.json online se la
# sua firma e' valida (tools/data-pipeline/scripts/resolve_ai_models.py). Fallisce se per un modello nessuna
# coincide: l'app rifiuterebbe quel download. Da lanciare dalla radice del repository, con GH_TOKEN.
# Uso: bash .github/scripts/resolve-ai-models.sh <LlmModelCatalog.kt> <output>
set -euo pipefail
source .github/scripts/retry.sh

catalog="$1"
output="$2"
online_dir="$(mktemp -d)"
trap 'rm -rf "$online_dir"' EXIT

online=()
if ! retry gh release download app-status --pattern 'app-status.json*' --dir "$online_dir" --repo "$GITHUB_REPOSITORY"; then
  echo "::warning::app-status.json online non scaricabile: uso solo il catalogo" >&2
# Il motivo (chiave non trovata, firma assente o non valida) resta nel log: lo scrive verify-app-status.sh.
elif ! bash .github/scripts/verify-app-status.sh "$online_dir/app-status.json"; then
  echo "::warning::app-status.json online non verificato (motivo sopra): uso solo il catalogo" >&2
else
  online=("$online_dir/app-status.json")
fi
python3 tools/data-pipeline/scripts/resolve_ai_models.py "$catalog" "${online[@]}" > "$output"
