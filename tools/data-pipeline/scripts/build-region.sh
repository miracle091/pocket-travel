#!/usr/bin/env bash
# Orchestratore batch (piano A2): per UNA regione (una nazione o una sua sotto-area, per le
# nazioni non contigue — vedi build-pilot-regions.sh per Stati Uniti), genera content.db
# (Wikivoyage + POI + numeri di emergenza, riusando i tool Kotlin esistenti
# generateGuideContent/generatePoi/generateEmergencyNumbers),
# scarica e ri-ospita i segmenti BRouter .rd5 che intersecano il bbox (stesso host di
# content.db, vedi manifest-fragment.json), e produce il frammento manifest.json (mapSource
# punta alla build Protomaps corrente per l'estrazione lato device della mappa — vedi
# PmtilesExtractor, core:sync).
#
# I .rd5 sono ri-ospitati (non solo hashati e scartati come in origine) perche' brouter.de
# rigenera periodicamente i propri segmenti: la stessa tile scaricata a poche ore di distanza
# ha gia' dato dimensioni diverse (visto su E10_N40.rd5: 80062379, poi 80071670, poi 79989750
# byte in meno di un giorno). RegionPackageDownloader.downloadAndVerify() confronta la
# dimensione scaricata con quella pinnata nel manifest al momento della pubblicazione e la
# rigetta come PermanentRegionPackageException se non combacia piu' — un manifest che punta
# dritto a brouter.de si rompe quindi da solo ad ogni rigenerazione a monte, senza modo per
# l'app di saperlo in anticipo. Ospitando la nostra copia, dimensione/hash restano quelli del
# file che l'utente scarica davvero, stabili finche' non ripubblichiamo la regione.
#
# Uso:
#   build-region.sh <regionId> <displayName> <version> <minLon> <minLat> <maxLon> <maxLat> \
#                    <wikivoyagePageTitle> <contentDbBaseUrl> <outputDir> [publishedManifestUrl]
#
# Esempio (San Marino):
#   build-region.sh san-marino "San Marino" 2026.09.14 12.40 43.89 12.52 43.99 \
#                    San_Marino https://github.com/miracle091/pocket-travel/releases/download/region-data \
#                    /tmp/out/san-marino
#
# <contentDbBaseUrl> e' la base a cui content.db/i .rd5 saranno raggiungibili una volta caricati
# (oggi gli asset della release "region-data", vedi publish-regions.yml): questo script calcola
# solo gli URL da scrivere nel manifest, non carica nulla.
#
# <publishedManifestUrl> (opzionale, es. https://.../manifest.json): se presente, prima di fare
# qualunque lavoro costoso lo script confronta le tile .rd5 attese con quelle gia' pubblicate per
# REGION_ID (stesso nome, stessa dimensione) - se coincidono la regione e' considerata invariata
# questa settimana e la rigenerazione viene saltata del tutto (vedi sezione "2bis." sotto e
# .claude/docs/weekly-manifest-update-plan.md, "Controllo di necessita'"). Omesso (come per
# l'esecuzione locale via build-pilot-regions.sh, sempre "tutto fresco") = nessun controllo,
# rigenerazione completa come sempre. Quando la regione viene saltata, <outputDir>/.skipped viene
# creato (vuoto) invece di content.db/i .rd5 - il chiamante lo usa per capire che non c'e' nulla
# di nuovo da ricaricare (vedi publish-regions.yml).
#
# Richiede: curl, sha256sum, awk, jq (solo se si passa publishedManifestUrl), gradle wrapper
# (./gradlew) dalla root del repo. I segmenti .rd5 restano in <outputDir> insieme a content.db,
# pronti per essere copiati nel sito da pubblicare (vedi build-pilot-regions.sh/publish-regions.yml).
set -euo pipefail

if [ "$#" -lt 10 ] || [ "$#" -gt 11 ]; then
  echo "Uso: $0 <regionId> <displayName> <version> <minLon> <minLat> <maxLon> <maxLat> <wikivoyagePageTitle> <contentDbBaseUrl> <outputDir> [publishedManifestUrl]" >&2
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
PUBLISHED_MANIFEST_URL="${11:-}"

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

