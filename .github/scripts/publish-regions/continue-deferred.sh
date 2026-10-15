#!/usr/bin/env bash
# Job "publish" di publish-regions.yml: rilancia in una run nuova (workflow_dispatch, che il GITHUB_TOKEN puo' fare) le
# regioni che gli shard hanno rimandato per il budget di tempo (deferred-<shard>.txt) e, solo dalla run iniziale, quelle
# fallite (failed-<shard>.txt). Al massimo 4 continuazioni in fila.
# Ambiente: CONTINUATION, TIME_BUDGET_INPUT, POI_MAX_AGE_INPUT (input della run), REF_NAME (ramo; dall'ambiente perche'
# il nome di un ramo puo' contenere $(...)), GITHUB_REPOSITORY, GH_TOKEN.
set -euo pipefail
source .github/scripts/retry.sh
shopt -s nullglob
[[ "${CONTINUATION:-}" =~ ^[0-9]+$ ]] || CONTINUATION=0
# Rimandate per il budget, e (solo dalla run iniziale, non dalle continuazioni) le fallite:
# un nuovo tentativo, in fondo; se falliscono ancora restano al calendario settimanale.
LIST_FILES=("$RUNNER_TEMP/fragments"/deferred-*.txt)
if [ "$CONTINUATION" -eq 0 ]; then
  LIST_FILES+=("$RUNNER_TEMP/fragments"/failed-*.txt)
fi
if [ "${#LIST_FILES[@]}" -eq 0 ]; then
  echo "-- nessuna regione rimandata o da riprovare"
  exit 0
fi
DEFERRED="$(cut -f1 "${LIST_FILES[@]}" | grep -E '^[a-z0-9-]+$' | sort -u | paste -sd, - || true)"
if [ -z "$DEFERRED" ]; then
  echo "-- nessuna regione rimandata o da riprovare"
  exit 0
fi
NEXT=$((CONTINUATION + 1))
if [ "$NEXT" -gt 4 ]; then
  echo "::warning::regioni rimandate dopo 4 continuazioni, non rilancio (restano al calendario settimanale): $DEFERRED"
  exit 0
fi
ARGS=(-f regions="$DEFERRED" -f continuation="$NEXT")
[ -z "${TIME_BUDGET_INPUT:-}" ] || ARGS+=(-f time_budget_minutes="$TIME_BUDGET_INPUT")
[ -z "${POI_MAX_AGE_INPUT:-}" ] || ARGS+=(-f poi_max_age_days="$POI_MAX_AGE_INPUT")
retry gh workflow run publish-regions.yml --repo "$GITHUB_REPOSITORY" --ref "$REF_NAME" "${ARGS[@]}"
echo "-- continuazione $NEXT lanciata con $(tr ',' '\n' <<< "$DEFERRED" | wc -l) regioni"
echo "Continuazione $NEXT lanciata con le regioni rimandate: $DEFERRED" >> "$GITHUB_STEP_SUMMARY"
