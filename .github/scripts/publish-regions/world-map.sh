#!/usr/bin/env bash
# Job "world-map" di publish-regions.yml: rigenera la mappa del mondo (z0-8, ~555 MB, release "world-map") solo se
# quella pubblicata ha piu' di WORLD_MAP_MAX_AGE_DAYS giorni o manca; scrive changed=true|false in $GITHUB_OUTPUT e,
# se l'ha rigenerata, il frammento di catalogo in $RUNNER_TEMP/fragments/world-map.json.
# Ambiente: PAGES_BASE_URL, WORLD_MAP_MAX_AGE_DAYS, GITHUB_REPOSITORY, GH_TOKEN.
set -euo pipefail
source .github/scripts/env.sh
: "${WORLD_MAP_MAX_AGE_DAYS:=30}"  # fuori dal workflow, lo stesso valore
source .github/scripts/retry.sh
# shellcheck source=tools/data-pipeline/scripts/lib.sh
source tools/data-pipeline/scripts/lib.sh
PROTOMAPS_DATE="$(resolve_protomaps_date)"
echo "-- build Protomaps: ${PROTOMAPS_DATE}.pmtiles"

# Si rigenera (550 MB) solo con un 404 (nessun `manifest.json`) o se la mappa pubblicata e' vecchia: un errore
# di rete su `manifest.json` non deve farla rigenerare, si riprova alla prossima run.
PUBLISHED_DATE=""
PUBLISHED_HTTP="$(fetch_published_manifest "$PAGES_BASE_URL/manifest.json" "$RUNNER_TEMP/published-manifest.json")"
if [ "$PUBLISHED_HTTP" = "200" ]; then
  PUBLISHED_DATE="$(jq -r '.worldMap.version // ""' "$RUNNER_TEMP/published-manifest.json")"
elif [ "$PUBLISHED_HTTP" != "404" ]; then
  echo "::warning::manifest.json pubblicato non scaricabile (HTTP ${PUBLISHED_HTTP:-000}): non rigenero la mappa del mondo in questa run"
  echo "changed=false" >> "$GITHUB_OUTPUT"
  exit 0
fi
if [ -n "$PUBLISHED_DATE" ]; then
  AGE_DAYS="$(( ($(date -u +%s) - $(date -u -d "$PUBLISHED_DATE" +%s)) / 86400 ))"
  if [ "$AGE_DAYS" -le "$WORLD_MAP_MAX_AGE_DAYS" ]; then
    echo "-- mappa del mondo pubblicata il $PUBLISHED_DATE, $AGE_DAYS giorni fa (max $WORLD_MAP_MAX_AGE_DAYS): non la rigenero"
    echo "changed=false" >> "$GITHUB_OUTPUT"
    exit 0
  fi
fi

ensure_release world-map \
  "Mappa del mondo" \
  "Mappa del mondo a bassa risoluzione (z0-8) per l'app Pocket Travel, letta online a pezzi. Non e' una release software."

# Estratta direttamente col nome dell'asset: niente copia di un file da ~555 MB
ASSET_NAME="world-${PROTOMAPS_DATE}-z8.pmtiles"
ASSET="$RUNNER_TEMP/$ASSET_NAME"
# Fino a 3 tentativi: build.protomaps.com a volte chiude la connessione a meta' download
# (stesso problema dei civici, vedi with_retries in build-address-cell.sh).
for attempt in 1 2 3; do
  rm -f "$ASSET"
  pmtiles extract "https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles" "$ASSET" --maxzoom=8 && break
  [ "$attempt" -lt 3 ] || { echo "Estrazione della mappa del mondo fallita dopo 3 tentativi" >&2; exit 1; }
  echo "-- estrazione della mappa del mondo fallita (tentativo $attempt/3), riprovo tra $((attempt * 60))s"
  sleep $((attempt * 60))
done
retry gh release upload world-map "$ASSET" --repo "$GITHUB_REPOSITORY" --clobber

SIZE="$(wc -c < "$ASSET" | tr -d ' ')"
mkdir -p "$RUNNER_TEMP/fragments"
jq -n -c --arg version "$PROTOMAPS_DATE" \
  --arg url "https://github.com/$GITHUB_REPOSITORY/releases/download/world-map/$ASSET_NAME" --argjson size "$SIZE" \
  '{manifestVersion: 2, worldMap: {version: $version, maxZoom: 8, url: $url, sizeBytes: $size}, regions: []}' \
  > "$RUNNER_TEMP/fragments/world-map.json"
echo "changed=true" >> "$GITHUB_OUTPUT"
