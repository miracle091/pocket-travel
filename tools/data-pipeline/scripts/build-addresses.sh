#!/usr/bin/env bash
# Pacchetto "civici" di UNA regione, da lanciare dopo build-region.sh sulla stessa <outputDir>:
# aggiunge al manifest-fragment.json gia' scritto la voce "addresses" (facoltativa nel manifest) e,
# se serve un file nuovo, lascia <outputDir>/addresses.pmtiles da caricare.
#
# I civici stanno nelle tile z15 di Protomaps (layer buildings, kind=address), mentre la mappa
# dell'app si ferma a z14: qui si estraggono le sole z15 del bbox con go-pmtiles (poche richieste
# HTTP a blocchi), GenerateAddresses.kt ne tiene solo punto e numero e li riscrive in un PMTiles a
# z14 che l'app sovrappone alla mappa. Le z15 restano sul runner e vengono cancellate subito.
#
# Mai fatale per la regione: se qualcosa va storto la regione resta con la voce gia' pubblicata
# (o senza civici) e lo script esce 0 con un avviso.
#
# Uso:
#   build-addresses.sh <regionId> <version> <minLon> <minLat> <maxLon> <maxLat> <assetBaseUrl> \
#                      <outputDir> [publishedManifestUrl]
#
# Variabili:
#   ADDRESSES_MAX_AGE_DAYS    (30)   oltre questa eta' il file pubblicato viene rigenerato
#   ADDRESSES_MAX_EXTRACT_MB  (4000) sopra questa dimensione delle z15 la regione resta senza civici
#   ADDRESSES_ATTEMPTS        (3)    tentativi di stima ed estrazione delle z15 (attese di 30 s, poi 2 min)
#   ADDRESSES_REPORT                 se impostata, file a cui aggiungere una riga per ogni regione
#                                    rimasta senza civici nuovi, con il motivo (riepilogo del job)
#   PMTILES_BIN                      eseguibile go-pmtiles (default: "pmtiles" o "go-pmtiles" nel PATH)
#
# Richiede: jq, curl, sha256sum, go-pmtiles, gradle wrapper dalla root del repo.
set -euo pipefail

if [ "$#" -lt 8 ] || [ "$#" -gt 9 ]; then
  echo "Uso: $0 <regionId> <version> <minLon> <minLat> <maxLon> <maxLat> <assetBaseUrl> <outputDir> [publishedManifestUrl]" >&2
  exit 1
fi

REGION_ID="$1"
VERSION="$2"
MIN_LON="$3"
MIN_LAT="$4"
MAX_LON="$5"
MAX_LAT="$6"
ASSET_BASE_URL="${7%/}"
OUTPUT_DIR="$8"
PUBLISHED_MANIFEST_URL="${9:-}"
ADDRESSES_MAX_AGE_DAYS="${ADDRESSES_MAX_AGE_DAYS:-30}"
ADDRESSES_MAX_EXTRACT_MB="${ADDRESSES_MAX_EXTRACT_MB:-4000}"
ADDRESSES_ATTEMPTS="${ADDRESSES_ATTEMPTS:-3}"
ADDRESSES_REPORT="${ADDRESSES_REPORT:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"

FRAGMENT="$OUTPUT_DIR/manifest-fragment.json"
ADDRESSES_FILE="$OUTPUT_DIR/addresses.pmtiles"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

if [ ! -f "$FRAGMENT" ]; then
  echo "ERRORE: $FRAGMENT mancante, lanciare prima build-region.sh" >&2
  exit 1
fi

# Voce "addresses" gia' pubblicata per la regione ("" se assente).
PUBLISHED_ENTRY=""
if [ -n "$PUBLISHED_MANIFEST_URL" ] && curl -sSf -o "$WORKDIR/published.json" "$PUBLISHED_MANIFEST_URL" 2>/dev/null; then
  PUBLISHED_ENTRY="$(jq -c --arg id "$REGION_ID" '[(.regions // [])[] | select(.regionId == $id) | .addresses // empty][0] // empty' "$WORKDIR/published.json")"
