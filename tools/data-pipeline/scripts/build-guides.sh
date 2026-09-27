#!/usr/bin/env bash
# Genera guides.db, il pacchetto guide unico per tutte le regioni di pilot-regions.sh (sezioni
# Wikivoyage + numeri di emergenza, via il tool Kotlin generateGuides), e il suo frammento
# manifest.json (voce "guides"). L'app lo scarica una volta per tutte le nazioni, separato dai
# pacchetti per regione (mappa, POI, routing) di build-region.sh, e lo aggiorna da solo: pesa
# meno di un MB compresso.
#
# Uso: build-guides.sh <version> <assetBaseUrl> <outputDir> [publishedManifestUrl]
#
# Scrive sempre <outputDir>/manifest-fragment.json. <outputDir>/guides.db e guides.db.xz esistono
# solo se il contenuto e' cambiato rispetto al guides.db pubblicato (guides.db.xz va caricato come
# asset guides--<version>--guides.db.xz sotto <assetBaseUrl> - unico asset pubblicato, stesso
# trattamento di poi.db/preview.pmtiles in build-region.sh: guides.db 6,3 MB -> 1,5 MB con xz, vedi
# xz_entry in lib.sh); altrimenti il frammento riusa la voce gia' pubblicata, stessa versione, e
# l'app non riscarica nulla.
#
# Una pagina Wikivoyage che non si riesce a scaricare in questa run non fa sparire la guida di
# quella regione: generateGuides ricopia le sue sezioni dal guides.db pubblicato.
#
# Richiede: curl, jq (solo se si passa publishedManifestUrl), xz, gradle wrapper dalla root del repo.
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
    # Pagina italiana molto corta (una vera pagina paese e' 20-130 KB; la Siberia, solo titoli, 4
    # KB): scarico anche quella inglese, e generateGuides la usa se l'italiana non da' sezioni.
    # Solo sotto soglia, per non raddoppiare le richieste a Wikimedia.
    dumpEn="$WORKDIR/$regionId.en.txt"
    if [[ "$sourceUrl" == https://it.* ]] && [ "$(wc -c < "$dump")" -lt 8000 ] \
      && sourceUrlEn="$(fetch_wikivoyage_en_dump "$wikiTitle" "$dumpEn")"; then
      printf '%s\t%s\t%s\t%s\t%s\n' "$regionId" "$(winpath "$dump")" "$sourceUrl" "$(winpath "$dumpEn")" "$sourceUrlEn" >> "$REGIONS_TSV"
    else
      printf '%s\t%s\t%s\n' "$regionId" "$(winpath "$dump")" "$sourceUrl" >> "$REGIONS_TSV"
    fi
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
PUBLISHED_XZ_JSON=""
if [ -n "$PUBLISHED_MANIFEST_URL" ] && command -v jq >/dev/null 2>&1; then
  if curl -sSf -o "$WORKDIR/published-manifest.json" "$PUBLISHED_MANIFEST_URL" 2>/dev/null; then
    PUBLISHED_URL="$(jq -r '.guides.file.url // ""' "$WORKDIR/published-manifest.json")"
    PUBLISHED_VERSION="$(jq -r '.guides.version // ""' "$WORKDIR/published-manifest.json")"
    PUBLISHED_XZ_JSON="$(jq -c '.guides.fileXz // empty' "$WORKDIR/published-manifest.json")"
    if [ -n "$PUBLISHED_URL" ]; then
      # guides.file.url punta al .xz pubblicato (stesso url di fileXz, vedi sezione 4): lo si
      # decomprime dopo il download per riottenere il database vero, letto piu' sotto da
      # generateGuides. Se punta ancora a un guides.db grezzo (manifest nello schema precedente,
      # prima di questo .xz) si usa cosi' com'e'.
      DOWNLOAD_TARGET="$WORKDIR/published-guides.db"
      [[ "$PUBLISHED_URL" == *.xz ]] && DOWNLOAD_TARGET="$WORKDIR/published-guides.db.xz"
      if curl -sSfL -o "$DOWNLOAD_TARGET" "$PUBLISHED_URL"; then
        if [[ "$PUBLISHED_URL" == *.xz ]]; then
          xz -dc "$DOWNLOAD_TARGET" > "$WORKDIR/published-guides.db" && PUBLISHED_DB="$WORKDIR/published-guides.db"
        else
          PUBLISHED_DB="$DOWNLOAD_TARGET"
        fi
      fi
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
# guides.db si pubblica solo compresso (guides--<version>--guides.db.xz, come poi.db/preview.pmtiles
# in build-region.sh): "file" e "fileXz" condividono lo stesso url, "file" descrive pero' il
# contenuto decompresso (nome/dimensione/sha256 che l'app ricontrolla dopo il download).
GUIDES_XZ_URL="${ASSET_BASE_URL}/guides--${VERSION}--guides.db.xz"
if [ -f "$GUIDES_DB" ]; then
  FRAGMENT_DB="$GUIDES_DB"
  FRAGMENT_URL="$GUIDES_XZ_URL"
  FRAGMENT_VERSION="$VERSION"
else
  echo "-- guide invariate: riuso la voce pubblicata (versione $PUBLISHED_VERSION)"
  FRAGMENT_DB="$PUBLISHED_DB"
  FRAGMENT_URL="$PUBLISHED_URL"
  FRAGMENT_VERSION="$PUBLISHED_VERSION"
fi
./gradlew -q :tools:data-pipeline:content:generateManifest \
  --args="--guides \"$(winpath "$FRAGMENT_DB")\" \"$FRAGMENT_URL\" \"$FRAGMENT_VERSION\" \"$(winpath "$REGIONS_TSV")\" \"$(winpath "$OUTPUT_DIR/manifest-fragment.json")\""

if [ -f "$GUIDES_DB" ]; then
  # Contenuto nuovo: comprime e aggiunge la voce fileXz (vedi xz_entry in lib.sh).
  GUIDES_XZ_JSON="$(xz_entry "$GUIDES_DB" "$GUIDES_XZ_URL")"
  jq -c --argjson xz "$GUIDES_XZ_JSON" '.guides.fileXz = $xz' "$OUTPUT_DIR/manifest-fragment.json" > "$WORKDIR/fragment.json"
  mv "$WORKDIR/fragment.json" "$OUTPUT_DIR/manifest-fragment.json"
elif [ -n "$PUBLISHED_XZ_JSON" ] && [ "$PUBLISHED_XZ_JSON" != "null" ]; then
  # Guide invariate: si riporta avanti la voce fileXz gia' pubblicata invece di ricomprimere.
  jq -c --argjson xz "$PUBLISHED_XZ_JSON" '.guides.fileXz = $xz' "$OUTPUT_DIR/manifest-fragment.json" > "$WORKDIR/fragment.json"
  mv "$WORKDIR/fragment.json" "$OUTPUT_DIR/manifest-fragment.json"
fi

echo "== guide fatte: $OUTPUT_DIR/manifest-fragment.json =="
