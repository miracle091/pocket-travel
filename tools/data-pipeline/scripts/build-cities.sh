#!/usr/bin/env bash
# Pacchetto "citta'" di UNA regione (fase 1 di rag-knowledge-plan.md), da lanciare dopo
# build-region.sh sulla stessa <outputDir> e dopo build-cities-dump.sh (che scrive
# <citiesJsonlDir>/<regionId>.cities.jsonl con un solo passaggio sul dump per tutte le regioni):
# aggiunge al manifest-fragment.json gia' scritto la voce "cities" (facoltativa nel manifest) e,
# se il contenuto e' cambiato, lascia <outputDir>/cities.db.xz da caricare - unico asset
# pubblicato, stesso trattamento di addresses.pmtiles in build-addresses.sh (xz_entry in lib.sh):
# "file" descrive il database decompresso (nome/dimensione/sha256 che l'app ricontrolla dopo il
# download), "fileXz" il file da scaricare, stesso url per entrambi.
#
# Le city_sections (citta', categoria, titolo, corpo, sourceUrl) sono generate dal tool Kotlin
# generateCities, che pulisce il wikitext con la stessa cleanBody delle guide
# (GenerateGuideContent.kt, via parseWikivoyageDump) e mappa i titoli di sezione delle citta' sulle
# categorie del contratto (vedi .claude/docs/rag-knowledge-plan.md).
#
# Mai fatale per la regione: se il dump non e' stato estratto in questo run (nessun
# <regionId>.cities.jsonl, es. build-cities-dump.sh fallito o non lanciato) o generateCities non
# produce nessuna sezione, la regione resta con la voce gia' pubblicata (o senza citta') e lo
# script esce 0 con un avviso.
#
# Uso:
#   build-cities.sh <regionId> <version> <assetBaseUrl> <outputDir> <citiesJsonlDir> \
#                    [publishedManifestUrl]
#
# Richiede: jq, curl, sha256sum, xz, gradle wrapper dalla root del repo.
set -euo pipefail

if [ "$#" -lt 5 ] || [ "$#" -gt 6 ]; then
  echo "Uso: $0 <regionId> <version> <assetBaseUrl> <outputDir> <citiesJsonlDir> [publishedManifestUrl]" >&2
  exit 1
fi

REGION_ID="$1"
VERSION="$2"
ASSET_BASE_URL="${3%/}"
OUTPUT_DIR="$4"
CITIES_JSONL_DIR="$5"
PUBLISHED_MANIFEST_URL="${6:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"

FRAGMENT="$OUTPUT_DIR/manifest-fragment.json"
CITIES_FILE="$OUTPUT_DIR/cities.db"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

if [ ! -f "$FRAGMENT" ]; then
  echo "ERRORE: $FRAGMENT mancante, lanciare prima build-region.sh" >&2
  exit 1
fi

# Voce "cities" gia' pubblicata per la regione ("" se assente). Stesso trattamento degli errori di
# rete di build-addresses.sh: un manifest non scaricabile e' un errore (non "nessuna citta'"), solo
# un 404 (prima pubblicazione) vale come "nessuna voce pubblicata".
PUBLISHED_ENTRY=""
if [ -n "$PUBLISHED_MANIFEST_URL" ]; then
  PUBLISHED_HTTP="$(fetch_published_manifest "$PUBLISHED_MANIFEST_URL" "$WORKDIR/published.json")"
  if [ "$PUBLISHED_HTTP" = "200" ]; then
    PUBLISHED_ENTRY="$(jq -c --arg id "$REGION_ID" '[(.regions // [])[] | select(.regionId == $id) | .cities // empty][0] // empty' "$WORKDIR/published.json")"
  elif [ "$PUBLISHED_HTTP" != "404" ]; then
    echo "ERRORE: manifest pubblicato non scaricabile da $PUBLISHED_MANIFEST_URL (HTTP ${PUBLISHED_HTTP:-000})" >&2
    exit 1
  fi
fi

set_entry() {
  jq -c --argjson entry "$1" '.regions |= map(.cities = $entry)' "$FRAGMENT" > "$WORKDIR/fragment.json"
  mv "$WORKDIR/fragment.json" "$FRAGMENT"
}

keep_published() {
  if [ -n "$PUBLISHED_ENTRY" ]; then
    set_entry "$PUBLISHED_ENTRY"
    echo "-- $REGION_ID: citta', tengo la voce pubblicata ($1)"
  else
    echo "-- $REGION_ID: citta' non disponibili ($1)"
  fi
}

JSONL="$CITIES_JSONL_DIR/${REGION_ID}.cities.jsonl"
if [ ! -f "$JSONL" ]; then
  keep_published "nessun dump estratto per questa regione in questo run"
  exit 0
fi

rm -f "$CITIES_FILE"
cd "$REPO_ROOT"
if ! GENERATED="$(./gradlew -q :tools:data-pipeline:content:generateCities \
  --args="\"$(winpath "$JSONL")\" \"$(winpath "$CITIES_FILE")\"" | tee /dev/stderr | sed -n 's/.*citta.: \([0-9]*\) .*/\1/p')"; then
  rm -f "$CITIES_FILE"
  echo "::warning::generateCities fallito per $REGION_ID"
  keep_published "generazione fallita"
  exit 0
fi
if [ -z "$GENERATED" ] || [ "$GENERATED" -eq 0 ] || [ ! -f "$CITIES_FILE" ]; then
  rm -f "$CITIES_FILE"
  keep_published "nessuna sezione nelle citta' della regione"
  exit 0
fi

HASH="$(sha256sum < "$CITIES_FILE" | awk '{print $1}')"
if [ -n "$PUBLISHED_ENTRY" ] && [ "$HASH" = "$(printf '%s' "$PUBLISHED_ENTRY" | jq -r '.file.sha256')" ]; then
  # Stesse citta': una versione nuova farebbe riscaricare all'app gli stessi dati.
  rm -f "$CITIES_FILE"
  keep_published "file rigenerato identico"
  exit 0
fi

SIZE="$(wc -c < "$CITIES_FILE" | tr -d ' ')"
CITIES_XZ_URL="${ASSET_BASE_URL}/${REGION_ID}--${VERSION}--cities.db.xz"
CITIES_XZ_JSON="$(xz_entry "$CITIES_FILE" "$CITIES_XZ_URL")"
set_entry "$(jq -n -c --arg version "$VERSION" --arg url "$CITIES_XZ_URL" \
  --argjson size "$SIZE" --arg hash "$HASH" --argjson xz "$CITIES_XZ_JSON" \
  '{version: $version, file: {name: "cities.db", url: $url, sizeBytes: $size, sha256: $hash}, fileXz: $xz}')"
# Una regione altrimenti invariata ora ha un file da caricare: il workflow carica solo le regioni
# senza .skipped, e con .incremental solo i file presenti in <outputDir> (vedi build-region.sh).
if [ -f "$OUTPUT_DIR/.skipped" ]; then
  rm -f "$OUTPUT_DIR/.skipped"
  : > "$OUTPUT_DIR/.incremental"
fi
echo "== [$REGION_ID] citta': $GENERATED sezioni in $SIZE byte, versione $VERSION =="