fi

set_entry() {
  jq -c --argjson entry "$1" '.regions |= map(.addresses = $entry)' "$FRAGMENT" > "$WORKDIR/fragment.json"
  mv "$WORKDIR/fragment.json" "$FRAGMENT"
}

keep_published() {
  if [ -n "$PUBLISHED_ENTRY" ]; then
    set_entry "$PUBLISHED_ENTRY"
    echo "-- $REGION_ID: civici, tengo la voce pubblicata ($1)"
  else
    echo "-- $REGION_ID: civici non disponibili ($1)"
  fi
}

# Come keep_published, ma per i casi in cui la regione non ha civici nuovi per un problema o un
# limite (non per la voce pubblicata ancora recente): finisce anche nel riepilogo del job.
give_up() {
  keep_published "$1"
  if [ -n "$ADDRESSES_REPORT" ]; then
    local state="senza civici"
    [ -z "$PUBLISHED_ENTRY" ] || state="tenuta la voce pubblicata"
    echo "- \`$REGION_ID\`: $1 ($state)" >> "$ADDRESSES_REPORT"
  fi
}

# Riprova un comando fino a ADDRESSES_ATTEMPTS volte: build.protomaps.com a volte chiude la
# connessione a meta' download (filippine al 96%, finlandia al 70%, run del 2026-09-24).
with_retries() {
  local attempt wait
  for attempt in $(seq 1 "$ADDRESSES_ATTEMPTS"); do
    if "$@"; then
      return 0
    fi
    if [ "$attempt" -lt "$ADDRESSES_ATTEMPTS" ]; then
      wait=$(( 30 * 4 ** (attempt - 1) ))
      echo "-- $REGION_ID: tentativo $attempt/$ADDRESSES_ATTEMPTS fallito, riprovo tra ${wait}s" >&2
      rm -f "$Z15"
      sleep "$wait"
    fi
  done
  return 1
}

