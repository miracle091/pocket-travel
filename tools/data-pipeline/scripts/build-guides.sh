#!/usr/bin/env bash
# Genera guides.db, il pacchetto guide unico per tutte le regioni di pilot-regions.sh (sezioni
# Wikivoyage + numeri di emergenza, via il tool Kotlin generateGuides), e il suo frammento
# manifest.json (voce "guides"). L'app lo scarica una volta per tutte le nazioni, separato dai
# pacchetti per regione (mappa, POI, routing) di build-region.sh, e lo aggiorna da solo: pesa
# meno di un MB compresso.
#
# Uso: build-guides.sh <version> <assetBaseUrl> <outputDir> [publishedManifestUrl]
#
# Scrive sempre <outputDir>/manifest-fragment.json. <outputDir>/guides.db esiste solo se il
# contenuto e' cambiato rispetto al guides.db pubblicato (va caricato come asset
# guides--<version>--guides.db sotto <assetBaseUrl>); altrimenti il frammento riusa la voce gia'
# pubblicata, stessa versione, e l'app non riscarica nulla.
#
# Una pagina Wikivoyage che non si riesce a scaricare in questa run non fa sparire la guida di
# quella regione: generateGuides ricopia le sue sezioni dal guides.db pubblicato.
#
# Richiede: curl, jq (solo se si passa publishedManifestUrl), gradle wrapper dalla root del repo.
set -euo pipefail

if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
  echo "Uso: $0 <version> <assetBaseUrl> <outputDir> [publishedManifestUrl]" >&2
  exit 1
fi

VERSION="$1"
ASSET_BASE_URL="${2%/}"
OUTPUT_DIR="$3"
PUBLISHED_MANIFEST_URL="${4:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"
# shellcheck source=./pilot-regions.sh
source "$SCRIPT_DIR/pilot-regions.sh"

mkdir -p "$OUTPUT_DIR"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

# --- 1. Pagine Wikivoyage di tutte le regioni ---------------------------------------------------
REGIONS_TSV="$WORKDIR/regions.tsv"
: > "$REGIONS_TSV"
FAILED=0
for spec in "${PILOT_REGIONS[@]}"; do
  IFS='|' read -r regionId _ _ _ _ _ wikiTitle _ <<< "$spec"
  dump="$WORKDIR/$regionId.txt"
  if sourceUrl="$(fetch_wikivoyage_dump "$wikiTitle" "$dump")"; then
    printf '%s\t%s\t%s\n' "$regionId" "$(winpath "$dump")" "$sourceUrl" >> "$REGIONS_TSV"
  else
    echo "-- $regionId: pagina Wikivoyage $wikiTitle non scaricata, tengo la guida gia' pubblicata" >&2
    printf '%s\t\t\n' "$regionId" >> "$REGIONS_TSV"
    FAILED=$((FAILED + 1))
  fi
done
if [ "$FAILED" -eq "${#PILOT_REGIONS[@]}" ]; then
  echo "ERRORE: nessuna pagina Wikivoyage scaricata" >&2
  exit 1
fi
echo "-- pagine Wikivoyage: $(( ${#PILOT_REGIONS[@]} - FAILED ))/${#PILOT_REGIONS[@]} scaricate"

# --- 2. guides.db pubblicato (per il confronto e per le regioni non scaricate) ------------------
PUBLISHED_DB=""
PUBLISHED_URL=""
PUBLISHED_VERSION=""
if [ -n "$PUBLISHED_MANIFEST_URL" ] && command -v jq >/dev/null 2>&1; then
  if curl -sSf -o "$WORKDIR/published-manifest.json" "$PUBLISHED_MANIFEST_URL" 2>/dev/null; then
    PUBLISHED_URL="$(jq -r '.guides.file.url // ""' "$WORKDIR/published-manifest.json")"
    PUBLISHED_VERSION="$(jq -r '.guides.version // ""' "$WORKDIR/published-manifest.json")"
    if [ -n "$PUBLISHED_URL" ] && curl -sSfL -o "$WORKDIR/published-guides.db" "$PUBLISHED_URL"; then
      PUBLISHED_DB="$WORKDIR/published-guides.db"
    fi
  fi
fi

# --- 3. guides.db ---------------------------------------------------------------------------------
GUIDES_DB="$OUTPUT_DIR/guides.db"
rm -f "$GUIDES_DB"
cd "$REPO_ROOT"
GUIDES_ARGS="\"$(winpath "$REGIONS_TSV")\" \"$(winpath "$GUIDES_DB")\""
[ -n "$PUBLISHED_DB" ] && GUIDES_ARGS="$GUIDES_ARGS \"$(winpath "$PUBLISHED_DB")\""
./gradlew -q :tools:data-pipeline:content:generateGuides --args="$GUIDES_ARGS"

# --- 4. Frammento manifest ------------------------------------------------------------------------
if [ -f "$GUIDES_DB" ]; then
  FRAGMENT_DB="$GUIDES_DB"
  FRAGMENT_URL="${ASSET_BASE_URL}/guides--${VERSION}--guides.db"
  FRAGMENT_VERSION="$VERSION"
else
  echo "-- guide invariate: riuso la voce pubblicata (versione $PUBLISHED_VERSION)"
  FRAGMENT_DB="$PUBLISHED_DB"
  FRAGMENT_URL="$PUBLISHED_URL"
  FRAGMENT_VERSION="$PUBLISHED_VERSION"
fi
./gradlew -q :tools:data-pipeline:content:generateManifest \
  --args="--guides \"$(winpath "$FRAGMENT_DB")\" \"$FRAGMENT_URL\" \"$FRAGMENT_VERSION\" \"$(winpath "$REGIONS_TSV")\" \"$(winpath "$OUTPUT_DIR/manifest-fragment.json")\""

echo "== guide fatte: $OUTPUT_DIR/manifest-fragment.json =="
