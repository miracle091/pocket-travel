#!/usr/bin/env bash
# Orchestratore batch: per UNA regione (una nazione o una sua sotto-area, per le
# nazioni non contigue — vedi build-pilot-regions.sh per Stati Uniti), genera poi.db (POI
# Overpass, via il tool Kotlin generatePoi), scarica e ri-ospita i segmenti BRouter .rd5 che
# intersecano il bbox (stesso host di poi.db, vedi manifest-fragment.json), e produce il
# frammento manifest.json con i tre pacchetti della regione: mappa (map.source punta alla build
# Protomaps corrente per l'estrazione lato device — vedi PmtilesExtractor, core:sync), routing
# (.rd5) e POI, ciascuno con la propria versione. Le guide non sono qui: un solo pacchetto per
# tutte le regioni, generato da build-guides.sh.
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
#                    <assetBaseUrl> <outputDir> [publishedManifestUrl]
#
# Esempio (San Marino):
#   build-region.sh san-marino "San Marino" 2026.09.14 12.40 43.89 12.52 43.99 \
#                    https://github.com/miracle091/pocket-travel/releases/download/region-data \
#                    /tmp/out/san-marino
#
# <assetBaseUrl> e' la base a cui poi.db/i .rd5 saranno raggiungibili una volta caricati
# (oggi gli asset della release "region-data", vedi publish-regions.yml): questo script calcola
# solo gli URL da scrivere nel manifest, non carica nulla.
#
# <publishedManifestUrl> (opzionale, es. https://.../manifest.json): se presente, prima di fare
# qualunque lavoro costoso lo script confronta le tile .rd5 attese con quelle gia' pubblicate per
# REGION_ID (stesso nome, stessa dimensione) - se coincidono e il poi.db pubblicato non ha piu' di
# POI_MAX_AGE_DAYS giorni, la regione e' considerata invariata questa settimana e la rigenerazione
# viene saltata del tutto (vedi sezione "2bis." sotto).
# Omesso (come per
# l'esecuzione locale via build-pilot-regions.sh, sempre "tutto fresco") = nessun controllo,
# rigenerazione completa come sempre. Quando la regione viene saltata, <outputDir>/.skipped viene
# creato (vuoto) invece di poi.db/i .rd5 - il chiamante lo usa per capire che non c'e' nulla
# di nuovo da ricaricare (vedi publish-regions.yml).
#
# Richiede: curl, sha256sum, awk, jq (solo se si passa publishedManifestUrl), gradle wrapper
# (./gradlew) dalla root del repo. I segmenti .rd5 restano in <outputDir> insieme a poi.db,
# pronti per essere copiati nel sito da pubblicare (vedi build-pilot-regions.sh/publish-regions.yml).
set -euo pipefail

if [ "$#" -lt 9 ] || [ "$#" -gt 10 ]; then
  echo "Uso: $0 <regionId> <displayName> <version> <minLon> <minLat> <maxLon> <maxLat> <assetBaseUrl> <outputDir> [publishedManifestUrl]" >&2
  exit 1
fi

REGION_ID="$1"
DISPLAY_NAME="$2"
VERSION="$3"
MIN_LON="$4"
MIN_LAT="$5"
MAX_LON="$6"
MAX_LAT="$7"
ASSET_BASE_URL="${8%/}"
OUTPUT_DIR="$9"
PUBLISHED_MANIFEST_URL="${10:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"
BROUTER_BASE="https://brouter.de/brouter/segments4"
MAP_MIN_ZOOM=0
MAP_MAX_ZOOM=14
# Versione della mappa (map.version, che fa ri-estrarre la mappa alle app installate): nuova solo se
# quella pubblicata ha piu' di MAP_MAX_AGE_DAYS giorni E le tile della regione sono cambiate. Il
# confronto usa un'impronta (mapFingerprint) delle sole tile da MAP_FINGERPRINT_MIN_ZOOM in su: quelle
# piu' basse coprono aree enormi e cambiano quasi a ogni build per modifiche lontane dalla regione.
MAP_MAX_AGE_DAYS="${MAP_MAX_AGE_DAYS:-30}"
MAP_FINGERPRINT_MIN_ZOOM=12
# Oltre questa eta' (giorni) il poi.db pubblicato viene rigenerato, anche se le tile sono
# invariate; sotto, si riusa (vedi sezione 2bis).
POI_MAX_AGE_DAYS="${POI_MAX_AGE_DAYS:-30}"

