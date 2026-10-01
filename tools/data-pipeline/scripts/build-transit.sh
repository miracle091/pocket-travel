#!/usr/bin/env bash
# Costruisce (o mantiene) il pacchetto orari di UNA rete di mezzi pubblici (GTFS) della lista
# tools/data-pipeline/transit-feeds.tsv: scarica la copia del feed dal Mobility Database (o dall'url_feed
# della riga), la converte
# in transit.db (generateTransit, schema "calendario", finestra di TRANSIT_WINDOW_DAYS giorni da
# oggi) e la comprime con xz. Se il feed scaricato ha lo stesso sha256 di quello della voce gia'
# pubblicata e la voce ha meno di TRANSIT_MAX_AGE_DAYS giorni, tiene la voce pubblicata (nessun
# upload): la finestra di 90 giorni resta valida, e l'app non riscarica nulla.
#
# Uso:
#   build-transit.sh <feedId> <version> <assetBaseUrl> <outputDir> [publishedIndexFile]
#
# Scrive in <outputDir> (mai svuotata: piu' reti ci scrivono in sequenza):
#   <feedId>--<version>--transit.db[.xz]   se la rete e' stata ricostruita
#   transit-entries.jsonl                  una riga JSON per rete (nuova o riusata), schema delle
#                                          voci "feeds[]" di transit.json
#
# Variabili:
#   TRANSIT_WINDOW_DAYS  (90)  giorni di orari nel pacchetto (60 e 90 pesano uguale: si tengono le corse)
#   TRANSIT_MAX_AGE_DAYS (7)   eta' oltre cui una rete si ricostruisce anche con il feed invariato
#   TRANSIT_FEEDS              lista delle reti (default: tools/data-pipeline/transit-feeds.tsv)
#
# Richiede: jq, curl, unzip, sha256sum, xz, gradle wrapper dalla root del repo.
set -euo pipefail

if [ "$#" -lt 4 ] || [ "$#" -gt 5 ]; then
  echo "Uso: $0 <feedId> <version> <assetBaseUrl> <outputDir> [publishedIndexFile]" >&2
  exit 1
fi

FEED_ID="$1"
VERSION="$2"
ASSET_BASE_URL="${3%/}"
OUTPUT_DIR="$4"
PUBLISHED_INDEX="${5:-}"
TRANSIT_WINDOW_DAYS="${TRANSIT_WINDOW_DAYS:-90}"
TRANSIT_MAX_AGE_DAYS="${TRANSIT_MAX_AGE_DAYS:-7}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
TRANSIT_FEEDS="${TRANSIT_FEEDS:-$REPO_ROOT/tools/data-pipeline/transit-feeds.tsv}"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"

[[ "$FEED_ID" =~ ^[a-z0-9-]+$ ]] || { echo "feedId non valido: $FEED_ID" >&2; exit 1; }
LINE="$(awk -F'\t' -v id="$FEED_ID" '$0 !~ /^#/ && $1 == id' "$TRANSIT_FEEDS")"
[ -n "$LINE" ] || { echo "$FEED_ID non e' nella lista $TRANSIT_FEEDS" >&2; exit 1; }
IFS=$'\t' read -r _ REGIONS NAME LICENSE ATTRIBUTION LICENSE_URL FEED_URL <<< "$LINE"
# Senza colonna url_feed: la copia del Mobility Database.
FEED_URL="${FEED_URL:-https://files.mobilitydatabase.org/${FEED_ID}/latest.zip}"
# {anno} nell'url_feed: l'orario dell'anno (Svizzera: un dataset per anno, cambio orario a meta'
# dicembre). Dal 10 dicembre si prova quello dell'anno dopo, se e' gia' pubblicato.
if [[ "$FEED_URL" == *"{anno}"* ]]; then
  YEAR="$(date -u +%Y)"
  if [ "$(date -u +%m%d)" -ge 1210 ] && curl -sSfIL -o /dev/null -A "$PIPELINE_USER_AGENT" "${FEED_URL//\{anno\}/$((YEAR + 1))}"; then
    YEAR=$((YEAR + 1))
  fi
  FEED_URL="${FEED_URL//\{anno\}/$YEAR}"
  echo "-- $FEED_ID: orario $YEAR ($FEED_URL)"
fi

mkdir -p "$OUTPUT_DIR"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
ENTRIES="$OUTPUT_DIR/transit-entries.jsonl"

echo "-- $FEED_ID ($NAME): scarico il feed..."
FEED_ZIP="$WORKDIR/feed.zip"
ok=false
for attempt in 1 2 3; do
  if curl -sSfL --retry 3 --retry-delay 5 -A "$PIPELINE_USER_AGENT" -o "$FEED_ZIP" "$FEED_URL"; then
    ok=true
    break
  fi
  [ "$attempt" -lt 3 ] && sleep $((attempt * 20))
done
[ "$ok" = true ] || { echo "-- $FEED_ID: feed non scaricato" >&2; exit 1; }
# Un messaggio chiaro invece dell'eccezione di Java ("zip END header not found"): BODS risponde 200 con
# {"errors":["Invalid region name"]} a una regione che non serve piu'.
if ! unzip -tqq "$FEED_ZIP" >/dev/null 2>&1; then
  echo "-- $FEED_ID: il feed scaricato non e' uno zip valido: $(head -c 120 "$FEED_ZIP" | tr -c '[:print:]' ' ')" >&2
  exit 1