# --- 2. Griglia di tile 5x5 gradi che copre il bbox (usata sia per i segmenti .rd5 che per le query Overpass, sezione 4) ---
floor5() {
  awk -v v="$1" 'BEGIN { x = v / 5; ix = int(x); if (x < ix) ix -= 1; printf "%d", ix * 5 }'
}

tile_name() {
  local lon="$1" lat="$2" ew="E" ns="N" alon="$lon" alat="$lat"
  if [ "$lon" -lt 0 ]; then ew="W"; alon=$(( -lon )); fi
  if [ "$lat" -lt 0 ]; then ns="S"; alat=$(( -lat )); fi
  echo "${ew}${alon}_${ns}${alat}"
}

# Popolato dalla sezione 2bis (se gira) con l'HTTP status gia' osservato per ciascuna tile, cosi'
# la sezione 4 non ripete la stessa richiesta HEAD per le tile gia' controllate - vuoto (nessun
# riuso, comportamento invariato) quando la sezione 2bis non gira, es. esecuzione locale.
declare -A PRECHECKED_TILE_CODE

LON_START="$(floor5 "$MIN_LON")"
LON_END="$(floor5 "$MAX_LON")"
LAT_START="$(floor5 "$MIN_LAT")"
LAT_END="$(floor5 "$MAX_LAT")"

