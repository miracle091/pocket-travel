#!/usr/bin/env bash
# Orchestratore batch (piano A2): per UNA regione (una nazione o una sua sotto-area, per le
# nazioni non contigue — vedi build-pilot-regions.sh per Stati Uniti), genera content.db
# (Wikivoyage + POI, riusando i tool Kotlin esistenti generateGuideContent/generatePoi),
# risolve e hasha i segmenti BRouter .rd5 che intersecano il bbox (referenziati direttamente
# su brouter.de nel manifest, MAI ri-scaricati/ri-ospitati — vengono scaricati una volta qui
# solo per calcolarne sha256/sizeBytes, richiesti da RegionManifestEntry.validate()), e produce
# il frammento manifest.json (mapSource punta alla build Protomaps corrente per l'estrazione
# lato device della mappa — vedi PmtilesExtractor, core:sync).
#
# Uso:
#   build-region.sh <regionId> <displayName> <version> <minLon> <minLat> <maxLon> <maxLat> \
#                    <wikivoyagePageTitle> <contentDbBaseUrl> <outputDir>
#
# Esempio (San Marino):
#   build-region.sh san-marino "San Marino" 2026.09.14 12.40 43.89 12.52 43.99 \
#                    San_Marino https://miracle091.github.io/pocket-travel /tmp/out/san-marino
#
# Richiede: curl, sha256sum, awk, gradle wrapper (./gradlew) dalla root del repo. Nessun
# segmento .rd5 viene mai conservato su disco oltre il tempo necessario a hasharlo.
set -euo pipefail

if [ "$#" -ne 10 ]; then
  echo "Uso: $0 <regionId> <displayName> <version> <minLon> <minLat> <maxLon> <maxLat> <wikivoyagePageTitle> <contentDbBaseUrl> <outputDir>" >&2
  exit 1
fi

REGION_ID="$1"
DISPLAY_NAME="$2"
VERSION="$3"
MIN_LON="$4"
MIN_LAT="$5"
MAX_LON="$6"
MAX_LAT="$7"
WIKI_TITLE="$8"
CONTENT_DB_BASE_URL="${9%/}"
OUTPUT_DIR="${10}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BROUTER_BASE="https://brouter.de/brouter/segments4"
MAP_MIN_ZOOM=0
MAP_MAX_ZOOM=14

mkdir -p "$OUTPUT_DIR"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

# gradlew invoca java.exe nativo di Windows: gli argomenti passati via --args="..." vanno in
# path Windows reali (con lettera di unita'), non nel path POSIX virtuale di git-bash/MSYS
# (es. /tmp/xxx), altrimenti java.exe non li trova (visto: FileNotFoundException su un path
# tipo "\tmp\xxx\dump.txt", senza lettera di unita'). "-m" (non "-w"): forward slash, niente
# da escapare quando il path finisce anche dentro spec.json. Su Linux (CI) cygpath non esiste
# e non serve: bash e java concordano gia' sullo stesso path POSIX.
winpath() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

echo "== [$REGION_ID] bbox: $MIN_LON,$MIN_LAT,$MAX_LON,$MAX_LAT =="

# --- 1. Data Protomaps piu' recente disponibile (build giornaliera, whole-planet) ---------------
resolve_protomaps_date() {
  for days_ago in 0 1 2 3; do
    local d
    d="$(date -u -d "-${days_ago} day" +%Y%m%d)"
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' -I "https://build.protomaps.com/${d}.pmtiles")"
    if [ "$code" = "200" ]; then
      echo "$d"
      return 0
    fi
  done
  echo "ERRORE: nessuna build Protomaps trovata negli ultimi giorni" >&2
  return 1
}
PROTOMAPS_DATE="$(resolve_protomaps_date)"
echo "-- build Protomaps: ${PROTOMAPS_DATE}.pmtiles"

# --- 2. Segmenti BRouter .rd5 che intersecano il bbox (tile fisse 5x5 gradi) ---------------------
floor5() {
  awk -v v="$1" 'BEGIN { x = v / 5; ix = int(x); if (x < ix) ix -= 1; printf "%d", ix * 5 }'
}

tile_name() {
  local lon="$1" lat="$2" ew="E" ns="N" alon="$lon" alat="$lat"
  if [ "$lon" -lt 0 ]; then ew="W"; alon=$(( -lon )); fi
  if [ "$lat" -lt 0 ]; then ns="S"; alat=$(( -lat )); fi
  echo "${ew}${alon}_${ns}${alat}"
}

LON_START="$(floor5 "$MIN_LON")"
LON_END="$(floor5 "$MAX_LON")"
LAT_START="$(floor5 "$MIN_LAT")"
LAT_END="$(floor5 "$MAX_LAT")"

REMOTE_FILES_JSON=""
lon="$LON_START"
while [ "$lon" -le "$LON_END" ]; do
  lat="$LAT_START"
  while [ "$lat" -le "$LAT_END" ]; do
    tile="$(tile_name "$lon" "$lat")"
    url="${BROUTER_BASE}/${tile}.rd5"
    code="$(curl -s -o /dev/null -w '%{http_code}' -I "$url")"
    if [ "$code" = "200" ]; then
      tmp="$WORKDIR/${tile}.rd5"
      echo "-- scarico $tile.rd5 (per hash, non ri-ospitato)..."
      curl -sS -o "$tmp" "$url"
      size="$(wc -c < "$tmp" | tr -d ' ')"
      hash="$(sha256sum "$tmp" | awk '{print $1}')"
      rm -f "$tmp"
      entry="{ \"name\": \"${tile}.rd5\", \"url\": \"${url}\", \"sizeBytes\": ${size}, \"sha256\": \"${hash}\" }"
      if [ -z "$REMOTE_FILES_JSON" ]; then REMOTE_FILES_JSON="$entry"; else REMOTE_FILES_JSON="$REMOTE_FILES_JSON, $entry"; fi
    else
      echo "-- $tile.rd5 non esiste (probabile tile oceanica), salto"
    fi
    lat=$(( lat + 5 ))
  done
  lon=$(( lon + 5 ))