fi
SOURCE_SHA="$(sha256sum "$FEED_ZIP" | cut -d' ' -f1)"

# Voce gia' pubblicata: si tiene se il feed non e' cambiato ed e' abbastanza recente.
if [ -n "$PUBLISHED_INDEX" ] && [ -s "$PUBLISHED_INDEX" ]; then
  PUBLISHED_ENTRY="$(jq -c --arg id "$FEED_ID" '.feeds[]? | select(.id == $id)' "$PUBLISHED_INDEX")"
  if [ -n "$PUBLISHED_ENTRY" ] && [ "$(jq -r '.sourceSha256' <<< "$PUBLISHED_ENTRY")" = "$SOURCE_SHA" ]; then
    PUBLISHED_DATE="$(jq -r '.version' <<< "$PUBLISHED_ENTRY" | cut -d. -f1-3 | tr . -)"
    AGE_DAYS=$(( ( $(date -u +%s) - $(date -u -d "$PUBLISHED_DATE" +%s) ) / 86400 ))
    if [ "$AGE_DAYS" -lt "$TRANSIT_MAX_AGE_DAYS" ]; then
      echo "-- $FEED_ID: feed invariato, voce di $AGE_DAYS giorni fa: la tengo"
      # Nome, regioni e attribuzione dalla lista di oggi (possono cambiare senza ricostruire).
      jq -c --arg name "$NAME" --arg regions "$REGIONS" --arg license "$LICENSE" --arg attribution "$ATTRIBUTION" --arg licenseUrl "$LICENSE_URL" \
        '.name = $name | .regions = ($regions | split(",")) | .license = $license | .attribution = $attribution | .licenseUrl = $licenseUrl' \
        <<< "$PUBLISHED_ENTRY" >> "$ENTRIES"
      exit 0
    fi
  fi
fi

DB="$WORKDIR/transit.db"
WINDOW_START="$(date -u +%Y-%m-%d)"
echo "-- $FEED_ID: converto (finestra di $TRANSIT_WINDOW_DAYS giorni da $WINDOW_START)..."
STATS="$(cd "$REPO_ROOT" && ./gradlew -q :tools:data-pipeline:content:generateTransit \
  --args="\"$(winpath "$FEED_ZIP")\" \"$(winpath "$DB")\" $FEED_ID $WINDOW_START $TRANSIT_WINDOW_DAYS" | tail -1)"
# Tab -> \x1f prima di leggere: il tab e' uno spazio per IFS, due tab di fila (validUntil vuoto) ne valgono
# uno e il bbox finirebbe in VALID_UNTIL.
IFS=$'\x1f' read -r STOPS ROUTES TRIPS STOP_TIMES VALID_UNTIL BBOX <<< "${STATS//$'\t'/$'\x1f'}"
echo "-- $FEED_ID: $STOPS fermate, $ROUTES linee, $TRIPS corse, $STOP_TIMES partenze, valido fino al ${VALID_UNTIL:-?}"
# Anche senza partenze (stop_times.txt assente o illeggibile): corse senza orari non servono al tabellone.
if [ "${TRIPS:-0}" -eq 0 ] || [ "${STOP_TIMES:-0}" -eq 0 ] || [ -z "$VALID_UNTIL" ]; then
  echo "-- $FEED_ID: nessuna corsa nei prossimi $TRANSIT_WINDOW_DAYS giorni, non pubblico" >&2
  exit 1
fi

ASSET="${FEED_ID}--${VERSION}--transit.db"
cp "$DB" "$OUTPUT_DIR/$ASSET"
# Dizionario da 16 MiB come xz_entry (lib.sh): con -9 (64 MiB) il telefono ne userebbe 65 per decomprimere.
xz -T1 --lzma2=preset=9e,dict=16MiB -k -f "$OUTPUT_DIR/$ASSET"
file_json() {
  jq -n --arg name "$2" --arg url "$ASSET_BASE_URL/$1" --argjson size "$(stat -c %s "$OUTPUT_DIR/$1")" \
    --arg sha "$(sha256sum "$OUTPUT_DIR/$1" | cut -d' ' -f1)" '{name: $name, url: $url, sizeBytes: $size, sha256: $sha}'
}
jq -c -n \
  --arg id "$FEED_ID" --arg name "$NAME" --arg regions "$REGIONS" --arg license "$LICENSE" \
  --arg attribution "$ATTRIBUTION" --arg licenseUrl "$LICENSE_URL" --arg version "$VERSION" \
  --arg sourceSha "$SOURCE_SHA" --arg validUntil "$VALID_UNTIL" --arg bbox "$BBOX" \
  --argjson stops "$STOPS" --argjson routes "$ROUTES" \
  --argjson file "$(file_json "$ASSET" transit.db)" --argjson fileXz "$(file_json "$ASSET.xz" transit.db.xz)" \
  '{id: $id, name: $name, regions: ($regions | split(",")), license: $license, attribution: $attribution,
    licenseUrl: $licenseUrl, version: $version, sourceSha256: $sourceSha, validUntil: $validUntil,
    bbox: ($bbox | split(",") | map(tonumber)), stops: $stops, routes: $routes, file: $file, fileXz: $fileXz}' \
  >> "$ENTRIES"
echo "-- $FEED_ID: $(stat -c %s "$OUTPUT_DIR/$ASSET.xz") byte xz"
