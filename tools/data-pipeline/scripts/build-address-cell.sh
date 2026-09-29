#!/usr/bin/env bash
# Costruisce (o mantiene) UNA cella della griglia adattiva dei civici:
# OSM (z15 Protomaps del bbox della cella, riserva
# Overpass se sono troppe) + Overture (query DuckDB filtrata sulla lista bianca
# tools/data-pipeline/overture-address-sources.tsv), deduplicati e ritagliati sulla cella da
# GenerateAddresses (--overture, --cell). Se il file .xz supera ADDRESS_CELL_MAX_BYTES la build
# pubblica i 4 figli al suo posto (solo quelli con dati), ricorsivamente fino a z=14 (una singola
# tile, non divisibile oltre): la' la cella resta piu' pesante del tetto (contratto della griglia,
# vedi ValidateManifest). Una cella foglia rigenerata con lo stesso sha256 di quella gia' pubblicata tiene versione e
# asset pubblicati (nessun upload, nessun nuovo download per l'app che l'ha gia').
#
# Mai fatale per il chiamante: una cella senza dati (ne' OSM ne' Overture, dopo la deduplica) non
# produce nessuna voce, non e' un errore.
#
# Uso:
#   build-address-cell.sh <z/x/y> <version> <protomapsSourceUrl> <assetBaseUrl> <outputDir> \
#                          [publishedIndexUrl]
#
# Scrive in <outputDir> (mai svuotata: piu' celle dello stesso shard ci scrivono in sequenza):
#   cell-<z>-<x>-<y>--<version>--addresses.pmtiles[.xz]   per ogni cella foglia nuova o cambiata
#   address-grid-entries.jsonl                            una riga JSON per cella foglia (nuova o
#                                                          riusata dall'indice pubblicato), stesso
#                                                          schema delle voci "cells[]" di
#                                                          address-grid.json
#
# Variabili:
#   ADDRESS_CELL_MAX_BYTES      (10000000) tetto del file .xz oltre cui si dividono i 4 figli
#   ADDRESS_CELL_MAX_AGE_DAYS   (30)       eta' (dal campo version pubblicato) oltre cui una cella
#                                          gia' pubblicata si rigenera
#   ADDRESS_CELL_MAX_EXTRACT_MB (500)      sopra questa dimensione delle z15 del bbox della cella si
#                                          prova Overpass (fonte di riserva): una cella e' piccola,
#                                          il limite e' molto piu' basso che per un riquadro regionale
#   ADDRESS_CELL_OVERPASS_MAX   (200000)   civici massimi accettati dalla fonte di riserva Overpass
#   ADDRESSES_ATTEMPTS          (3)        tentativi di stima ed estrazione delle z15
#   OVERTURE_WHITELIST                     lista bianca dei dataset (default:
#                                          tools/data-pipeline/overture-address-sources.tsv)
#   OVERTURE_RELEASE                       rilascio Overture da interrogare (default: il piu' recente,
#                                          risolto da scripts/overture_addresses.py)
#   PYTHON_BIN                             eseguibile python col pacchetto duckdb (default:
#                                          python3/python nel PATH; senza, si salta Overture e si
#                                          continua con i soli civici OSM)
#   PMTILES_BIN                            eseguibile go-pmtiles (default: pmtiles/go-pmtiles nel PATH)
#
# Richiede: jq, curl, sha256sum, xz, go-pmtiles, gradle wrapper dalla root del repo (Overpass e
# Overture sono fonti facoltative: la loro assenza non blocca la cella).
set -euo pipefail

if [ "$#" -lt 5 ] || [ "$#" -gt 6 ]; then
  echo "Uso: $0 <z/x/y> <version> <protomapsSourceUrl> <assetBaseUrl> <outputDir> [publishedIndexUrl]" >&2
  exit 1
fi

CELL_ID="$1"
VERSION="$2"
SOURCE_URL="$3"
ASSET_BASE_URL="${4%/}"
OUTPUT_DIR="$5"
PUBLISHED_INDEX_URL="${6:-}"
ADDRESS_CELL_MAX_BYTES="${ADDRESS_CELL_MAX_BYTES:-10000000}"
ADDRESS_CELL_MAX_AGE_DAYS="${ADDRESS_CELL_MAX_AGE_DAYS:-30}"
ADDRESS_CELL_MAX_EXTRACT_MB="${ADDRESS_CELL_MAX_EXTRACT_MB:-500}"
ADDRESS_CELL_OVERPASS_MAX="${ADDRESS_CELL_OVERPASS_MAX:-200000}"
ADDRESSES_ATTEMPTS="${ADDRESSES_ATTEMPTS:-3}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"