done

if [ -z "$REMOTE_FILES_JSON" ]; then
  echo "ERRORE: nessun segmento .rd5 trovato per il bbox di $REGION_ID" >&2
  exit 1
fi

# --- 3. Guida testuale da Wikivoyage (wikitext grezzo) -------------------------------------------
DUMP_FILE="$WORKDIR/dump.txt"
WIKI_URL="https://en.wikivoyage.org/wiki/${WIKI_TITLE}"
echo "-- scarico dump Wikivoyage: $WIKI_TITLE"
curl -sS "https://en.wikivoyage.org/w/index.php?title=${WIKI_TITLE}&action=raw" -o "$DUMP_FILE"
if [ ! -s "$DUMP_FILE" ]; then
  echo "ERRORE: dump Wikivoyage vuoto per $WIKI_TITLE (titolo pagina errato?)" >&2
  exit 1
fi

# --- 4. POI da Overpass (nodi con amenity/shop/tourism/leisure/historic dentro il bbox) ----------
POI_XML="$WORKDIR/poi.osm.xml"
OVERPASS_QUERY="[out:xml][timeout:180];(node[\"amenity\"](${MIN_LAT},${MIN_LON},${MAX_LAT},${MAX_LON});node[\"shop\"](${MIN_LAT},${MIN_LON},${MAX_LAT},${MAX_LON});node[\"tourism\"](${MIN_LAT},${MIN_LON},${MAX_LAT},${MAX_LON});node[\"leisure\"](${MIN_LAT},${MIN_LON},${MAX_LAT},${MAX_LON});node[\"historic\"](${MIN_LAT},${MIN_LON},${MAX_LAT},${MAX_LON}););out body;"
echo "-- interrogo Overpass per i POI..."
# Overpass e' un servizio pubblico condiviso, spesso occupato/rate-limited: qualche ritentativo
# con backoff evita di far fallire l'intera pipeline per un timeout transitorio del server.
overpass_ok=0
for attempt in 1 2 3; do
  curl -sS "https://overpass-api.de/api/interpreter" --data-urlencode "data=${OVERPASS_QUERY}" -o "$POI_XML"
  if grep -q "<osm" "$POI_XML"; then
    overpass_ok=1
    break
  fi
  echo "-- Overpass non disponibile (tentativo $attempt/3), riprovo tra $((attempt * 20))s..."
  sleep $((attempt * 20))
done
if [ "$overpass_ok" -ne 1 ]; then
  echo "ERRORE: risposta Overpass non valida per $REGION_ID dopo 3 tentativi" >&2
  cat "$POI_XML" >&2
  exit 1
fi

# --- 5. content.db: guide_sections + poi, via i tool Kotlin esistenti ----------------------------
CONTENT_DB="$OUTPUT_DIR/content.db"
rm -f "$CONTENT_DB"
cd "$REPO_ROOT"
echo "-- genero content.db (guide_sections)..."
./gradlew -q :tools:data-pipeline:content:generateGuideContent \
  --args="\"$(winpath "$DUMP_FILE")\" \"$REGION_ID\" \"$WIKI_URL\" \"$(winpath "$CONTENT_DB")\""
echo "-- genero content.db (poi)..."
./gradlew -q :tools:data-pipeline:content:generatePoi \
  --args="\"$(winpath "$POI_XML")\" \"$REGION_ID\" \"$(winpath "$CONTENT_DB")\""

# --- 6. Frammento manifest.json (content.db nostro + rd5 remoti + mapSource) ---------------------
CONTENT_DB_URL="${CONTENT_DB_BASE_URL}/regions/${REGION_ID}/${VERSION}/content.db"
SPEC_FILE="$WORKDIR/spec.json"
cat > "$SPEC_FILE" <<EOF
{
  "regionId": "${REGION_ID}",
  "displayName": "${DISPLAY_NAME}",
  "version": "${VERSION}",
  "contentDb": { "path": "$(winpath "$CONTENT_DB")", "url": "${CONTENT_DB_URL}" },
  "remoteFiles": [ ${REMOTE_FILES_JSON} ],
  "mapSource": {
    "sourceUrl": "https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles",
    "minLon": ${MIN_LON}, "minLat": ${MIN_LAT}, "maxLon": ${MAX_LON}, "maxLat": ${MAX_LAT},
    "minZoom": ${MAP_MIN_ZOOM}, "maxZoom": ${MAP_MAX_ZOOM}
  }
}
EOF

MANIFEST_FRAGMENT="$OUTPUT_DIR/manifest-fragment.json"
echo "-- genero il frammento manifest..."
./gradlew -q :tools:data-pipeline:content:generateManifest \
  --args="\"$(winpath "$SPEC_FILE")\" \"$(winpath "$MANIFEST_FRAGMENT")\""

echo "== [$REGION_ID] fatto: $CONTENT_DB, $MANIFEST_FRAGMENT =="
