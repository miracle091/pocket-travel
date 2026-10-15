#!/usr/bin/env bash
# Job "transit" di publish-regions.yml: costruisce le reti dei mezzi pubblici di tools/data-pipeline/transit-feeds.tsv
# (build-transit.sh), le carica sulla release "transit-feeds" e scrive l'indice transit.json e il suo frammento di
# catalogo ($RUNNER_TEMP/transit-index/transit.json, $RUNNER_TEMP/fragments/transit.json). Una rete che fallisce
# tiene la voce gia' pubblicata.
# Ambiente: PAGES_BASE_URL, GITHUB_REPOSITORY, GITHUB_RUN_NUMBER, GITHUB_RUN_ATTEMPT, GH_TOKEN.
set -euo pipefail
source .github/scripts/retry.sh
# shellcheck source=tools/data-pipeline/scripts/lib.sh
source tools/data-pipeline/scripts/lib.sh
INDEX_URL="$PAGES_BASE_URL/transit.json"
PUBLISHED="$RUNNER_TEMP/published-transit.json"
PUBLISHED_HTTP="$(fetch_published_manifest "$INDEX_URL" "$PUBLISHED")"
if [ "$PUBLISHED_HTTP" = "404" ]; then
  rm -f "$PUBLISHED"
elif [ "$PUBLISHED_HTTP" != "200" ]; then
  echo "ERRORE: transit.json non scaricabile da $INDEX_URL (HTTP ${PUBLISHED_HTTP:-000})" >&2
  exit 1
fi
VERSION="$(date -u +%Y.%m.%d).$GITHUB_RUN_NUMBER.$GITHUB_RUN_ATTEMPT"
OUT="$RUNNER_TEMP/transit-out"
mkdir -p "$OUT" "$RUNNER_TEMP/fragments" "$RUNNER_TEMP/transit-index"
FAILED=()
PUBLISHED_ARG=""
[ ! -s "$PUBLISHED" ] || PUBLISHED_ARG="$PUBLISHED"
# Gli id sono parole senza spazi; un ciclo "while read" darebbe a build-transit.sh lo stdin del ciclo.
# shellcheck disable=SC2013
for id in $(awk -F'\t' '$0 !~ /^#/ && NF >= 5 { print $1 }' tools/data-pipeline/transit-feeds.tsv); do
  echo "::group::$id"
  if ! tools/data-pipeline/scripts/build-transit.sh "$id" "$VERSION" \
    "https://github.com/$GITHUB_REPOSITORY/releases/download/transit-feeds" "$OUT" "$PUBLISHED_ARG"; then
    echo "::warning::rete $id non costruita: resta la voce gia' pubblicata, se c'e'"
    FAILED+=("$id")
    [ ! -s "$PUBLISHED" ] || jq -c --arg id "$id" '.feeds[]? | select(.id == $id)' "$PUBLISHED" >> "$OUT/transit-entries.jsonl"
  fi
  echo "::endgroup::"
done
shopt -s nullglob
ASSETS=("$OUT"/*--transit.db "$OUT"/*--transit.db.xz)
if [ "${#ASSETS[@]}" -gt 0 ]; then
  retry gh release upload transit-feeds "${ASSETS[@]}" --repo "$GITHUB_REPOSITORY" --clobber
fi
if [ ! -s "$OUT/transit-entries.jsonl" ]; then
  echo "Nessuna rete da pubblicare" >&2
  exit 1
fi
INDEX="$RUNNER_TEMP/transit-index/transit.json"
# publishedAt (secondi Unix) dentro i byte firmati e coperti dallo sha256 del frammento: l'app rifiuta
# un valore piu' basso dell'ultimo accettato (niente ritorno a una vecchia coppia file + .sig).
# missing: le reti escluse di transit-missing.tsv, che l'app mostra come "Non incluse" con il motivo.
MISSING="$(jq -Rn '[inputs | select(length > 0 and (startswith("#") | not)) | split("\t")
  | {id: .[0], regions: (.[1] | split(",")), name: .[2], reason: .[3]}]' tools/data-pipeline/transit-missing.tsv)"
jq -s --arg v "$VERSION" --argjson at "$(date +%s)" --argjson missing "$MISSING" \
  '{version: $v, publishedAt: $at, feeds: (unique_by(.id)), missing: $missing}' "$OUT/transit-entries.jsonl" > "$INDEX"
jq -n --arg v "$VERSION" --arg url "$INDEX_URL" --argjson size "$(stat -c %s "$INDEX")" \
  --arg sha "$(sha256sum "$INDEX" | cut -d' ' -f1)" \
  '{manifestVersion: 2, transit: {version: $v, url: $url, sizeBytes: $size, sha256: $sha}, regions: []}' \
  > "$RUNNER_TEMP/fragments/transit.json"
{
  echo "### Reti dei mezzi pubblici"
  jq -r '.feeds[] | "- \(.id) \(.name): valido fino al \(.validUntil), versione \(.version)"' "$INDEX"
  [ "${#FAILED[@]}" -eq 0 ] || echo "Non costruite (resta la voce pubblicata): ${FAILED[*]}"
} >> "$GITHUB_STEP_SUMMARY"