# --- 2bis. Controllo di necessita': salta la regione se le tile .rd5 attese sono gia' quelle
# pubblicate (vedi .claude/docs/weekly-manifest-update-plan.md, "Controllo di necessita'") -------
# Confronto sulla dimensione (Content-Length via HEAD), non sull'hash: piu' economico (nessun
# download necessario) ma in teoria non si accorgerebbe di un contenuto cambiato a parita' di
# byte - la dimensione cambia pero' quasi sempre quando BRouter rigenera davvero un segmento (vedi
# il commento in testa a questo file, esempio E10_N40.rd5), quindi resta un proxy ragionevole in
# pratica, non una garanzia crittografica come lo sha256 gia' usato altrove per l'integrita' del
# download. Un confronto per sha256 richiederebbe scaricare comunque il file, annullando il
# risparmio che questo controllo vuole ottenere. Nessun $PUBLISHED_MANIFEST_URL o jq mancante =
# nessun controllo, si procede sempre con la rigenerazione completa (comportamento storico, usato
# anche dall'esecuzione locale via build-pilot-regions.sh).
if [ -n "$PUBLISHED_MANIFEST_URL" ] && command -v jq >/dev/null 2>&1; then
  echo "-- controllo se $REGION_ID e' gia' aggiornata rispetto a $PUBLISHED_MANIFEST_URL..."
  PUBLISHED_MANIFEST="$WORKDIR/published-manifest.json"
  if curl -sSf -o "$PUBLISHED_MANIFEST" "$PUBLISHED_MANIFEST_URL" 2>/dev/null; then
    # Un file TSV (nome<TAB>dimensione) invece di un array JSON costruito con una chiamata jq per
    # tile: su una regione grande (es. Canada, ~130 tile) risparmia altrettante invocazioni jq, una
    # sola alla fine basta per ordinare/convertire tutto il file in JSON.
    EXPECTED_TSV="$WORKDIR/expected-rd5.tsv"
    : > "$EXPECTED_TSV"
    lon="$LON_START"
    while [ "$lon" -le "$LON_END" ]; do
      lat="$LAT_START"
      while [ "$lat" -le "$LAT_END" ]; do
        tile="$(tile_name "$lon" "$lat")"
        headers="$(curl -sS -I "${BROUTER_BASE}/${tile}.rd5" 2>/dev/null || true)"
        code="$(printf '%s' "$headers" | head -1 | awk '{print $2}')"
        PRECHECKED_TILE_CODE["$tile"]="${code:-000}"
        if [ "$code" = "200" ]; then
          size="$(printf '%s' "$headers" | tr -d '\r' | grep -i '^content-length:' | tail -1 | awk '{print $2}')"
          printf '%s.rd5\t%s\n' "$tile" "${size:-0}" >> "$EXPECTED_TSV"
        fi
        lat=$((lat + 5))
      done
      lon=$((lon + 5))
    done
    EXPECTED_SORTED="$(jq -R -s -c '
      split("\n") | map(select(length > 0) | split("\t") | {name: .[0], sizeBytes: (.[1] | tonumber)})
      | sort_by(.name)
    ' "$EXPECTED_TSV")"

    PUBLISHED_SORTED="$(jq -c --arg id "$REGION_ID" '
      [(.regions // [])[] | select(.regionId == $id) | (.files // [])[] | select(.name | endswith(".rd5")) | {name, sizeBytes}]
      | sort_by(.name)
    ' "$PUBLISHED_MANIFEST" 2>/dev/null || echo "[]")"

    if [ -n "$EXPECTED_SORTED" ] && [ "$EXPECTED_SORTED" != "[]" ] && [ "$PUBLISHED_SORTED" = "$EXPECTED_SORTED" ]; then
      echo "== [$REGION_ID] invariata rispetto al manifest pubblicato (stesse tile .rd5, stesse dimensioni): salto la rigenerazione =="
      : > "$OUTPUT_DIR/.skipped"
      jq -c --arg id "$REGION_ID" '{manifestVersion: 1, regions: [(.regions // [])[] | select(.regionId == $id)]}' \
        "$PUBLISHED_MANIFEST" > "$OUTPUT_DIR/manifest-fragment.json"
      echo "== [$REGION_ID] fatto (saltata, frammento riusato da quello pubblicato) =="
      exit 0
    fi
    echo "-- $REGION_ID cambiata (o non ancora pubblicata): rigenerazione completa"
  else
    echo "-- nessun manifest pubblicato raggiungibile su $PUBLISHED_MANIFEST_URL: rigenerazione completa"
  fi
fi

# --- 3. Guida testuale da Wikivoyage (wikitext grezzo): preferisce l'edizione italiana -----------
# Wikivoyage IT e' scritto da editor italiani, non una traduzione automatica: piu' "tradotto" e
# "leggibile" di qualunque pipeline di traduzione aggiunta qui, senza dipendenze nuove (vedi "no
# hosting infra" nella memoria di progetto). Il titolo IT non si puo' indovinare da WIKI_TITLE
# (es. "Giappone" per "Japan", "Palau (stato)" per "Palau"): si risolve dai langlinks interwiki
# della pagina EN via l'API MediaWiki, gia' tenuti allineati da Wikivoyage stesso — evita di
# mantenere a mano una seconda colonna di titoli IT in pilot-regions.sh (~200 righe), che si
# disallineerebbe silenziosamente ad ogni rinomina di pagina. jq resta facoltativo altrove in
# questo script (solo per PUBLISHED_MANIFEST_URL): questa risposta la parsiamo con sed per non
# renderlo improvvisamente obbligatorio anche per l'esecuzione locale senza manifest pubblicato.
DUMP_FILE="$WORKDIR/dump.txt"
WIKI_URL="https://en.wikivoyage.org/wiki/${WIKI_TITLE}"
LANGLINKS_JSON="$(curl -sS "https://en.wikivoyage.org/w/api.php?action=query&titles=${WIKI_TITLE}&prop=langlinks&lllang=it&format=json" 2>/dev/null || true)"
IT_TITLE="$(printf '%s' "$LANGLINKS_JSON" | sed -n 's/.*"lang":"it","\*":"\([^"]*\)".*/\1/p')"

if [ -n "$IT_TITLE" ]; then
  IT_TITLE_URL="${IT_TITLE// /_}"
  echo "-- scarico dump Wikivoyage (IT): $IT_TITLE"
  download_with_progress "$DUMP_FILE" "dump Wikivoyage IT $IT_TITLE" -sS \
    "https://it.wikivoyage.org/w/index.php?title=${IT_TITLE_URL}&action=raw" -o "$DUMP_FILE"
  if [ -s "$DUMP_FILE" ]; then
    WIKI_URL="https://it.wikivoyage.org/wiki/${IT_TITLE_URL}"
  fi
fi

# Nessun langlink IT (o pagina IT risultata vuota nonostante il langlink): fallback sull'originale
# inglese, comportamento identico a prima di questa modifica.
if [ ! -s "$DUMP_FILE" ]; then
  echo "-- scarico dump Wikivoyage (EN): $WIKI_TITLE"
  download_with_progress "$DUMP_FILE" "dump Wikivoyage EN $WIKI_TITLE" -sS "https://en.wikivoyage.org/w/index.php?title=${WIKI_TITLE}&action=raw" -o "$DUMP_FILE"
  WIKI_URL="https://en.wikivoyage.org/wiki/${WIKI_TITLE}"
fi

if [ ! -s "$DUMP_FILE" ]; then
  echo "ERRORE: dump Wikivoyage vuoto per $WIKI_TITLE (titolo pagina errato?)" >&2
  exit 1
fi

# --- 4. Segmenti .rd5 + POI Overpass, tile per tile (stessa griglia 5x5 gradi) -------------------
# Le due cose sono unite in un solo giro sulla griglia (non due giri separati come prima):
# interrogare Overpass anche per una tile senza .rd5 (nessuna strada estratta, quasi certamente
# oceano aperto) e' puro spreco - trovato sul Giappone (arcipelago, fino a 42 tile nella griglia
# rettangolare che ne racchiude il territorio, la maggior parte mare) dove ogni tile oceanica
# pagava comunque il balzello di backoff sui mirror morti sotto (vedi OVERPASS_ENDPOINTS) prima di
# scoprire l'ovvio: zero POI in mezzo al mare.
#
# Una singola query Overpass sull'intero bbox non regge invece per una nazione grande: gli Stati
# Uniti (bbox contiguo, 48 stati) hanno fatto scadere tutti i mirror pubblici, l'ultimo con un 504
# Gateway Timeout del reverse proxy anche dopo 900s pieni - non e' un timeout nostro ritentabile,
# il server si arrende prima di finire di elaborare un'area cosi' grande. Si spezza quindi anche
# la query POI nella stessa griglia usata per i segmenti .rd5: per un paese piccolo come San
# Marino resta un solo chunk/una sola query, per uno grande diventano N query piu' leggere.
# generatePoi (piu' sotto) unisce i risultati delle tile con terra emersa.
#
# overpass.openstreetmap.fr per primo (non per ultimo come prima): sui mirror precedenti
# (overpass-api.de, z.overpass-api.de) ogni singolo tentativo di questa sessione e' fallito,
# nessuno dei due ha mai risposto una volta - tenerli come primi tentativi costava ~60s di
# backoff a vuoto (20s+40s) PER OGNI tile della griglia, moltiplicato per decine di tile su un
# paese grande diventa mezz'ora o piu' di puro tempo morto. Restano come fallback, non rimossi
# del tutto, nel caso smettano di essere irraggiungibili in futuro.
OVERPASS_ENDPOINTS=(
  "https://overpass.openstreetmap.fr/api/interpreter"
  "https://overpass-api.de/api/interpreter"
  "https://z.overpass-api.de/api/interpreter"
  "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
)
ATTEMPTS=3

fmax() { awk -v a="$1" -v b="$2" 'BEGIN { print (a+0>b+0)?a:b }'; }
fmin() { awk -v a="$1" -v b="$2" 'BEGIN { print (a+0<b+0)?a:b }'; }

# Ritenta un singolo chunk sui mirror noti (stessa rilevazione del <remark> di timeout mascherato
# da XML valido, vedi sotto). Ritorna 1 se nessun mirror ha risposto validamente dopo $ATTEMPTS
# tentativi - il chiamante decide se e' fatale.
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

echo "-- risolvo segmenti .rd5 e interrogo Overpass per i POI, tile per tile..."
REMOTE_FILES_JSON=""
POI_XML_FILES=()
FAILED_CHUNKS=0
lon="$LON_START"
chunkIndex=0
while [ "$lon" -le "$LON_END" ]; do
  lat="$LAT_START"
  while [ "$lat" -le "$LAT_END" ]; do
    tile="$(tile_name "$lon" "$lat")"
    url="${BROUTER_BASE}/${tile}.rd5"
    # Riusa l'esito gia' osservato dalla sezione 2bis per questa tile, se c'e' (stessa richiesta
    # HEAD, evita di rifarla identica una seconda volta quando il controllo di necessita' e' girato
    # ma non ha portato a uno skip - vuota quando la sezione 2bis non gira, comportamento invariato).
    if [ -n "${PRECHECKED_TILE_CODE[$tile]:-}" ]; then
      code="${PRECHECKED_TILE_CODE[$tile]}"
    else
      code="$(curl -s -o /dev/null -w '%{http_code}' -I "$url")"
    fi
    if [ "$code" = "200" ]; then
      dest="$OUTPUT_DIR/${tile}.rd5"
      echo "-- scarico $tile.rd5 (ri-ospitato insieme a content.db, vedi commento in testa al file)..."
      download_with_progress "$dest" "$tile.rd5" -sS -o "$dest" "$url"
      size="$(wc -c < "$dest" | tr -d ' ')"
      hash="$(sha256sum "$dest" | awk '{print $1}')"
      rd5Url="${CONTENT_DB_BASE_URL}/${REGION_ID}--${VERSION}--${tile}.rd5"
      entry="{ \"name\": \"${tile}.rd5\", \"url\": \"${rd5Url}\", \"sizeBytes\": ${size}, \"sha256\": \"${hash}\" }"
      if [ -z "$REMOTE_FILES_JSON" ]; then REMOTE_FILES_JSON="$entry"; else REMOTE_FILES_JSON="$REMOTE_FILES_JSON, $entry"; fi

      chunkMinLon="$(fmax "$MIN_LON" "$lon")"
      chunkMinLat="$(fmax "$MIN_LAT" "$lat")"
      chunkMaxLon="$(fmin "$MAX_LON" "$((lon + 5))")"
      chunkMaxLat="$(fmin "$MAX_LAT" "$((lat + 5))")"
      chunkIndex=$((chunkIndex + 1))
      chunkFile="$WORKDIR/poi-chunk-${chunkIndex}.osm.xml"
      echo "-- chunk $chunkIndex ($tile): $chunkMinLon,$chunkMinLat,$chunkMaxLon,$chunkMaxLat"
      if fetch_overpass_chunk "$chunkMinLon" "$chunkMinLat" "$chunkMaxLon" "$chunkMaxLat" "$chunkFile"; then
        POI_XML_FILES+=("$chunkFile")
      else
        # Non fatale subito: un chunk perso (una sotto-area di una nazione grande) significa POI
        # mancanti solo li', non l'intera regione da buttare via - coerente con la stessa logica
        # "non perdere il lavoro gia' fatto" applicata alle regioni nel workflow.
        echo "-- chunk $chunkIndex fallito dopo $ATTEMPTS tentativi su tutti i mirror, salto (POI di quella sotto-area mancanti)" >&2
        FAILED_CHUNKS=$((FAILED_CHUNKS + 1))
      fi
    else
      echo "-- $tile.rd5 non esiste (probabile tile oceanica), salto anche Overpass per questa tile"
    fi
    lat=$(( lat + 5 ))
  done
  lon=$(( lon + 5 ))
done

if [ -z "$REMOTE_FILES_JSON" ]; then
  echo "ERRORE: nessun segmento .rd5 trovato per il bbox di $REGION_ID" >&2
  exit 1
fi
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
echo "-- genero content.db (emergency_numbers)..."
./gradlew -q :tools:data-pipeline:content:generateEmergencyNumbers \
  --args="\"$REGION_ID\" \"$(winpath "$CONTENT_DB")\""

# --- 6. Frammento manifest.json (content.db nostro + rd5 remoti + mapSource) ---------------------
CONTENT_DB_URL="${CONTENT_DB_BASE_URL}/${REGION_ID}--${VERSION}--content.db"
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
