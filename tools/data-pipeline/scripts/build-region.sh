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

# curl -sS scarica in silenzio: per i trasferimenti piu' grandi (rd5, dump Wikivoyage, risposta
# Overpass) i log di GitHub Actions restano vuoti per tutta la durata del download - per Overpass
# su un bbox nazionale possono passare 10+ minuti senza una sola riga di log, senza modo di
# distinguere un download lento da uno bloccato. Scarica in background e stampa periodicamente i
# KB scaricati finora, senza il meter interattivo di curl (a base di \r, illeggibile in un log
# non-tty). $@ dopo out/label sono passati a curl cosi' com'e' (GET o POST con -data-urlencode).
download_with_progress() {
  local out="$1" label="$2"
  shift 2
  curl "$@" &
  local pid=$!
  local last_kb=-1
  local elapsed=0
  # Overpass elabora la query lato server prima di iniziare a rispondere: i byte scaricati
  # restano a 0 per gran parte dell'attesa (anche 10+ minuti su un bbox nazionale), quindi il
  # solo cambio di dimensione non basta a distinguere un'attesa normale da un blocco - un
  # "battito" ogni 30s conferma che il processo e' ancora vivo anche a 0 byte.
  while kill -0 "$pid" 2>/dev/null; do
    sleep 2
    elapsed=$((elapsed + 2))
    local size_kb=0
    [ -f "$out" ] && size_kb=$(( $(wc -c < "$out" | tr -d ' ') / 1024 ))
    if [ "$size_kb" -ne "$last_kb" ] || [ $((elapsed % 30)) -eq 0 ]; then
      echo "-- $label: ${size_kb} KB scaricati (${elapsed}s trascorsi)..."
      last_kb=$size_kb
    fi
  done
  wait "$pid"
}

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
      download_with_progress "$tmp" "$tile.rd5" -sS -o "$tmp" "$url"
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
download_with_progress "$DUMP_FILE" "dump Wikivoyage $WIKI_TITLE" -sS "https://en.wikivoyage.org/w/index.php?title=${WIKI_TITLE}&action=raw" -o "$DUMP_FILE"
if [ ! -s "$DUMP_FILE" ]; then
  echo "ERRORE: dump Wikivoyage vuoto per $WIKI_TITLE (titolo pagina errato?)" >&2
  exit 1
fi

# --- 4. POI da Overpass (nodi con amenity/shop/tourism/leisure/historic dentro il bbox) ----------
# Una singola query sull'intero bbox non regge per una nazione grande: gli Stati Uniti (bbox
# contiguo, 48 stati) hanno fatto scadere tutti e 3 i mirror pubblici, l'ultimo con un 504
# Gateway Timeout del reverse proxy anche dopo 900s pieni - non e' un timeout nostro ritentabile,
# il server si arrende prima di finire di elaborare un'area cosi' grande. Si spezza quindi la
# query nella stessa griglia 5x5 gradi gia' calcolata sopra per i segmenti .rd5 (riuso diretto,
# non una seconda griglia indipendente): per un paese piccolo come San Marino restano invariati
# un solo chunk e una sola query, per uno grande diventano N query piu' leggere, ciascuna con lo
# stesso fallback multi-mirror di prima. generatePoi (piu' sotto) unisce i risultati.
OVERPASS_ENDPOINTS=(
  "https://overpass-api.de/api/interpreter"
  "https://z.overpass-api.de/api/interpreter"
  "https://overpass.openstreetmap.fr/api/interpreter"
  "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
)
ATTEMPTS=3

fmax() { awk -v a="$1" -v b="$2" 'BEGIN { print (a+0>b+0)?a:b }'; }
fmin() { awk -v a="$1" -v b="$2" 'BEGIN { print (a+0<b+0)?a:b }'; }

