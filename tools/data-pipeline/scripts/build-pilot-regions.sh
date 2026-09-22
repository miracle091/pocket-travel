#!/usr/bin/env bash
# Lancia build-region.sh per l'intero lotto pilota (piano A2, deciso il 2026-09-14) — vedi
# pilot-regions.sh per l'elenco e le note su bbox/Stati Uniti non contigui. Esecuzione locale
# "tutto fresco"; il workflow publish-regions.yml usa lo stesso pilot-regions.sh ma permette di
# selezionare un sottoinsieme e unisce col manifest gia' pubblicato (assemble-site.sh).
#
# content.db e i .rd5 non vivono su GitHub Pages (limite di 1GB per l'intero sito, sforato con
# la copertura mondiale di pilot-regions.sh) ma sugli asset della release "region-data" — vedi
# il commento in testa a publish-regions.yml. contentDbBaseUrl va quindi passato coerente con
# quello (default: la release region-data di questo stesso repo).
#
# Uso: build-pilot-regions.sh <outputDir> [contentDbBaseUrl]
set -euo pipefail

OUTPUT_ROOT="${1:?Uso: $0 <outputDir> [contentDbBaseUrl]}"
CONTENT_DB_BASE_URL="${2:-https://github.com/miracle091/pocket-travel/releases/download/region-data}"
VERSION="$(date -u +%Y.%m.%d)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# shellcheck source=./pilot-regions.sh
source "$SCRIPT_DIR/pilot-regions.sh"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"
REGIONS=("${PILOT_REGIONS[@]}")

# Risolta una volta sola per l'intero lotto (non da ogni build-region.sh, vedi lib.sh): stessa
# build Protomaps per tutte le regioni della stessa run.
export PROTOMAPS_DATE_OVERRIDE="$(resolve_protomaps_date)"
echo "== build Protomaps: ${PROTOMAPS_DATE_OVERRIDE}.pmtiles =="

FRAGMENT_FILES=()
for spec in "${REGIONS[@]}"; do
  IFS='|' read -r regionId displayName minLon minLat maxLon maxLat wikiTitle _flag _group _groupLabel _continent <<< "$spec"
  regionOut="$OUTPUT_ROOT/$regionId"
  "$SCRIPT_DIR/build-region.sh" "$regionId" "$displayName" "$VERSION" \
    "$minLon" "$minLat" "$maxLon" "$maxLat" "$wikiTitle" "$CONTENT_DB_BASE_URL" "$regionOut"
  FRAGMENT_FILES+=("$regionOut/manifest-fragment.json")
done

# --- merge dei frammenti in un unico manifest.json + asset pronti per la release -----------------
# SITE_DIR/manifest.json e' cio' che va su GitHub Pages; RELEASE_ASSETS_DIR contiene invece
# content.db e i .rd5 gia' rinominati regionId--version--nomefile (stessa convenzione usata da
# publish-regions.yml per "gh release upload region-data"), pronti per essere caricati a mano con
# lo stesso comando se questo lotto va pubblicato per davvero.
SITE_DIR="$OUTPUT_ROOT/site"
RELEASE_ASSETS_DIR="$OUTPUT_ROOT/release-assets"
mkdir -p "$SITE_DIR" "$RELEASE_ASSETS_DIR"
for spec in "${REGIONS[@]}"; do
  IFS='|' read -r regionId _ _ _ _ _ _ _ _ _ _ <<< "$spec"
  cp "$OUTPUT_ROOT/$regionId/content.db" "$RELEASE_ASSETS_DIR/${regionId}--${VERSION}--content.db"
  # Segmenti .rd5 ri-ospitati insieme a content.db (vedi commento in testa a build-region.sh):
  # zero o piu' a seconda di quante tile 5x5 gradi intersecano il bbox della regione.
  for rd5 in "$OUTPUT_ROOT/$regionId"/*.rd5; do
    [ -e "$rd5" ] || continue
    cp "$rd5" "$RELEASE_ASSETS_DIR/${regionId}--${VERSION}--$(basename "$rd5")"
  done
done

# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"
ARGS_STR="\"$(winpath "$SITE_DIR/manifest.json")\""
for f in "${FRAGMENT_FILES[@]}"; do
  ARGS_STR="$ARGS_STR \"$(winpath "$f")\""
done

cd "$REPO_ROOT"
./gradlew -q :tools:data-pipeline:content:mergeManifests --args="$ARGS_STR"

echo "== Lotto pilota completo =="
echo "   manifest.json (da pubblicare su Pages): $SITE_DIR"
echo "   asset content.db/.rd5 (da caricare con 'gh release upload region-data $RELEASE_ASSETS_DIR/*'): $RELEASE_ASSETS_DIR"
echo "   vedi .github/workflows/publish-regions.yml per il flusso automatico completo (genera anche index.html)"