mkdir -p "$OUTPUT_DIR"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

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
# resolve_protomaps_date e' in lib.sh. PROTOMAPS_DATE_OVERRIDE (opzionale): quando il chiamante
# processa piu' regioni nella stessa run e l'ha gia' risolta una volta, evita di rifarlo qui.
if [ -n "${PROTOMAPS_DATE_OVERRIDE:-}" ]; then
  PROTOMAPS_DATE="$PROTOMAPS_DATE_OVERRIDE"
else
  PROTOMAPS_DATE="$(resolve_protomaps_date)"
fi
echo "-- build Protomaps: ${PROTOMAPS_DATE}.pmtiles"

# --- 2. Griglia di tile 5x5 gradi che copre il bbox (usata sia per i segmenti .rd5 che per le query Overpass, sezione 4) ---
# floor5/tile_name sono in lib.sh (condivise con generate-weekly-schedule.sh, stessa griglia).

# Popolato dalla sezione 2bis (se gira) con l'HTTP status gia' osservato per ciascuna tile, cosi'
# la sezione 4 non ripete la stessa richiesta HEAD per le tile gia' controllate - vuoto (nessun
# riuso, comportamento invariato) quando la sezione 2bis non gira, es. esecuzione locale.
declare -A PRECHECKED_TILE_CODE
# true quando la sezione 2bis ha gia' scritto il frammento (tile cambiate) e resta da rigenerare
# solo il poi.db: la sezione 3 interroga Overpass senza riscaricare i .rd5.
POI_ONLY=false

LON_START="$(floor5 "$MIN_LON")"
LON_END="$(floor5 "$MAX_LON")"
LAT_START="$(floor5 "$MIN_LAT")"
LAT_END="$(floor5 "$MAX_LAT")"

