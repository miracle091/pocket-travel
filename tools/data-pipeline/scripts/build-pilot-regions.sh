#!/usr/bin/env bash
# Lancia build-region.sh per l'intero lotto pilota — vedi
# pilot-regions.sh per l'elenco e le note su bbox/Stati Uniti non contigui. Esecuzione locale
# "tutto fresco"; il workflow publish-regions.yml usa lo stesso pilot-regions.sh ma permette di
# selezionare un sottoinsieme e unisce col manifest gia' pubblicato (assemble-site.sh).
#
# guides.db, poi.db e i .rd5 non vivono su GitHub Pages (limite di 1GB per l'intero sito, sforato
# con la copertura mondiale di pilot-regions.sh) ma sugli asset delle release "region-data*" —
# vedi il commento in testa a publish-regions.yml. releaseBaseUrl e' il prefisso degli URL di
# download delle release: come nel workflow, le guide vanno su "region-data-guide" e ogni regione
# sulla release data da region_release_tag (pilot-regions.sh). Default: le release di questo repo.
#
# Uso: build-pilot-regions.sh <outputDir> [releaseBaseUrl]
set -euo pipefail

OUTPUT_ROOT="${1:?Uso: $0 <outputDir> [releaseBaseUrl]}"
RELEASE_BASE_URL_PREFIX="${2:-https://github.com/miracle091/pocket-travel/releases/download}"
RELEASE_BASE_URL_PREFIX="${RELEASE_BASE_URL_PREFIX%/}"
# Data + ora: come il suffisso della run nel workflow, non riusa il nome di asset gia' pubblicati
# lo stesso giorno (gli script leggono la data ignorando il suffisso).
VERSION="$(date -u +%Y.%m.%d.%H%M)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# shellcheck source=./pilot-regions.sh
source "$SCRIPT_DIR/pilot-regions.sh"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"
REGIONS=("${PILOT_REGIONS[@]}")

# Risolta una volta sola per l'intero lotto (non da ogni build-region.sh, vedi lib.sh): stessa
# build Protomaps per tutte le regioni della stessa run.
PROTOMAPS_DATE_OVERRIDE="$(resolve_protomaps_date)"
export PROTOMAPS_DATE_OVERRIDE
echo "== build Protomaps: ${PROTOMAPS_DATE_OVERRIDE}.pmtiles =="

# Pacchetto guide unico per tutte le regioni (vedi build-guides.sh), poi i pacchetti per regione.
"$SCRIPT_DIR/build-guides.sh" "$VERSION" "$RELEASE_BASE_URL_PREFIX/region-data-guide" "$OUTPUT_ROOT/guides"
FRAGMENT_FILES=("$OUTPUT_ROOT/guides/manifest-fragment.json")
for spec in "${REGIONS[@]}"; do
  IFS='|' read -r regionId displayName minLon minLat maxLon maxLat _wikiTitle _flag _group _groupLabel continent <<< "$spec"
  regionOut="$OUTPUT_ROOT/$regionId"
  "$SCRIPT_DIR/build-region.sh" "$regionId" "$displayName" "$VERSION" \
    "$minLon" "$minLat" "$maxLon" "$maxLat" "$RELEASE_BASE_URL_PREFIX/$(region_release_tag "$regionId" "$continent")" "$regionOut"
  FRAGMENT_FILES+=("$regionOut/manifest-fragment.json")
done

# --- merge dei frammenti in un unico manifest.json + asset pronti per la release -----------------
# SITE_DIR/manifest.json e' cio' che va su GitHub Pages; RELEASE_ASSETS_DIR/<tag> contiene invece
# gli asset della release <tag> gia' rinominati <id>--version--nomefile (stessa convenzione e stessi
# file compressi caricati da publish-regions.yml), pronti per essere caricati a mano se questo lotto
# va pubblicato per davvero.
SITE_DIR="$OUTPUT_ROOT/site"
RELEASE_ASSETS_DIR="$OUTPUT_ROOT/release-assets"
mkdir -p "$SITE_DIR" "$RELEASE_ASSETS_DIR/region-data-guide"
cp "$OUTPUT_ROOT/guides/guides.db.xz" "$RELEASE_ASSETS_DIR/region-data-guide/guides--${VERSION}--guides.db.xz"
for spec in "${REGIONS[@]}"; do
  IFS='|' read -r regionId _ _ _ _ _ _ _ _ _ continent <<< "$spec"
  tagDir="$RELEASE_ASSETS_DIR/$(region_release_tag "$regionId" "$continent")"
  mkdir -p "$tagDir"
  # poi-extra.db.xz e preview.pmtiles.xz solo se build-region.sh li ha prodotti.
  for xz in poi.db.xz poi-extra.db.xz preview.pmtiles.xz; do
    [ -f "$OUTPUT_ROOT/$regionId/$xz" ] || continue
    cp "$OUTPUT_ROOT/$regionId/$xz" "$tagDir/${regionId}--${VERSION}--$xz"
  done
  # Segmenti .rd5 ri-ospitati insieme a poi.db (vedi commento in testa a build-region.sh):
  # zero o piu' a seconda di quante tile 5x5 gradi intersecano il bbox della regione.
  for rd5 in "$OUTPUT_ROOT/$regionId"/*.rd5; do
    [ -e "$rd5" ] || continue
    cp "$rd5" "$tagDir/${regionId}--${VERSION}--$(basename "$rd5")"
  done
done

ARGS_STR="\"$(winpath "$SITE_DIR/manifest.json")\""
for f in "${FRAGMENT_FILES[@]}"; do
  ARGS_STR="$ARGS_STR \"$(winpath "$f")\""
done

cd "$REPO_ROOT"
./gradlew -q :tools:data-pipeline:content:mergeManifests --args="$ARGS_STR"

echo "== Lotto pilota completo =="
echo "   manifest.json (da pubblicare su Pages): $SITE_DIR"
echo "   asset per release (da caricare con 'gh release upload <tag> $RELEASE_ASSETS_DIR/<tag>/*'): $RELEASE_ASSETS_DIR"
echo "   vedi .github/workflows/publish-regions.yml per il flusso automatico completo (genera anche index.html)"