OVERTURE_WHITELIST="${OVERTURE_WHITELIST:-$REPO_ROOT/tools/data-pipeline/overture-address-sources.tsv}"
PYTHON_BIN="${PYTHON_BIN:-$(command -v python3 || command -v python || true)}"
PMTILES_BIN="${PMTILES_BIN:-$(command -v pmtiles || command -v go-pmtiles || true)}"

if [ -z "$PMTILES_BIN" ]; then
  echo "::warning::go-pmtiles non trovato: cella $CELL_ID saltata"
  exit 0
fi

mkdir -p "$OUTPUT_DIR"
ENTRIES_FILE="$OUTPUT_DIR/address-grid-entries.jsonl"
touch "$ENTRIES_FILE"

RUN_WORKDIR="$(mktemp -d)"
trap 'rm -rf "$RUN_WORKDIR"' EXIT

# Indice pubblicato (una volta sola per l'intero albero della cella): per l'eta' e per riusare
# versione/asset di una cella foglia invariata. "" (file vuoto) se non c'e' ancora nessun indice.
PUBLISHED_INDEX="$RUN_WORKDIR/published-index.json"
if [ -n "$PUBLISHED_INDEX_URL" ]; then
  PUBLISHED_HTTP="$(fetch_published_manifest "$PUBLISHED_INDEX_URL" "$PUBLISHED_INDEX")"
  if [ "$PUBLISHED_HTTP" != "200" ] && [ "$PUBLISHED_HTTP" != "404" ]; then
    echo "ERRORE: indice pubblicato non scaricabile da $PUBLISHED_INDEX_URL (HTTP ${PUBLISHED_HTTP:-000})" >&2
    exit 1
  fi
  [ "$PUBLISHED_HTTP" = "200" ] || : > "$PUBLISHED_INDEX"
else
  : > "$PUBLISHED_INDEX"
fi

published_entry() {
  local id="$1"
  [ -s "$PUBLISHED_INDEX" ] || return 0
  jq -c --arg id "$id" '[(.cells // [])[] | select(.id == $id)][0] // empty' "$PUBLISHED_INDEX"
}

