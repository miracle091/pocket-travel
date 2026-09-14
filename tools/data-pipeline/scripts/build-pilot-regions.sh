#!/usr/bin/env bash
# Lancia build-region.sh per l'intero lotto pilota (piano A2, deciso il 2026-09-14) — vedi
# pilot-regions.sh per l'elenco e le note su bbox/Stati Uniti non contigui. Esecuzione locale
# "tutto fresco"; il workflow publish-regions.yml usa lo stesso pilot-regions.sh ma permette di
# selezionare un sottoinsieme e unisce col manifest gia' pubblicato (assemble-site.sh).
#
# Uso: build-pilot-regions.sh <outputDir> [contentDbBaseUrl]
set -euo pipefail

OUTPUT_ROOT="${1:?Uso: $0 <outputDir> [contentDbBaseUrl]}"
CONTENT_DB_BASE_URL="${2:-https://miracle091.github.io/pocket-travel}"
VERSION="$(date -u +%Y.%m.%d)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# shellcheck source=./pilot-regions.sh
source "$SCRIPT_DIR/pilot-regions.sh"
REGIONS=("${PILOT_REGIONS[@]}")

FRAGMENT_FILES=()
for spec in "${REGIONS[@]}"; do
  IFS='|' read -r regionId displayName minLon minLat maxLon maxLat wikiTitle <<< "$spec"
  regionOut="$OUTPUT_ROOT/$regionId"
  "$SCRIPT_DIR/build-region.sh" "$regionId" "$displayName" "$VERSION" \
    "$minLon" "$minLat" "$maxLon" "$maxLat" "$wikiTitle" "$CONTENT_DB_BASE_URL" "$regionOut"
  FRAGMENT_FILES+=("$regionOut/manifest-fragment.json")
done

# --- merge dei frammenti in un unico manifest.json + layout del sito da pubblicare ---------------
SITE_DIR="$OUTPUT_ROOT/site"
mkdir -p "$SITE_DIR"
for spec in "${REGIONS[@]}"; do
  IFS='|' read -r regionId _ _ _ _ _ _ <<< "$spec"
  destDir="$SITE_DIR/regions/$regionId/$VERSION"
  mkdir -p "$destDir"
  cp "$OUTPUT_ROOT/$regionId/content.db" "$destDir/content.db"
done

# gradlew invoca java.exe nativo di Windows: gli argomenti --args vogliono path Windows reali,
# non il path POSIX virtuale di git-bash/MSYS (stesso problema/fix di build-region.sh). Su
# Linux (CI) cygpath non esiste e non serve.
winpath() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
ARGS_STR="\"$(winpath "$SITE_DIR/manifest.json")\""
for f in "${FRAGMENT_FILES[@]}"; do
  ARGS_STR="$ARGS_STR \"$(winpath "$f")\""
done

cd "$REPO_ROOT"
./gradlew -q :tools:data-pipeline:content:mergeManifests --args="$ARGS_STR"

echo "== Lotto pilota completo. Sito pronto in $SITE_DIR (da pubblicare, vedi .github/workflows/publish-regions.yml) =="
