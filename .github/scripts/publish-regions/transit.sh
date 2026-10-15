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
# Download in parallelo e conversione una rete alla volta, appena il suo feed e' arrivato: il download vale circa 60%
# del job (at-toscana da solo ~15 minuti, server lento senza Range), la conversione il resto. I download sono divisi per
# host: le reti dello stesso host si scaricano una dopo l'altra (al massimo un download per host: Mobility Database,
# BODS e gli altri non ricevono raffiche), al massimo 4 host insieme, at-toscana per primo. Un download fallito qui
# si riprova nella conversione (build-transit.sh senza il feed in TRANSIT_PREFETCH_DIR lo scarica da se').
FEEDS_TSV=tools/data-pipeline/transit-feeds.tsv
PREFETCH_DIR="$RUNNER_TEMP/transit-feeds"
LOG_DIR="$RUNNER_TEMP/transit-download-logs"
DONE_LIST="$LOG_DIR/done.txt"
mkdir -p "$PREFETCH_DIR" "$LOG_DIR"
: > "$DONE_LIST"
export TRANSIT_PREFETCH_DIR="$PREFETCH_DIR"
mapfile -t FEED_IDS < <(awk -F'\t' '$0 !~ /^#/ && NF >= 5 { print $1 }' "$FEEDS_TSV")
# Una riga per host con i suoi id: prima il gruppo di at-toscana, poi i gruppi piu' numerosi.
awk -F'\t' '$0 !~ /^#/ && NF >= 5 {
    host = ($7 == "" ? "files.mobilitydatabase.org" : $7); sub(/^[a-z]+:\/\//, "", host); sub(/[\/#].*/, "", host)
    ids[host] = ids[host] " " $1; n[host]++; if ($1 == "at-toscana") first = host }
  END { for (h in ids) print (h == first ? 999999 : n[h]) "\t" ids[h] }' "$FEEDS_TSV" \
  | sort -t$'\t' -k1,1nr | cut -f2 > "$LOG_DIR/groups.txt"
# shellcheck disable=SC2016 # lo script di bash -c riceve i suoi argomenti da xargs
xargs -P 4 -L 1 bash -c 'for id in "${@:3}"; do
    tools/data-pipeline/scripts/build-transit.sh --download-only "$id" "$1" > "$2/$id.log" 2>&1 || true
    echo "$id" >> "$2/done.txt"
  done' _ "$PREFETCH_DIR" "$LOG_DIR" < "$LOG_DIR/groups.txt" &
DOWNLOADS_PID=$!

declare -A BUILT=()
build_feed() {
  local id="$1"
  BUILT[$id]=1
  echo "::group::$id"
  [ ! -s "$LOG_DIR/$id.log" ] || cat "$LOG_DIR/$id.log"
  if ! tools/data-pipeline/scripts/build-transit.sh "$id" "$VERSION" \
    "https://github.com/$GITHUB_REPOSITORY/releases/download/transit-feeds" "$OUT" "$PUBLISHED_ARG"; then
    echo "::warning::rete $id non costruita: resta la voce gia' pubblicata, se c'e'"
    FAILED+=("$id")
    [ ! -s "$PUBLISHED" ] || jq -c --arg id "$id" '.feeds[]? | select(.id == $id)' "$PUBLISHED" >> "$OUT/transit-entries.jsonl"
  fi
  echo "::endgroup::"
}

# Conversione nell'ordine in cui i download finiscono (l'ordine delle voci non conta: transit.json le ordina per id).
# done.txt si legge per indice, cosi' build-transit.sh non riceve lo stdin del ciclo.
next=0
while true; do
  mapfile -t DONE_IDS < "$DONE_LIST"
  if [ "$next" -lt "${#DONE_IDS[@]}" ]; then
    build_feed "${DONE_IDS[$next]}"
    next=$((next + 1))
  elif kill -0 "$DOWNLOADS_PID" 2>/dev/null; then
    sleep 2
  elif [ "$(wc -l < "$DONE_LIST")" -le "$next" ]; then
    break
  fi
done
wait "$DOWNLOADS_PID" || true
# Reti senza download in parallelo (xargs interrotto): build-transit.sh le scarica da se'.
for id in "${FEED_IDS[@]}"; do
  [ -n "${BUILT[$id]:-}" ] || build_feed "$id"
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