# --- 2bis. Controllo di necessita': salta la regione se le tile .rd5 attese sono gia' quelle
# pubblicate -------------------------------------------------------------------------------------
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
      [(.regions // [])[] | select(.regionId == $id) | (.routing.files // .files // [])[] | select(.name | endswith(".rd5")) | {name, sizeBytes}]
      | sort_by(.name)
    ' "$PUBLISHED_MANIFEST" 2>/dev/null || echo "[]")"

    # Eta' del poi.db pubblicato, dal nome dell'asset (regionId--YYYY.MM.DD--poi.db, o --content.db
    # per le regioni convertite dal formato v1). Serve sia allo skip qui sotto sia all'aggiornamento
    # incrementale: senza, una regione con le tile invariate verrebbe saltata per sempre e i suoi
    # POI non si aggiornerebbero mai.
    PUBLISHED_REGION="$(jq -c --arg id "$REGION_ID" '[(.regions // [])[] | select(.regionId == $id)][0] // empty' "$PUBLISHED_MANIFEST" 2>/dev/null || true)"
    PUBLISHED_POI_URL="$(printf '%s' "$PUBLISHED_REGION" | jq -r '.poi.file.url // ""' 2>/dev/null || true)"
    POI_DATE="$(printf '%s' "$PUBLISHED_POI_URL" | sed -n 's#.*--\([0-9]\{4\}\.[0-9]\{2\}\.[0-9]\{2\}\)--\(poi\|content\)\.db$#\1#p')"
    POI_AGE_DAYS=""
    if [ -n "$POI_DATE" ]; then
      POI_AGE_DAYS="$(( ($(date -u +%s) - $(date -u -d "${POI_DATE//./-}" +%s)) / 86400 ))"
    fi
    POI_STALE=false

    # Impronta delle tile della regione nella build corrente e confronto con la mappa pubblicata.
    MAP_FINGERPRINT="$(cd "$REPO_ROOT" && ./gradlew -q :tools:data-pipeline:content:mapFingerprint \
      --args="https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles $MIN_LON $MIN_LAT $MAX_LON $MAX_LAT $MAP_FINGERPRINT_MIN_ZOOM $MAP_MAX_ZOOM" 2>/dev/null \
      | sed -n 's/.*impronta \([0-9a-f]\{64\}\).*/\1/p' | tail -1 || true)"
    PUBLISHED_MAP_FINGERPRINT="$(printf '%s' "$PUBLISHED_REGION" | jq -r '.map.fingerprint // ""' 2>/dev/null || true)"
    PUBLISHED_MAP_DATE="$(printf '%s' "$PUBLISHED_REGION" | jq -r '.map.version // ""' 2>/dev/null | sed -n 's/^\([0-9]\{4\}\)\.\([0-9]\{2\}\)\.\([0-9]\{2\}\)$/\1-\2-\3/p' || true)"
    MAP_DUE=false
    if [ -n "$MAP_FINGERPRINT" ] && [ -n "$PUBLISHED_MAP_FINGERPRINT" ] && [ "$MAP_FINGERPRINT" != "$PUBLISHED_MAP_FINGERPRINT" ] && [ -n "$PUBLISHED_MAP_DATE" ]; then
      MAP_AGE_DAYS="$(( ($(date -u +%s) - $(date -u -d "$PUBLISHED_MAP_DATE" +%s)) / 86400 ))"
      if [ "$MAP_AGE_DAYS" -gt "$MAP_MAX_AGE_DAYS" ]; then
        MAP_DUE=true
        echo "-- $REGION_ID: mappa di $MAP_AGE_DAYS giorni (max $MAP_MAX_AGE_DAYS) e tile cambiate: nuova versione della mappa"
      fi
    fi
    if [ -n "$POI_AGE_DAYS" ] && [ "$POI_AGE_DAYS" -gt "$POI_MAX_AGE_DAYS" ]; then
      POI_STALE=true
    fi

    if [ -n "$EXPECTED_SORTED" ] && [ "$EXPECTED_SORTED" != "[]" ] && [ "$PUBLISHED_SORTED" = "$EXPECTED_SORTED" ] && [ "$POI_STALE" != "true" ] && [ "$MAP_DUE" != "true" ]; then
      echo "== [$REGION_ID] invariata rispetto al manifest pubblicato (stesse tile .rd5, stesse dimensioni): salto la rigenerazione =="
      : > "$OUTPUT_DIR/.skipped"
      # manifestVersion quella del manifest pubblicato: una regione ancora v1 viene convertita da
      # mergeManifests (vedi MergeManifests.kt), non qui.
      # Mappe pubblicate prima dell'impronta: la registra senza cambiare versione (riferimento per
      # i confronti successivi).
      jq -c --arg id "$REGION_ID" --arg fp "$MAP_FINGERPRINT" '{manifestVersion: .manifestVersion, regions: [(.regions // [])[] | select(.regionId == $id)
        | if (.map.fingerprint // "") == "" and $fp != "" then .map.fingerprint = $fp else . end]}' \
        "$PUBLISHED_MANIFEST" > "$OUTPUT_DIR/manifest-fragment.json"
      echo "== [$REGION_ID] fatto (saltata, frammento riusato da quello pubblicato) =="
      exit 0
    fi
    # Aggiornamento incrementale: la regione e' gia' pubblicata nel formato a pacchetti (v2) con
    # le stesse tile (cambiano solo le dimensioni di alcune). Si riscaricano solo le tile cambiate
    # (nuova versione del routing, e della mappa, che punta alla build Protomaps corrente; nessuna
    # delle due cambia versione se non e' cambiata nessuna tile, altrimenti l'app riscaricherebbe
    # per niente). I POI non dipendono dalle tile di routing: il poi.db pubblicato resta com'e' se
    # ha al piu' POI_MAX_AGE_DAYS giorni, altrimenti si rigenera solo lui (sezione 3 in modalita'
    # POI_ONLY). Una regione ancora v1 (senza "routing") viene rigenerata per intero.
    if [ -n "$PUBLISHED_REGION" ] && [ "$EXPECTED_SORTED" != "[]" ] && [ "$(printf '%s' "$PUBLISHED_REGION" | jq 'has("routing")')" = "true" ]; then
      SAME_TILES="$(jq -n --argjson a "$EXPECTED_SORTED" --argjson b "$PUBLISHED_SORTED" '($a | map(.name)) == ($b | map(.name))')"
      if [ "$SAME_TILES" = "true" ] && [ -n "$POI_AGE_DAYS" ]; then
        if [ "$POI_STALE" = "true" ]; then
          echo "-- $REGION_ID: stesse tile, poi.db di $POI_AGE_DAYS giorni (max $POI_MAX_AGE_DAYS): aggiorno le tile cambiate e rigenero il poi.db"
          POI_ONLY=true
        else
          echo "-- $REGION_ID: stesse tile, poi.db di $POI_AGE_DAYS giorni (max $POI_MAX_AGE_DAYS): aggiorno solo le tile cambiate"
        fi
        UPDATED_TSV="$WORKDIR/updated-rd5.tsv"
        : > "$UPDATED_TSV"
        CHANGED_TILES="$(jq -r -n --argjson a "$EXPECTED_SORTED" --argjson b "$PUBLISHED_SORTED" \
          '$a[] as $e | ($b[] | select(.name == $e.name)) as $p | select($p.sizeBytes != $e.sizeBytes) | $e.name')"
        while read -r tileFile; do
          [ -n "$tileFile" ] || continue
          dest="$OUTPUT_DIR/$tileFile"
          echo "-- scarico $tileFile (cambiata)..."
          download_with_progress "$dest" "$tileFile" -sS -o "$dest" "${BROUTER_BASE}/${tileFile}"
          size="$(wc -c < "$dest" | tr -d ' ')"
          hash="$(sha256sum < "$dest" | awk '{print $1}')"
          printf '%s\t%s\t%s\t%s\n' "$tileFile" "${ASSET_BASE_URL}/${REGION_ID}--${VERSION}--${tileFile}" "$size" "$hash" >> "$UPDATED_TSV"
        done <<< "$CHANGED_TILES"
        jq -R -s -c 'split("\n") | map(select(length > 0) | split("\t") | {name: .[0], url: .[1], sizeBytes: (.[2] | tonumber), sha256: .[3]})' \
          "$UPDATED_TSV" > "$WORKDIR/updated-rd5.json"
        # Mappa e routing hanno versioni indipendenti: una tile .rd5 cambiata non fa piu' ri-estrarre
        # la mappa (prima map.version cambiava con routing.version).
        jq -c --arg id "$REGION_ID" --arg version "$VERSION" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
          --arg src "https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles" --slurpfile upd "$WORKDIR/updated-rd5.json" \
          --arg fp "$MAP_FINGERPRINT" --argjson mapdue "$MAP_DUE" '
          ($upd[0] | map({key: .name, value: .}) | from_entries) as $u
          | {manifestVersion: 2, regions: [(.regions // [])[] | select(.regionId == $id)
              | .updatedAt = $now
              | .map.source.sourceUrl = $src
              | if $mapdue then .map.version = $version | .map.fingerprint = $fp
                elif (.map.fingerprint // "") == "" and $fp != "" then .map.fingerprint = $fp
                else . end
              | if ($u | length) > 0 then
                  .routing.version = $version | .routing.files |= map(if $u[.name] then $u[.name] else . end)
                else . end]}' \
          "$PUBLISHED_MANIFEST" > "$OUTPUT_DIR/manifest-fragment.json"
        : > "$OUTPUT_DIR/.incremental"
        if [ "$POI_ONLY" != "true" ]; then
          echo "== [$REGION_ID] fatto (incrementale: solo tile cambiate, poi.db riusato) =="
          exit 0
        fi
      fi
    fi
    if [ "$POI_ONLY" != "true" ]; then
      echo "-- $REGION_ID cambiata (o non ancora pubblicata): rigenerazione completa"
    fi
  else
    echo "-- nessun manifest pubblicato raggiungibile su $PUBLISHED_MANIFEST_URL: rigenerazione completa"
  fi
fi

# --- 3. Segmenti .rd5 + POI Overpass, tile per tile (stessa griglia 5x5 gradi) -------------------
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
# Mirror Overpass: OVERPASS_ENDPOINTS in lib.sh. ATTEMPTS = numero di istanze, cosi' ogni chunk le
# prova tutte.
ATTEMPTS=${#OVERPASS_ENDPOINTS[@]}

# Chiavi di tag OSM riconosciute come punti di interesse — sottoinsieme minimo, non lo schema POI
# completo di OSM (amenity/shop/tourism/leisure/historic coprono la maggior parte dei casi comuni
# per una guida di viaggio). Unica fonte di verita': passata a generatePoi via CLI (vedi POI_ARGS
# piu' sotto) invece di essere duplicata anche li', cosi' la query Overpass e il filtro dei nodi
# in GeneratePoi.kt non possono disallinearsi.
POI_TAG_KEYS=(amenity shop tourism leisure historic)
# Chiavi lette da generatePoi ma interrogate su Overpass solo con i filtri mirati di
# fetch_overpass_chunk (stazioni, aeroporti): con la chiave intera arriverebbero anche binari,
# passaggi a livello e segnali.
POI_EXTRA_TAG_KEYS=(railway aeroway)

fmax() { awk -v a="$1" -v b="$2" 'BEGIN { print (a+0>b+0)?a:b }'; }
fmin() { awk -v a="$1" -v b="$2" 'BEGIN { print (a+0<b+0)?a:b }'; }

# Ritenta un singolo chunk sui mirror noti (stessa rilevazione del <remark> di timeout mascherato
# da XML valido, vedi sotto). Ritorna 1 se nessun mirror ha risposto validamente dopo $ATTEMPTS
# tentativi - il chiamante decide se e' fatale.
fetch_overpass_chunk() {
  local chunkMinLon="$1" chunkMinLat="$2" chunkMaxLon="$3" chunkMaxLat="$4" outFile="$5"
  local query="[out:xml][timeout:900];("
  for tag in "${POI_TAG_KEYS[@]}"; do
    query="${query}node[\"${tag}\"](${chunkMinLat},${chunkMinLon},${chunkMaxLat},${chunkMaxLon});"
  done
  # Parcheggi, autostazioni, ospedali, caserme dei pompieri, chiese, monasteri e terminal dei
  # traghetti anche come aree (quasi sempre disegnati cosi'): "out center" da' alle way un punto.
  local bbox="(${chunkMinLat},${chunkMinLon},${chunkMaxLat},${chunkMaxLon})"
  query="${query}way[\"amenity\"~\"^(parking|bus_station|hospital|fire_station|place_of_worship|monastery|ferry_terminal)$\"]${bbox};"
  query="${query}way[\"tourism\"=\"information\"][\"information\"~\"^(office|visitor_centre)$\"]${bbox};"
  # Parchi pubblici e a pagamento (parchi a tema e acquatici, zoo) e riserve naturali: quasi sempre aree
  # o relazioni. Dei parchi e delle riserve solo quelli con un nome: gli altri parchi sono per lo piu'
  # aiuole e giardinetti.
  query="${query}wr[\"leisure\"~\"^(park|nature_reserve)$\"][\"name\"]${bbox};wr[\"leisure\"=\"water_park\"]${bbox};wr[\"tourism\"~\"^(theme_park|zoo)$\"]${bbox};"
  # Trasporti: stazioni (treno e metro), autostazioni, aeroporti con codice IATA (niente aviosuperfici).
  query="${query}nw[\"railway\"~\"^(station|halt)$\"]${bbox};nwr[\"aeroway\"=\"aerodrome\"][\"iata\"]${bbox};"
  query="${query});out center;"
  for attempt in $(seq 1 "$ATTEMPTS"); do
    local endpoint="${OVERPASS_ENDPOINTS[$(( (attempt - 1) % ${#OVERPASS_ENDPOINTS[@]} ))]}"
    echo "-- tentativo $attempt/$ATTEMPTS su $endpoint..."
    download_with_progress "$outFile" "Overpass POI ($endpoint)" -sS --max-time 950 -A "$PIPELINE_USER_AGENT" "$endpoint" --data-urlencode "data=${query}" -o "$outFile"
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
      if [ "$POI_ONLY" != "true" ]; then
        dest="$OUTPUT_DIR/${tile}.rd5"
        echo "-- scarico $tile.rd5 (ri-ospitato insieme a poi.db, vedi commento in testa al file)..."
        download_with_progress "$dest" "$tile.rd5" -sS -o "$dest" "$url"
        size="$(wc -c < "$dest" | tr -d ' ')"
        hash="$(sha256sum < "$dest" | awk '{print $1}')"
        rd5Url="${ASSET_BASE_URL}/${REGION_ID}--${VERSION}--${tile}.rd5"
        entry="{ \"name\": \"${tile}.rd5\", \"url\": \"${rd5Url}\", \"sizeBytes\": ${size}, \"sha256\": \"${hash}\" }"
        if [ -z "$REMOTE_FILES_JSON" ]; then REMOTE_FILES_JSON="$entry"; else REMOTE_FILES_JSON="$REMOTE_FILES_JSON, $entry"; fi
      fi

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

if [ -z "$REMOTE_FILES_JSON" ] && [ "$POI_ONLY" != "true" ]; then
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

# --- 4. poi.db e poi-extra.db, via il tool Kotlin generatePoi ------------------------------------
# poi.db: i POI che la mappa mostra. poi-extra.db: quelli scaricabili a parte (fontanelle, tavoli da
# picnic...), assente se la regione non ne ha. Gli altri non si pubblicano (regole in core:poi).
POI_DB="$OUTPUT_DIR/poi.db"
POI_EXTRA_DB="$OUTPUT_DIR/poi-extra.db"
rm -f "$POI_DB" "$POI_EXTRA_DB"
cd "$REPO_ROOT"
echo "-- genero poi.db e poi-extra.db..."
POI_TAG_KEYS_ARG="$(IFS=,; echo "${POI_TAG_KEYS[*]} ${POI_EXTRA_TAG_KEYS[*]}" | tr ' ' ,)"
POI_ARGS="\"$REGION_ID\" \"$(winpath "$POI_DB")\" \"$(winpath "$POI_EXTRA_DB")\" \"$POI_TAG_KEYS_ARG\""
for f in "${POI_XML_FILES[@]}"; do
  POI_ARGS="$POI_ARGS \"$(winpath "$f")\""
done
./gradlew -q :tools:data-pipeline:content:generatePoi --args="$POI_ARGS"

# --- 5. Frammento manifest.json (poi.db e poi-extra.db nostri + rd5 ri-ospitati + sorgente mappa)
POI_DB_URL="${ASSET_BASE_URL}/${REGION_ID}--${VERSION}--poi.db"
POI_EXTRA_DB_URL="${ASSET_BASE_URL}/${REGION_ID}--${VERSION}--poi-extra.db"
if [ "$POI_ONLY" = "true" ]; then
  # Il frammento incrementale scritto dalla sezione 2bis ha gia' mappa, routing e le voci POI
  # pubblicate: si aggiornano solo i pacchetti POI. Un file identico a quello pubblicato si scarta e
  # si tiene la voce pubblicata: una versione nuova farebbe riscaricare all'app gli stessi dati.
  MANIFEST_FRAGMENT="$OUTPUT_DIR/manifest-fragment.json"
  update_poi_entry() {
    local key="$1" file="$2" name="$3" url="$4" hash
    if [ ! -f "$file" ]; then
      # Nessun POI extra nella regione: niente voce.
      jq -c --arg key "$key" '.regions |= map(del(.[$key]))' "$MANIFEST_FRAGMENT" > "$WORKDIR/fragment.json"
      mv "$WORKDIR/fragment.json" "$MANIFEST_FRAGMENT"
      return 0
    fi
    hash="$(sha256sum < "$file" | awk '{print $1}')"
    if [ "$hash" = "$(printf '%s' "$PUBLISHED_REGION" | jq -r --arg key "$key" '.[$key].file.sha256 // ""')" ]; then
      rm -f "$file"
      return 0
    fi
    jq -c --arg key "$key" --arg version "$VERSION" --arg name "$name" --arg url "$url"       --argjson size "$(wc -c < "$file" | tr -d ' ')" --arg hash "$hash" '
      .regions |= map(.[$key] = {version: $version, file: {name: $name, url: $url, sizeBytes: $size, sha256: $hash}})'       "$MANIFEST_FRAGMENT" > "$WORKDIR/fragment.json"
    mv "$WORKDIR/fragment.json" "$MANIFEST_FRAGMENT"
  }
  update_poi_entry poi "$POI_DB" poi.db "$POI_DB_URL"
  update_poi_entry poiExtra "$POI_EXTRA_DB" poi-extra.db "$POI_EXTRA_DB_URL"
  if [ ! -f "$POI_DB" ] && [ ! -f "$POI_EXTRA_DB" ]; then
    CHANGED_RD5=false
    for rd5 in "$OUTPUT_DIR"/*.rd5; do
      [ -e "$rd5" ] && CHANGED_RD5=true
    done
    if [ "$CHANGED_RD5" != "true" ] && [ "$(jq -c '.regions[0] | {poi, poiExtra}' "$MANIFEST_FRAGMENT")" = "$(printf '%s' "$PUBLISHED_REGION" | jq -c '{poi, poiExtra}')" ]; then
      # Nessuna tile cambiata e POI identici: niente da caricare, come lo skip della sezione 2bis.
      rm -f "$OUTPUT_DIR/.incremental"
      : > "$OUTPUT_DIR/.skipped"
      jq -c --arg id "$REGION_ID" '{manifestVersion: .manifestVersion, regions: [(.regions // [])[] | select(.regionId == $id)]}'         "$PUBLISHED_MANIFEST" > "$MANIFEST_FRAGMENT"
    fi
    echo "== [$REGION_ID] fatto (incrementale: POI rigenerati ma identici a quelli pubblicati) =="
    exit 0
  fi
  echo "== [$REGION_ID] fatto (incrementale: POI rigenerati) =="
  exit 0
fi
POI_EXTRA_SPEC=""
if [ -f "$POI_EXTRA_DB" ]; then
  POI_EXTRA_SPEC="\"poiExtraDb\": { \"path\": \"$(winpath "$POI_EXTRA_DB")\", \"url\": \"${POI_EXTRA_DB_URL}\" },"
fi
SPEC_FILE="$WORKDIR/spec.json"
cat > "$SPEC_FILE" <<EOF
{
  "regionId": "${REGION_ID}",
  "displayName": "${DISPLAY_NAME}",
  "version": "${VERSION}",
  "poiDb": { "path": "$(winpath "$POI_DB")", "url": "${POI_DB_URL}" },
  ${POI_EXTRA_SPEC}
  "routingFiles": [ ${REMOTE_FILES_JSON} ],
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
# Impronta delle tile della mappa appena pubblicata (riferimento per la prossima versione).
if [ -z "${MAP_FINGERPRINT:-}" ]; then
  MAP_FINGERPRINT="$(./gradlew -q :tools:data-pipeline:content:mapFingerprint \
    --args="https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles $MIN_LON $MIN_LAT $MAX_LON $MAX_LAT $MAP_FINGERPRINT_MIN_ZOOM $MAP_MAX_ZOOM" 2>/dev/null \
    | sed -n 's/.*impronta \([0-9a-f]\{64\}\).*/\1/p' | tail -1 || true)"
fi
if [ -n "$MAP_FINGERPRINT" ]; then
  jq -c --arg fp "$MAP_FINGERPRINT" '.regions |= map(.map.fingerprint = $fp)' "$MANIFEST_FRAGMENT" > "$WORKDIR/fragment.json"
  mv "$WORKDIR/fragment.json" "$MANIFEST_FRAGMENT"
fi

echo "== [$REGION_ID] fatto: $POI_DB, $MANIFEST_FRAGMENT =="