# has_published_descendant <z> <x> <y>: vero se l'indice pubblicato ha una cella dentro z/x/y a uno zoom maggiore.
has_published_descendant() {
  [ -s "$PUBLISHED_INDEX" ] || return 1
  jq -e --argjson z "$1" --argjson x "$2" --argjson y "$3" '
    any((.cells // [])[] | .id | split("/") | map(tonumber);
        .[0] > $z and ((.[1] / pow(2; .[0] - $z)) | floor) == $x and ((.[2] / pow(2; .[0] - $z)) | floor) == $y)' \
    "$PUBLISHED_INDEX" > /dev/null
}

# Estrae gli indirizzi OSM del bbox (z15 Protomaps, riserva Overpass se sono troppe): scrive in
# <resultVar> (nameref) il path del .pmtiles o del .tsv da passare a generateAddresses, o "" se la
# cella non ha civici OSM disponibili (non fatale: si continua con Overture da solo).
extract_osm_points() {
  local label="$1" minLon="$2" minLat="$3" maxLon="$4" maxLat="$5" workdir="$6" resultVar="$7"
  local -n result_ref="$resultVar"
  result_ref=""
  local z15="$workdir/z15.pmtiles" bbox="$minLon,$minLat,$maxLon,$maxLat" dryRun extractMb note points
  if dryRun="$(with_retries "$label" "$ADDRESSES_ATTEMPTS" "$z15" "$PMTILES_BIN" extract "$SOURCE_URL" "$z15" --bbox="$bbox" --minzoom=15 --maxzoom=15 --dry-run 2>&1)"; then
    extractMb="$(printf '%s' "$dryRun" | sed -n 's/.*archive size of \([0-9.]*\) \([kMG]\{0,1\}B\).*/\1 \2/p' | tail -1 \
      | awk '{ f = ($2 == "GB") ? 1024 : ($2 == "MB") ? 1 : ($2 == "kB") ? 1 / 1024 : 1 / 1048576; printf "%d", $1 * f + 0.5 }')"
    if [ -n "$extractMb" ] && [ "$extractMb" -le "$ADDRESS_CELL_MAX_EXTRACT_MB" ]; then
      if with_retries "$label" "$ADDRESSES_ATTEMPTS" "$z15" "$PMTILES_BIN" extract "$SOURCE_URL" "$z15" --bbox="$bbox" --minzoom=15 --maxzoom=15; then
        result_ref="$z15"
        return 0
      fi
      echo "-- $label: estrazione z15 fallita, provo Overpass"
    else
      echo "-- $label: z15 troppo grandi (${extractMb:-?} MB, max $ADDRESS_CELL_MAX_EXTRACT_MB), provo Overpass"
    fi
  else
    echo "-- $label: stima delle z15 fallita, provo Overpass"
  fi
  points="$workdir/osm.tsv"
  if fetch_overpass_address_points "$label" "nwr[\"addr:housenumber\"]($minLat,$minLon,$maxLat,$maxLon)" "$ADDRESS_CELL_OVERPASS_MAX" "$points" note; then
    result_ref="$points"
  else
    echo "-- $label: nessun civico OSM disponibile$note"
  fi
}

# Punti Overture del bbox (lista bianca dei dataset), in <outFile>: "" (file vuoto) se la query
# fallisce o python/duckdb non sono disponibili - mai fatale, si continua con i soli civici OSM.
fetch_overture_points() {
  local label="$1" minLon="$2" minLat="$3" maxLon="$4" maxLat="$5" outFile="$6"
  : > "$outFile"
  if [ -z "$PYTHON_BIN" ]; then
    echo "-- $label: python non trovato, salto Overture"
    return 0
  fi
  local args=("$minLon" "$minLat" "$maxLon" "$maxLat" "$OVERTURE_WHITELIST" "$outFile")
  [ -z "${OVERTURE_RELEASE:-}" ] || args+=(--release "$OVERTURE_RELEASE")
  if ! "$PYTHON_BIN" "$SCRIPT_DIR/overture_addresses.py" "${args[@]}"; then
    echo "-- $label: query Overture fallita, continuo con i soli civici OSM"
    : > "$outFile"
  fi
}

# Costruisce la cella z/x/y: scrive una riga in $ENTRIES_FILE (nuova, riusata dall'indice pubblicato,
# o niente se la cella non ha dati) e, se il contenuto e' nuovo, i file cell-*.pmtiles[.xz] in
# $OUTPUT_DIR. Oltre ADDRESS_CELL_MAX_BYTES e sotto z14: richiama se stessa sui 4 figli invece di
# scrivere una voce per questa cella (i figli senza dati restano senza voce, come da contratto).
build_cell() {
  local z="$1" x="$2" y="$3"
  local id="$z/$x/$y" label="cell-$z-$x-$y"

  local published="" publishedVersion publishedDate ageDays
  published="$(published_entry "$id")"
  if [ -n "$published" ]; then
    publishedVersion="$(printf '%s' "$published" | jq -r '.version')"
    publishedDate="$(printf '%s' "$publishedVersion" | sed -n 's/^\([0-9]\{4\}\.[0-9]\{2\}\.[0-9]\{2\}\).*/\1/p')"
    if [ -n "$publishedDate" ]; then
      ageDays="$(( ($(date -u +%s) - $(date -u -d "${publishedDate//./-}" +%s)) / 86400 ))"
      if [ "$ageDays" -le "$ADDRESS_CELL_MAX_AGE_DAYS" ]; then
        echo "-- $id: civici, tengo la voce pubblicata ($ageDays giorni, max $ADDRESS_CELL_MAX_AGE_DAYS)"
        printf '%s\n' "$published" >> "$ENTRIES_FILE"
        return 0
      fi
    fi
  fi

  # Cella gia' divisa nell'indice pubblicato (ci sono solo le figlie): si passa subito alle 4 figlie,
  # che riusano o rifanno le proprie voci, invece di rifare estrazione, Overture e generateAddresses
  # di questa cella solo per riscoprire che supera il tetto. Le celle non si riuniscono mai.
  if [ -z "$published" ] && [ "$z" -lt 14 ] && has_published_descendant "$z" "$x" "$y"; then
    echo "-- $id: gia' divisa nell'indice pubblicato, passo alle 4 figlie"
    build_cell $((z + 1)) $((x * 2)) $((y * 2))
    build_cell $((z + 1)) $((x * 2 + 1)) $((y * 2))
    build_cell $((z + 1)) $((x * 2)) $((y * 2 + 1))
    build_cell $((z + 1)) $((x * 2 + 1)) $((y * 2 + 1))
    return 0
  fi

  local cellWorkdir; cellWorkdir="$(mktemp -d)"
  local minLon minLat maxLon maxLat
  read -r minLon minLat maxLon maxLat <<< "$(cell_bbox "$z" "$x" "$y")"

  local osmInput=""
  extract_osm_points "$label" "$minLon" "$minLat" "$maxLon" "$maxLat" "$cellWorkdir" osmInput

  local overtureTsv="$cellWorkdir/overture.tsv"
  fetch_overture_points "$label" "$minLon" "$minLat" "$maxLon" "$maxLat" "$overtureTsv"

  if [ -z "$osmInput" ] && [ ! -s "$overtureTsv" ]; then
    echo "-- $id: nessun civico (ne' OSM ne' Overture), cella senza dati"
    rm -rf "$cellWorkdir"
    return 0
  fi

  local pmtiles="$cellWorkdir/addresses.pmtiles" generatedLine
  if ! generatedLine="$(
      cd "$REPO_ROOT" && ./gradlew -q :tools:data-pipeline:content:generateAddresses \
        --args="\"$(winpath "$pmtiles")\" $minLon $minLat $maxLon $maxLat ${osmInput:+\"$(winpath "$osmInput")\"} --overture \"$(winpath "$overtureTsv")\" --cell \"$id\"" \
        | tee /dev/stderr | sed -n 's/.*indirizzi: \([0-9]*\) .*/\1/p'
    )"; then
    echo "::warning::generateAddresses fallito per $id"
    rm -rf "$cellWorkdir"
    return 0
  fi

  if [ -z "$generatedLine" ] || [ "$generatedLine" -eq 0 ]; then
    echo "-- $id: nessun civico dopo la deduplica"
    rm -rf "$cellWorkdir"
    return 0
  fi

  xz -T1 --lzma2=preset=9e,dict=16MiB -c "$pmtiles" > "$pmtiles.xz"
  local xzSize; xzSize="$(wc -c < "$pmtiles.xz" | tr -d ' ')"

  if [ "$xzSize" -gt "$ADDRESS_CELL_MAX_BYTES" ] && [ "$z" -lt 14 ]; then
    echo "-- $id: $xzSize byte oltre il tetto ($ADDRESS_CELL_MAX_BYTES), divido in 4 celle figlie"
    rm -rf "$cellWorkdir"
    build_cell $((z + 1)) $((x * 2)) $((y * 2))
    build_cell $((z + 1)) $((x * 2 + 1)) $((y * 2))
    build_cell $((z + 1)) $((x * 2)) $((y * 2 + 1))
    build_cell $((z + 1)) $((x * 2 + 1)) $((y * 2 + 1))
    return 0
  fi
  if [ "$xzSize" -gt "$ADDRESS_CELL_MAX_BYTES" ]; then
    echo "::warning::$id: $xzSize byte oltre il tetto ($ADDRESS_CELL_MAX_BYTES) ma gia' a z14 (una singola tile), pubblicata cosi' com'e'"
  fi

  local hash; hash="$(sha256sum < "$pmtiles" | awk '{print $1}')"
  if [ -n "$published" ] && [ "$hash" = "$(printf '%s' "$published" | jq -r '.file.sha256')" ]; then
    echo "-- $id: file rigenerato identico, tengo la voce pubblicata"
    printf '%s\n' "$published" >> "$ENTRIES_FILE"
    rm -rf "$cellWorkdir"
    return 0
  fi

  local baseName="cell-$z-$x-$y--$VERSION--addresses.pmtiles"
  cp "$pmtiles" "$OUTPUT_DIR/$baseName"
  cp "$pmtiles.xz" "$OUTPUT_DIR/$baseName.xz"
  local size xzHash url
  size="$(wc -c < "$OUTPUT_DIR/$baseName" | tr -d ' ')"
  xzHash="$(sha256sum < "$OUTPUT_DIR/$baseName.xz" | awk '{print $1}')"
  url="${ASSET_BASE_URL}/${baseName}.xz"
  jq -n -c --arg id "$id" --arg version "$VERSION" --arg name "$baseName" --arg url "$url" \
    --argjson size "$size" --arg hash "$hash" --arg xzName "$baseName.xz" --argjson xzSize "$xzSize" --arg xzHash "$xzHash" \
    '{id: $id, version: $version,
      file: {name: $name, url: $url, sizeBytes: $size, sha256: $hash},
      fileXz: {name: $xzName, url: $url, sizeBytes: $xzSize, sha256: $xzHash}}' \
    >> "$ENTRIES_FILE"
  echo "== [$id] civici: $generatedLine in $size byte ($xzSize byte xz), versione $VERSION =="
  rm -rf "$cellWorkdir"
}

IFS='/' read -r START_Z START_X START_Y <<< "$CELL_ID"
build_cell "$START_Z" "$START_X" "$START_Y"