# Eta' del file pubblicato, dal nome dell'asset (regionId--YYYY.MM.DD--addresses.pmtiles).
if [ -n "$PUBLISHED_ENTRY" ]; then
  PUBLISHED_DATE="$(printf '%s' "$PUBLISHED_ENTRY" | jq -r '.file.url' | sed -n 's#.*--\([0-9]\{4\}\.[0-9]\{2\}\.[0-9]\{2\}\)--addresses\.pmtiles$#\1#p')"
  if [ -n "$PUBLISHED_DATE" ]; then
    AGE_DAYS="$(( ($(date -u +%s) - $(date -u -d "${PUBLISHED_DATE//./-}" +%s)) / 86400 ))"
    if [ "$AGE_DAYS" -le "$ADDRESSES_MAX_AGE_DAYS" ]; then
      keep_published "$AGE_DAYS giorni, max $ADDRESSES_MAX_AGE_DAYS"
      exit 0
    fi
  fi
fi

PMTILES_BIN="${PMTILES_BIN:-$(command -v pmtiles || command -v go-pmtiles || true)}"
if [ -z "$PMTILES_BIN" ]; then
  echo "::warning::go-pmtiles non trovato: $REGION_ID resta senza civici nuovi"
  give_up "go-pmtiles mancante"
  exit 0
fi

# Stessa build Protomaps della mappa della regione.
SOURCE_URL="$(jq -r '.regions[0].map.source.sourceUrl' "$FRAGMENT")"
BBOX="$MIN_LON,$MIN_LAT,$MAX_LON,$MAX_LAT"
Z15="$WORKDIR/z15.pmtiles"

# Stima prima di scaricare: "... for an archive size of 2.5 MB".
if ! DRY_RUN="$(with_retries "$PMTILES_BIN" extract "$SOURCE_URL" "$Z15" --bbox="$BBOX" --minzoom=15 --maxzoom=15 --dry-run 2>&1)"; then
  echo "::warning::stima delle z15 di $REGION_ID fallita"
  give_up "stima fallita"
  exit 0
fi
EXTRACT_MB="$(printf '%s' "$DRY_RUN" | sed -n 's/.*archive size of \([0-9.]*\) \([kMG]\{0,1\}B\).*/\1 \2/p' | tail -1 \
  | awk '{ f = ($2 == "GB") ? 1024 : ($2 == "MB") ? 1 : ($2 == "kB") ? 1 / 1024 : 1 / 1048576; printf "%d", $1 * f + 0.5 }')"
echo "-- $REGION_ID: z15 da estrarre ~${EXTRACT_MB:-?} MB"
if [ -z "$EXTRACT_MB" ] || [ "$EXTRACT_MB" -gt "$ADDRESSES_MAX_EXTRACT_MB" ]; then
  echo "::warning::$REGION_ID: z15 troppo grandi (${EXTRACT_MB:-?} MB, max $ADDRESSES_MAX_EXTRACT_MB) o stima illeggibile"
  give_up "estrazione troppo grande (${EXTRACT_MB:-?} MB, max $ADDRESSES_MAX_EXTRACT_MB)"
  exit 0
fi

if ! with_retries "$PMTILES_BIN" extract "$SOURCE_URL" "$Z15" --bbox="$BBOX" --minzoom=15 --maxzoom=15; then
  echo "::warning::estrazione delle z15 di $REGION_ID fallita"
  give_up "estrazione fallita dopo $ADDRESSES_ATTEMPTS tentativi"
  exit 0
fi

rm -f "$ADDRESSES_FILE"
cd "$REPO_ROOT"
# Il log di Planetiler puo' precedere la riga con codici colore ANSI: niente ancora a inizio riga.
if ! GENERATED="$(./gradlew -q :tools:data-pipeline:content:generateAddresses \
  --args="\"$(winpath "$ADDRESSES_FILE")\" $MIN_LON $MIN_LAT $MAX_LON $MAX_LAT \"$(winpath "$Z15")\"" | tee /dev/stderr | sed -n 's/.*indirizzi: \([0-9]*\) .*/\1/p')"; then
  rm -f "$Z15" "$ADDRESSES_FILE"
  echo "::warning::generateAddresses fallito per $REGION_ID"
  give_up "generazione fallita"
  exit 0
fi
rm -f "$Z15"
if [ -z "$GENERATED" ] || [ "$GENERATED" -eq 0 ]; then
  rm -f "$ADDRESSES_FILE"
  give_up "nessun civico nelle z15"
  exit 0
fi

HASH="$(sha256sum < "$ADDRESSES_FILE" | awk '{print $1}')"
if [ -n "$PUBLISHED_ENTRY" ] && [ "$HASH" = "$(printf '%s' "$PUBLISHED_ENTRY" | jq -r '.file.sha256')" ]; then
  # Stessi civici: una versione nuova farebbe riscaricare all'app gli stessi dati.
  rm -f "$ADDRESSES_FILE"
  keep_published "file rigenerato identico"
  exit 0
fi

SIZE="$(wc -c < "$ADDRESSES_FILE" | tr -d ' ')"
set_entry "$(jq -n -c --arg version "$VERSION" --arg url "${ASSET_BASE_URL}/${REGION_ID}--${VERSION}--addresses.pmtiles" \
  --argjson size "$SIZE" --arg hash "$HASH" \
  '{version: $version, file: {name: "addresses.pmtiles", url: $url, sizeBytes: $size, sha256: $hash}}')"
# Una regione altrimenti invariata ora ha un file da caricare: il workflow carica solo le regioni
# senza .skipped, e con .incremental solo i file presenti in <outputDir>.
if [ -f "$OUTPUT_DIR/.skipped" ]; then
  rm -f "$OUTPUT_DIR/.skipped"
  : > "$OUTPUT_DIR/.incremental"
fi
echo "== [$REGION_ID] civici: $GENERATED in $SIZE byte, versione $VERSION =="