# Ritenta un singolo chunk sui mirror noti, come il vecchio ciclo su tutto il bbox (stessa
# rilevazione del <remark> di timeout mascherato da XML valido, vedi sotto). Ritorna 1 se nessun
# mirror ha risposto validamente dopo $ATTEMPTS tentativi - il chiamante decide se e' fatale.
fetch_overpass_chunk() {
  local chunkMinLon="$1" chunkMinLat="$2" chunkMaxLon="$3" chunkMaxLat="$4" outFile="$5"
  local query="[out:xml][timeout:900];(node[\"amenity\"](${chunkMinLat},${chunkMinLon},${chunkMaxLat},${chunkMaxLon});node[\"shop\"](${chunkMinLat},${chunkMinLon},${chunkMaxLat},${chunkMaxLon});node[\"tourism\"](${chunkMinLat},${chunkMinLon},${chunkMaxLat},${chunkMaxLon});node[\"leisure\"](${chunkMinLat},${chunkMinLon},${chunkMaxLat},${chunkMaxLon});node[\"historic\"](${chunkMinLat},${chunkMinLon},${chunkMaxLat},${chunkMaxLon}););out body;"
  for attempt in $(seq 1 "$ATTEMPTS"); do
    local endpoint="${OVERPASS_ENDPOINTS[$(( (attempt - 1) % ${#OVERPASS_ENDPOINTS[@]} ))]}"
    echo "-- tentativo $attempt/$ATTEMPTS su $endpoint..."
    download_with_progress "$outFile" "Overpass POI ($endpoint)" -sS --max-time 950 "$endpoint" --data-urlencode "data=${query}" -o "$outFile"
    # Attenzione: su un timeout della query (bbox grande), Overpass non fallisce la richiesta
    # HTTP ma risponde comunque con un <osm> ben formato contenente un
    # <remark>runtime error: Query timed out...</remark> e zero nodi -- un semplice grep "<osm"
    # lo scambierebbe per una risposta valida, producendo silenziosamente 0 POI (visto con
    # l'Italia: timeout:180 troppo basso per un bbox nazionale).
    if grep -q "<osm" "$outFile" && ! grep -q "<remark>" "$outFile"; then
      return 0
    fi
    echo "-- $endpoint non disponibile o in timeout, riprovo tra $((attempt * 20))s..."
    sleep $((attempt * 20))
  done
  return 1
}

echo "-- interrogo Overpass per i POI (a chunk di 5x5 gradi)..."
POI_XML_FILES=()
FAILED_CHUNKS=0
lon="$LON_START"
chunkIndex=0
while [ "$lon" -le "$LON_END" ]; do
  lat="$LAT_START"
  while [ "$lat" -le "$LAT_END" ]; do
    chunkMinLon="$(fmax "$MIN_LON" "$lon")"
    chunkMinLat="$(fmax "$MIN_LAT" "$lat")"
    chunkMaxLon="$(fmin "$MAX_LON" "$((lon + 5))")"
    chunkMaxLat="$(fmin "$MAX_LAT" "$((lat + 5))")"
    chunkIndex=$((chunkIndex + 1))
    chunkFile="$WORKDIR/poi-chunk-${chunkIndex}.osm.xml"
    echo "-- chunk $chunkIndex: $chunkMinLon,$chunkMinLat,$chunkMaxLon,$chunkMaxLat"
    if fetch_overpass_chunk "$chunkMinLon" "$chunkMinLat" "$chunkMaxLon" "$chunkMaxLat" "$chunkFile"; then
      POI_XML_FILES+=("$chunkFile")
    else
      # Non fatale subito: un chunk perso (una sotto-area di una nazione grande) significa POI
      # mancanti solo li', non l'intera regione da buttare via - coerente con la stessa logica
      # "non perdere il lavoro gia' fatto" applicata alle regioni nel workflow.
      echo "-- chunk $chunkIndex fallito dopo $ATTEMPTS tentativi su tutti i mirror, salto (POI di quella sotto-area mancanti)" >&2
      FAILED_CHUNKS=$((FAILED_CHUNKS + 1))
    fi
    lat=$(( lat + 5 ))
  done
  lon=$(( lon + 5 ))
done

if [ "${#POI_XML_FILES[@]}" -eq 0 ]; then
  echo "ERRORE: nessun chunk Overpass ha prodotto dati validi per $REGION_ID" >&2
  exit 1
fi
if [ "$FAILED_CHUNKS" -gt 0 ]; then
  echo "-- attenzione: $FAILED_CHUNKS/$chunkIndex chunk falliti, alcuni POI di $REGION_ID mancheranno"
fi

# --- 5. content.db: guide_sections + poi, via i tool Kotlin esistenti ----------------------------
CONTENT_DB="$OUTPUT_DIR/content.db"
rm -f "$CONTENT_DB"
cd "$REPO_ROOT"
echo "-- genero content.db (guide_sections)..."
./gradlew -q :tools:data-pipeline:content:generateGuideContent \
  --args="\"$(winpath "$DUMP_FILE")\" \"$REGION_ID\" \"$WIKI_URL\" \"$(winpath "$CONTENT_DB")\""
echo "-- genero content.db (poi)..."
POI_ARGS="\"$REGION_ID\" \"$(winpath "$CONTENT_DB")\""
for f in "${POI_XML_FILES[@]}"; do
  POI_ARGS="$POI_ARGS \"$(winpath "$f")\""
done
./gradlew -q :tools:data-pipeline:content:generatePoi --args="$POI_ARGS"

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
