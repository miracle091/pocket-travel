#!/usr/bin/env bash
# Orchestratore batch: per UNA regione (una nazione o una sua sotto-area, per le
# nazioni non contigue — vedi build-all-regions.sh per Stati Uniti), genera poi.db (POI
# Overpass, via il tool Kotlin generatePoi), scarica e ri-ospita i segmenti BRouter .rd5 che
# intersecano il bbox (stesso host di poi.db, vedi manifest-fragment.json), estrae con go-pmtiles
# una mappa d'insieme offline a pochi zoom della mappa (preview.pmtiles, vedi PREVIEW_MAX_ZOOM piu' sotto),
# e produce il frammento di catalogo con i pacchetti della regione: mappa (map.source punta alla
# build Protomaps corrente per l'estrazione sul dispositivo — vedi PmtilesExtractor, core:sync),
# rete stradale (.rd5), POI e mappa d'insieme (preview, facoltativa), ciascuno con la propria versione. Le
# guide non sono qui: un solo pacchetto per tutte le regioni, generato da build-guides.sh.
#
# I .rd5 sono ri-ospitati perche' brouter.de
# rigenera periodicamente i propri segmenti: la stessa tile scaricata a poche ore di distanza
# ha gia' dato dimensioni diverse (visto su E10_N40.rd5: 80062379, poi 80071670, poi 79989750
# byte in meno di un giorno). RegionPackageDownloader.downloadAndVerify() confronta la
# dimensione scaricata con quella fissata in `manifest.json` al momento della pubblicazione e la
# rigetta come PermanentRegionPackageException se non combacia piu' — un `manifest.json` che punta
# dritto a brouter.de si rompe quindi da solo ad ogni rigenerazione a monte, senza modo per
# l'app di saperlo in anticipo. Con una copia ospitata dal progetto, dimensione/hash restano quelli
# del file che l'utente scarica davvero, stabili finche' la regione non viene ripubblicata.
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
# (gli asset della release "region-data", vedi publish-regions.yml): questo script calcola
# solo gli URL da scrivere in `manifest.json`, non carica nulla.
#
# <publishedManifestUrl> (opzionale, es. https://.../manifest.json): se presente, prima di fare
# qualunque lavoro costoso lo script confronta le tile .rd5 attese con quelle gia' pubblicate per
# REGION_ID (stesso nome, stessa dimensione) - se coincidono e il poi.db pubblicato non ha piu' di
# POI_MAX_AGE_DAYS giorni, la regione e' considerata invariata questa settimana e la rigenerazione
# viene saltata del tutto (vedi sezione "2bis." sotto).
# Omesso (come per
# l'esecuzione locale via build-all-regions.sh, sempre "tutto fresco") = nessun controllo,
# rigenerazione completa. Quando la regione viene saltata, <outputDir>/.skipped viene
# creato (vuoto) invece di poi.db/i .rd5 - il chiamante lo usa per capire che non c'e' nulla
# di nuovo da ricaricare (vedi publish-regions.yml).
#
# Richiede: curl, sha256sum, awk, jq, xz, python3 (clip_rd5.py), gradle wrapper (./gradlew) dalla
# root del repo. go-pmtiles (PMTILES_BIN, vedi publish-regions.yml) e' facoltativo: senza, la
# regione resta senza mappa d'insieme (mai fatale). I segmenti .rd5 restano in <outputDir> insieme a
# poi.db e preview.pmtiles(.xz), pronti per essere copiati nel sito da pubblicare (vedi
# build-all-regions.sh/publish-regions.yml).
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
# I .rd5 di brouter.de coprono 5x5 gradi: si scaricano gia' ritagliati sul rettangolo della regione
# allargato di RD5_CLIP_MARGIN gradi (clip_rd5.py con richieste HTTP Range: San Marino 0,9 MB invece
# di 76, Lussemburgo 6,3 invece di 435, stesso percorso). Nel
# `manifest.json` sizeBytes/sha256 sono del file ritagliato, sourceKey = "<dimensione originale> $RD5_CLIP":
# il confronto 2bis riscarica una tile se cambia su brouter.de o se cambiano rettangolo o margine.
RD5_CLIP_MARGIN="${RD5_CLIP_MARGIN:-0.1}"
RD5_CLIP="$MIN_LON,$MIN_LAT,$MAX_LON,$MAX_LAT $RD5_CLIP_MARGIN"
# Colonne del TSV scritto da fetch_rd5 -> voci routing.files del manifest.
RD5_TSV_TO_JSON='split("\n") | map(select(length > 0) | split("\t") | {name: .[0], url: .[1], sizeBytes: (.[2] | tonumber), sha256: .[3], sourceKey: .[4]})'
# fetch_rd5 <tile.rd5> <tsv>: scarica in OUTPUT_DIR la parte della tile che serve alla regione e
# aggiunge la sua riga (nome, url ri-ospitato, dimensione, sha256, sourceKey) al TSV.
# RD5_RANGE_CACHE (facoltativa, la imposta il job "build"): intervalli gia' scaricati riusati dalle
# regioni successive dello stesso job, che spesso ritagliano le stesse tile (clip_rd5.py --range-cache).
fetch_rd5() {
  local name="$1" dest="$OUTPUT_DIR/$1" sourceSize cacheArgs=()
  [ -z "${RD5_RANGE_CACHE:-}" ] || cacheArgs=(--range-cache "$RD5_RANGE_CACHE")
  local rc=0
  sourceSize="$(python3 "$SCRIPT_DIR/clip_rd5.py" "${BROUTER_BASE}/${name}" "$dest" \
    --bbox="$MIN_LON,$MIN_LAT,$MAX_LON,$MAX_LAT" --margin "$RD5_CLIP_MARGIN" --user-agent "$PIPELINE_USER_AGENT" "${cacheArgs[@]}")" || rc=$?
  # 3 = la tile non ha strade nel rettangolo (costa, mare al bordo): non serve alla regione, si salta.
  [ "$rc" -ne 3 ] || return 0
  [ "$rc" -eq 0 ] || return "$rc"
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "${ASSET_BASE_URL}/${REGION_ID}--${VERSION}--${name}" \
    "$(wc -c < "$dest" | tr -d ' ')" "$(sha256sum < "$dest" | awk '{print $1}')" "$sourceSize $RD5_CLIP" >> "$2"
}
# Variante "solo auto" dei segmenti (voce routingCar di `manifest.json`, rete stradale solo auto
# dell'app): filterRd5 (Rd5CarFilter, circa il 60% del file intero) sulle tile appena scaricate, in
# OUTPUT_DIR/car con lo stesso nome. Asset "<regione>--<versione>--car-<tile>.rd5" su CAR_ASSET_BASE_URL: la
# release region-data-car-rNN accanto a region-data-rNN (le release reggono 1.000 asset), o la stessa base per
# gli URL che non seguono quello schema (esecuzioni locali).
CAR_ASSET_BASE_URL="${CAR_ASSET_BASE_URL:-${ASSET_BASE_URL/\/region-data-r//region-data-car-r}}"
# build_car_variant <tsvIn> <tsvOut>: una riga per tile di <tsvIn> (scritto da fetch_rd5), stesso sourceKey.
build_car_variant() {
  local name sourceKey inputs=() f
  : > "$2"
  while IFS=$'\t' read -r name _; do
    [ -n "$name" ] && inputs+=("$(winpath "$OUTPUT_DIR/$name")")
  done < "$1"
  [ "${#inputs[@]}" -gt 0 ] || return 0
  mkdir -p "$OUTPUT_DIR/car"
  (cd "$REPO_ROOT" && ./gradlew -q :tools:data-pipeline:content:filterRd5 \
    --args="$(winpath "$REPO_ROOT/feature/map/src/main/assets/brouter-profile/lookups.dat") $(winpath "$OUTPUT_DIR/car") ${inputs[*]}")
  while IFS=$'\t' read -r name _ _ _ sourceKey; do
    [ -n "$name" ] || continue
    f="$OUTPUT_DIR/car/$name"
    printf '%s\t%s\t%s\t%s\t%s\n' "$name" "${CAR_ASSET_BASE_URL}/${REGION_ID}--${VERSION}--car-${name}" \
      "$(wc -c < "$f" | tr -d ' ')" "$(sha256sum < "$f" | awk '{print $1}')" "$sourceKey" >> "$2"
  done < "$1"
}
# rd5_head <url> <headersFile>: HEAD della tile con fino a 4 tentativi, scrive gli header nel file e
# stampa il codice HTTP (000 = nessuna risposta). Solo 200 e 404 sono esiti definitivi: 404 e' una
# tile senza strade (oceano), un timeout o un 5xx si ritenta e non va scambiato per oceano.
rd5_head() {
  local url="$1" out="$2" code attempt
  for attempt in 1 2 3 4; do
    curl -sS -I --max-time 60 "$url" > "$out" 2>/dev/null || true
    code="$(head -1 "$out" | awk '{print $2}')"
    code="${code:-000}"
    if [ "$code" = "200" ] || [ "$code" = "404" ] || [ "$attempt" -eq 4 ]; then
      break
    fi
    echo "-- HEAD $url: HTTP $code (tentativo $attempt/4), riprovo tra $((attempt * 10))s..." >&2
    sleep $((attempt * 10))
  done
  echo "$code"
}
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

# Mappa d'insieme offline della regione (preview.pmtiles): pochi livelli di zoom della stessa build
# Protomaps della mappa, con un tetto di peso sul file compresso. Si parte da PREVIEW_MAX_ZOOM:
# se il .xz supera PREVIEW_MAX_XZ_BYTES si rifa' con uno zoom in meno, fino a PREVIEW_MIN_ZOOM
# incluso - se anche li' resta sopra il tetto si pubblica comunque (e' il minimo scelto) con un
# avviso, invece di lasciare la regione senza mappa d'insieme. Si rigenera solo quando la mappa cambia o
# la regione non ne ha ancora una (sezioni 2bis e 5 piu' sotto), non a ogni run: e' un'estrazione e
# una compressione in piu', inutili se la mappa sottostante e' la stessa.
PREVIEW_MAX_ZOOM=9
PREVIEW_MIN_ZOOM=6
PREVIEW_MAX_XZ_BYTES=$((10 * 1024 * 1024))
# go-pmtiles: stesso binario e stessa installazione di build-address-cell.sh (vedi publish-regions.yml).
# Mancante = niente mappa d'insieme, mai fatale per la regione (solo un avviso).
PMTILES_BIN="${PMTILES_BIN:-$(command -v pmtiles || command -v go-pmtiles || true)}"
# Limite per tentativo di estrazione: una mappa d'insieme nei limiti scarica pochi MB in qualche secondo, ma
# build.protomaps.com a volte rallenta a ~20 kB/s o risponde 524 dopo 15 minuti (norvegia, run del
# 2026-09-30: due tentativi da ~18 minuti hanno fatto scadere il limite di 45 minuti della regione).
PREVIEW_ATTEMPT_TIMEOUT=180
PREVIEW_ATTEMPTS=2

# preview_over_cap <sourceUrl> <zoom>: successo se la stima della dimensione a quello zoom e'
# chiaramente sopra il tetto, cosi' non si scarica un'estrazione destinata a essere scartata
# (norvegia: 112 MB a z9 e 43 MB a z8 prima di arrivare a z6). La stima viene da "extract --dry-run",
# che legge solo le directory dell'archivio (pochi secondi, nessuna tile) e stampa la dimensione
# dell'archivio risultante, arrotondata (es. "113 MB"). L'.xz pesa tra il 95% e il 100% del
# pmtiles (le tile sono gia' compresse, run del 2026-09-30), quindi si scarta solo sopra
# tetto/0,85: nei casi dubbi si scarica e si misura. Qualunque errore = non sopra il tetto.
preview_over_cap() {
  local sourceUrl="$1" zoom="$2" out
  out="$(timeout 120 "$PMTILES_BIN" extract "$sourceUrl" "$OUTPUT_DIR/preview.dry-run.pmtiles" \
    --bbox="$MIN_LON,$MIN_LAT,$MAX_LON,$MAX_LAT" --maxzoom="$zoom" --dry-run 2>&1)" || return 1
  printf '%s\n' "$out" | awk -v cap="$PREVIEW_MAX_XZ_BYTES" '
    match($0, /archive size of [0-9.]+ [kMG]?B/) {
      split(substr($0, RSTART + 16, RLENGTH - 16), a, " ")
      mult = (a[2] == "GB") ? 1e9 : (a[2] == "MB") ? 1e6 : (a[2] == "kB") ? 1e3 : 1
      found = 1; over = (a[1] * mult * 0.85 > cap)
    }
    END { exit !(found && over) }'
}

# build_preview <sourceUrl>: scrive $OUTPUT_DIR/preview.pmtiles e preview.pmtiles.xz, impostando
# PREVIEW_ZOOM_USED e le dimensioni/hash (PREVIEW_SIZE, PREVIEW_SHA256, PREVIEW_XZ_SIZE,
# PREVIEW_XZ_SHA256) per preview_manifest_entry piu' sotto - cosi' chi li usa non deve ri-comprimere
# o ri-hashare un file gia' pronto. Stessi parametri xz di xz_entry (sezione 4): dizionario da 16
# MiB, un solo thread.
build_preview() {
  local sourceUrl="$1" zoom="$PREVIEW_MAX_ZOOM" file="$OUTPUT_DIR/preview.pmtiles"
  # Si parte dallo zoom della mappa d'insieme gia' pubblicata invece che dal massimo: ripartendo
  # sempre da z9 le regioni grandi rifarebbero ogni volta le estrazioni scartate (Italia: z9, z8,
  # poi z7). Se allo zoom pubblicato il file sta sotto meta' del tetto, si prova un livello in piu'
  # (la mappa d'insieme puo' anche crescere); se non ci sta piu', si scende di un livello alla volta.
  local published="" tryUp=false
  if [ -n "${PUBLISHED_MANIFEST:-}" ] && [ -s "$PUBLISHED_MANIFEST" ]; then
    published="$(jq -r --arg id "$REGION_ID" '[(.regions // [])[] | select(.regionId == $id)][0].preview.maxZoom // empty' "$PUBLISHED_MANIFEST" 2>/dev/null || true)"
  fi
  if [[ "$published" =~ ^[0-9]+$ ]] && [ "$published" -ge "$PREVIEW_MIN_ZOOM" ] && [ "$published" -lt "$PREVIEW_MAX_ZOOM" ]; then
    zoom="$published"
    tryUp=true
  fi
  while true; do
    rm -f "$file" "$file.xz"
    # Sopra lo zoom minimo, uno zoom la cui stima e' gia' oltre il tetto non si scarica nemmeno.
    if [ "$zoom" -gt "$PREVIEW_MIN_ZOOM" ] && preview_over_cap "$sourceUrl" "$zoom"; then
      echo "-- mappa d'insieme di $REGION_ID a z$zoom: stima oltre il tetto ($PREVIEW_MAX_XZ_BYTES), salto il download e provo z$((zoom - 1))"
      zoom=$((zoom - 1))
      tryUp=false
      continue
    fi
    # build.protomaps.com a volte non risponde o chiude a meta': due tentativi (ciascuno con un limite
    # di tempo), poi la mappa d'insieme si salta (return 1) e il chiamante tiene quella pubblicata, invece di
    # perdere POI e rete stradale gia' fatti.
    with_retries "mappa d'insieme di $REGION_ID" "$PREVIEW_ATTEMPTS" "$file" \
      timeout "$PREVIEW_ATTEMPT_TIMEOUT" "$PMTILES_BIN" extract "$sourceUrl" "$file" --bbox="$MIN_LON,$MIN_LAT,$MAX_LON,$MAX_LAT" --maxzoom="$zoom" 2>&1 | pmtiles_log || return 1
    xz -T1 --lzma2=preset=9e,dict=16MiB -c "$file" > "$file.xz"
    local xzSize
    xzSize="$(wc -c < "$file.xz" | tr -d ' ')"
    if [ "$tryUp" = true ] && [ $((xzSize * 2)) -le "$PREVIEW_MAX_XZ_BYTES" ] && [ "$zoom" -lt "$PREVIEW_MAX_ZOOM" ]; then
      # Un solo tentativo verso l'alto: se non ci sta, si torna qui sotto allo zoom pubblicato.
      tryUp=false
      echo "-- mappa d'insieme di $REGION_ID a z$zoom: $xzSize byte, sotto meta' del tetto: provo z$((zoom + 1))"
      mv "$file" "$file.down"
      mv "$file.xz" "$file.xz.down"
      local upSize=$((PREVIEW_MAX_XZ_BYTES + 1))
      # Il tentativo verso l'alto e' facoltativo: se l'estrazione fallisce resta lo zoom pubblicato.
      if ! preview_over_cap "$sourceUrl" "$((zoom + 1))" \
        && with_retries "mappa d'insieme di $REGION_ID a z$((zoom + 1))" "$PREVIEW_ATTEMPTS" "$file" \
        timeout "$PREVIEW_ATTEMPT_TIMEOUT" "$PMTILES_BIN" extract "$sourceUrl" "$file" --bbox="$MIN_LON,$MIN_LAT,$MAX_LON,$MAX_LAT" --maxzoom="$((zoom + 1))" 2>&1 | pmtiles_log; then
        xz -T1 --lzma2=preset=9e,dict=16MiB -c "$file" > "$file.xz"
        upSize="$(wc -c < "$file.xz" | tr -d ' ')"
      fi
      if [ "$upSize" -le "$PREVIEW_MAX_XZ_BYTES" ]; then
        zoom=$((zoom + 1))
        xzSize="$upSize"
        rm -f "$file.down" "$file.xz.down"
      else
        mv -f "$file.down" "$file"
        mv -f "$file.xz.down" "$file.xz"
      fi
    fi
    tryUp=false
    if [ "$xzSize" -le "$PREVIEW_MAX_XZ_BYTES" ] || [ "$zoom" -le "$PREVIEW_MIN_ZOOM" ]; then
      if [ "$xzSize" -gt "$PREVIEW_MAX_XZ_BYTES" ]; then
        echo "::warning::mappa d'insieme di $REGION_ID a z$zoom pesa $xzSize byte (tetto $PREVIEW_MAX_XZ_BYTES), pubblicata comunque (zoom minimo $PREVIEW_MIN_ZOOM raggiunto)"
      fi
      PREVIEW_ZOOM_USED="$zoom"
      PREVIEW_SIZE="$(wc -c < "$file" | tr -d ' ')"
      PREVIEW_SHA256="$(sha256sum < "$file" | awk '{print $1}')"
      PREVIEW_XZ_SIZE="$xzSize"
      PREVIEW_XZ_SHA256="$(sha256sum < "$file.xz" | awk '{print $1}')"
      return 0
    fi
    echo "-- mappa d'insieme di $REGION_ID a z$zoom: $xzSize byte oltre il tetto ($PREVIEW_MAX_XZ_BYTES), riprovo a z$((zoom - 1))"
    zoom=$((zoom - 1))
  done
}

# preview_manifest_entry <version> <url>: la voce "preview" di `manifest.json` (stesso schema di "poi" -
# file = pmtiles decompresso, fileXz = da scaricare, stesso url per entrambi perche' l'unico asset
# pubblicato e' il .xz, vedi POI_DB_URL/xz_entry piu' sotto). Richiede una build_preview riuscita.
preview_manifest_entry() {
  jq -n -c --arg version "$1" --argjson maxZoom "$PREVIEW_ZOOM_USED" --arg url "$2" \
    --argjson size "$PREVIEW_SIZE" --arg hash "$PREVIEW_SHA256" \
    --argjson xzSize "$PREVIEW_XZ_SIZE" --arg xzHash "$PREVIEW_XZ_SHA256" '
    {version: $version, maxZoom: $maxZoom,
     file: {name: "preview.pmtiles", url: $url, sizeBytes: $size, sha256: $hash},
     fileXz: {name: "preview.pmtiles.xz", url: $url, sizeBytes: $xzSize, sha256: $xzHash}}'
}

# update_preview_entry <manifestFragmentFile> <needed:true|false>: se needed e go-pmtiles c'e',
# (ri)genera la mappa d'insieme e la scrive nel frammento con preview_manifest_entry; se manca go-pmtiles
# e serve, solo un avviso (mai fatale). Usata dalle rigenerazioni incrementali (sezione 2bis): la
# rigenerazione completa (sezione 5) passa invece dalla spec di generateManifest (previewPmtiles),
# che gestisce "file" come i POI e lascia solo "fileXz" a questo script.
update_preview_entry() {
  local fragment="$1" needed="$2"
  [ "$needed" = "true" ] || return 0
  if [ -z "$PMTILES_BIN" ]; then
    echo "::warning::go-pmtiles non trovato: $REGION_ID resta senza mappa d'insieme"
    return 0
  fi
  echo "-- $REGION_ID: (ri)genero la mappa d'insieme"
  if ! build_preview "https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles"; then
    echo "::warning::mappa d'insieme di $REGION_ID non estratta (build.protomaps.com): resta quella pubblicata"
    return 0
  fi
  local url="${ASSET_BASE_URL}/${REGION_ID}--${VERSION}--preview.pmtiles.xz"
  jq -c --argjson entry "$(preview_manifest_entry "$VERSION" "$url")" '.regions |= map(.preview = $entry)' "$fragment" > "$WORKDIR/preview-fragment.json"
  mv "$WORKDIR/preview-fragment.json" "$fragment"
}

mkdir -p "$OUTPUT_DIR"
# Marcatori di una run precedente sullo stesso outputDir: valgono solo quelli di questa run.
rm -f "$OUTPUT_DIR/.skipped" "$OUTPUT_DIR/.incremental"
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
  local elapsed=0
  # Overpass elabora la query lato server prima di iniziare a rispondere: i byte scaricati
  # restano a 0 per gran parte dell'attesa (anche 10+ minuti su un bbox nazionale), quindi il
  # solo cambio di dimensione non basta a distinguere un'attesa normale da un blocco - un
  # "battito" ogni 30s conferma che il processo e' ancora vivo anche a 0 byte.
  while kill -0 "$pid" 2>/dev/null; do
    sleep 2
    elapsed=$((elapsed + 2))
    # Una riga ogni 30 s, anche a 0 byte (Overpass sta ancora calcolando): abbastanza per vedere che
    # avanza, senza le centinaia di righe di un aggiornamento a ogni cambio di dimensione.
    if [ $((elapsed % 30)) -eq 0 ]; then
      local size_kb=0
      [ -f "$out" ] && size_kb=$(( $(wc -c < "$out" | tr -d ' ') / 1024 ))
      echo "-- $label: ${size_kb} KB scaricati (${elapsed}s trascorsi)..."
    fi
  done
  local rc=0
  wait "$pid" || rc=$?
  local total_kb=0
  [ -f "$out" ] && total_kb=$(( $(wc -c < "$out" | tr -d ' ') / 1024 ))
  if [ "$rc" -eq 0 ]; then
    echo "-- $label: ${total_kb} KB in ${elapsed}s"
  else
    echo "-- $label: curl uscito con $rc dopo ${elapsed}s (${total_kb} KB)"
  fi
  return "$rc"
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
# nessun controllo, si procede sempre con la rigenerazione completa (come
# nell'esecuzione locale via build-all-regions.sh).
# Nazione della regione (flagCode di regions.sh): con quella si scartano gli estratti Geofabrik dei paesi confinanti
# che il bbox contiene, e si taglia sul confine. Vuoto se la regione non e' nell'elenco: si usa solo il bbox.
REGION_ISO="$(source "$SCRIPT_DIR/regions.sh" && for spec in "${ALL_REGIONS[@]}"; do IFS='|' read -r id _ _ _ _ _ _ flag _ <<< "$spec"; [ "$id" = "$REGION_ID" ] && echo "$flag" && break; done || true)"
# Ceuta e Melilla (EA, codice riservato in ISO 3166-1): confine ed estratti Geofabrik (foglie "ceuta" e "melilla")
# della Spagna; il loro rettangolo comprende anche un po' di Marocco.
[ "$REGION_ISO" != "ea" ] || REGION_ISO=es
# Titolo Wikivoyage della regione: nome con cui si cercano i territori che per Nominatim non sono nazioni.
REGION_TITLE="$(source "$SCRIPT_DIR/regions.sh" && for spec in "${ALL_REGIONS[@]}"; do IFS='|' read -r id _ _ _ _ _ title _ <<< "$spec"; [ "$id" = "$REGION_ID" ] && echo "${title//_/ }" && break; done || true)"
# groupName di regions.sh: non vuoto per le regioni che sono parte di una nazione (stati USA, province).
REGION_GROUP="$(source "$SCRIPT_DIR/regions.sh" && for spec in "${ALL_REGIONS[@]}"; do IFS='|' read -r id _ _ _ _ _ _ _ group _ <<< "$spec"; [ "$id" = "$REGION_ID" ] && echo "$group" && break; done || true)"
# Confine OpenStreetMap della nazione (country_border.py, da Nominatim): i POI fuori si scartano, sia dagli estratti
# Geofabrik sia da Overpass (area 3600000000 + id della relazione). Il rettangolo da solo comprende anche i paesi vicini:
# meta' dei POI di San Marino erano italiani, quasi meta' di quelli dell'Ungheria austriaci o slovacchi (misura del
# 2026-10-11 sui pacchetti pubblicati). Senza confine (Canarie, Bonaire) resta il solo rettangolo.
# Una regione che e' parte di una nazione si taglia sul confine della sua suddivisione (stato, provincia), se
# country_border.py la trova; altrimenti su quello della nazione. Il rettangolo di uno stato comprende parti di quelli
# vicini (Virginia Occidentale: 73% dei POI fuori, misura del 2026-10-11), e l'app li mostrerebbe due volte a chi
# scarica le due regioni.
# Si cerca prima di decidere se riusare il poi.db pubblicato (sezione 2bis), e una volta sola.
BORDER_FILE=""
BORDER_AREA=""
BORDER_RELATION=""
BORDER_CHECKED=""
POI_SCOPE=""
# Regola del taglio sul confine, in manifest.json come poi.borderRule: un pacchetto pubblicato con un'altra regola si
# rigenera una volta anche se recente. 2: geofabrik_pois.py tiene solo gli oggetti con il punto (sull'oggetto) dentro il
# confine completo di country_border.py, quindi ogni via o area a cavallo in una sola regione e nessun membro lontano
# delle relazioni a cavallo (con la sola strategia smart di osmium: 1.534 POI su 7.546 della Mongolia Interna in Shanxi,
# Hebei, Pechino e Tianjin, tratti della Grande Muraglia). Il controllo sulla meta' dei POI pubblicati resta valido:
# il taglio ne toglie al massimo il 20% (Mongolia Interna).
POI_BORDER_RULE=2
find_border() {
  BORDER_CHECKED=true
  if [ "$REGION_ISO" = "ic" ] || [ "$REGION_ISO" = "bq" ]; then
    # Canarie (IC, codice riservato in ISO 3166-1) e Bonaire, Sint Eustatius e Saba (BQ): nessun confine, i rettangoli
    # non toccano terre di altri paesi. La relazione ES-CN segue la costa e lascerebbe fuori moli e mare: 90 POI su
    # 36.326 (terminal dei traghetti, porti, marine, Museo Atlantico, aree marine protette), nessuno di un altro paese.
    # Le isole BQ in OSM sono municipalita' dei Paesi Bassi (BQ-BO, BQ-SE, BQ-SA, country_code nl), non nazioni.
    echo "-- confine OSM: nessuno per $REGION_ID (codice $REGION_ISO), punti di interesse sul solo rettangolo"
  elif [ -n "$REGION_ISO" ] && BORDER_RELATION="$(python3 "$SCRIPT_DIR/country_border.py" --iso "$REGION_ISO" --name "$REGION_TITLE" --out "$WORKDIR/confine.geojson" \
      ${REGION_GROUP:+--subdivision "--bbox=$MIN_LON,$MIN_LAT,$MAX_LON,$MAX_LAT"} \
      --cache "${GEOFABRIK_CACHE:-$WORKDIR/geofabrik}" --user-agent "$PIPELINE_USER_AGENT")"; then
    BORDER_FILE="$WORKDIR/confine.geojson"
    BORDER_AREA=$((3600000000 + BORDER_RELATION))
    echo "-- confine OSM: relazione $BORDER_RELATION"
    # Regioni sorelle: tagliate sulla stessa relazione nel `manifest.json` pubblicato, con il rettangolo di regions.sh
    # che tocca questo (le 10 regioni russe sul confine della Russia: 17 coppie di rettangoli sovrapposti, POI delle
    # sovrapposizioni in due pacchetti). geofabrik_pois.py lascia ogni POI di una sovrapposizione a una regione sola
    # (--sibling): tutte le sorelle devono vedere gli stessi rettangoli, quindi vengono tutti da regions.sh, come --bbox.
    # In manifest.json come poi.scope (sorelle e regione, con i rettangoli), che fa rigenerare i POI quando le sorelle o
    # un rettangolo cambiano.
    if [ -s "${PUBLISHED_MANIFEST:-}" ]; then
      SIBLING_IDS="$(jq -r --arg id "$REGION_ID" --argjson rel "$BORDER_RELATION" \
        '(.regions // [])[] | select(.regionId != $id and .poi.borderRelation == $rel) | .regionId' "$PUBLISHED_MANIFEST" 2>/dev/null || true)"
      POI_SCOPE="$( (source "$SCRIPT_DIR/regions.sh" && printf '%s\n' "${ALL_REGIONS[@]}") \
        | awk -F'|' -v ids=" $(printf '%s ' $SIBLING_IDS)" -v self="$REGION_ID" -v a="$MIN_LON" -v b="$MIN_LAT" -v c="$MAX_LON" -v d="$MAX_LAT" '
          index(ids, " " $1 " ") && $3 <= c && $5 >= a && $4 <= d && $6 >= b { out[n++] = $1 ":" $3 "," $4 "," $5 "," $6 }
          END { if (n) { out[n++] = self ":" a "," b "," c "," d; for (i = 0; i < n; i++) print out[i] } }' | sort | paste -sd' ')"
      [ -z "$POI_SCOPE" ] || echo "-- regioni sorelle sullo stesso confine: $POI_SCOPE"
    fi
  elif [ "$(printf '%s' "${PUBLISHED_REGION:-}" | jq -r '.poi.border // false' 2>/dev/null || true)" = "true" ]; then
    # I POI pubblicati sono gia' tagliati sul confine: senza (Nominatim non raggiungibile) si pubblicherebbero di nuovo
    # quelli dei paesi vicini.
    echo "ERRORE: confine OSM di $REGION_ID non disponibile e i POI pubblicati sono tagliati sul confine: li tengo" >&2
    exit 1
  else
    echo "::warning::confine OSM di $REGION_ID non trovato (${REGION_ISO:-nessun codice ISO}): punti di interesse sul solo rettangolo"
  fi
}

if [ -n "$PUBLISHED_MANIFEST_URL" ] && command -v jq >/dev/null 2>&1; then
  echo "-- controllo se $REGION_ID e' gia' aggiornata rispetto a $PUBLISHED_MANIFEST_URL..."
  PUBLISHED_MANIFEST="$WORKDIR/published-manifest.json"
  # Con ritentativi e, se non arriva, errore: una rigenerazione completa per un problema di rete
  # darebbe nuove versioni (e riscaricamenti) a mappa, rete stradale e POI senza motivo. Solo un 404 (nessun
  # `manifest.json` ancora pubblicato, prima pubblicazione) porta alla rigenerazione completa.
  PUBLISHED_MANIFEST_HTTP="$(fetch_published_manifest "$PUBLISHED_MANIFEST_URL" "$PUBLISHED_MANIFEST")"
  if [ "$PUBLISHED_MANIFEST_HTTP" != "200" ] && [ "$PUBLISHED_MANIFEST_HTTP" != "404" ]; then
    echo "ERRORE: manifest.json pubblicato non scaricabile da $PUBLISHED_MANIFEST_URL (HTTP ${PUBLISHED_MANIFEST_HTTP:-000})" >&2
    exit 1
  fi
  if [ "$PUBLISHED_MANIFEST_HTTP" = "200" ]; then
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
        code="$(rd5_head "${BROUTER_BASE}/${tile}.rd5" "$WORKDIR/head.txt")"
        PRECHECKED_TILE_CODE["$tile"]="$code"
        if [ "$code" = "200" ]; then
          # "|| true": una risposta senza content-length (chunked) non deve fermare lo script con pipefail.
          size="$(tr -d '\r' < "$WORKDIR/head.txt" | { grep -i '^content-length:' || true; } | tail -1 | awk '{print $2}')"
          printf '%s.rd5\t%s\n' "$tile" "${size:-0}" >> "$EXPECTED_TSV"
        elif [ "$code" != "404" ]; then
          echo "ERRORE: HEAD di ${tile}.rd5 fallita (HTTP $code) dopo 4 tentativi, non posso sapere se la tile esiste" >&2
          exit 1
        fi
        lat=$((lat + 5))
      done
      lon=$((lon + 5))
    done
    EXPECTED_SORTED="$(jq -R -s -c --arg clip "$RD5_CLIP" '
      split("\n") | map(select(length > 0) | split("\t") | {name: .[0], sourceKey: "\(.[1]) \($clip)"})
      | sort_by(.name)
    ' "$EXPECTED_TSV")"

    PUBLISHED_SORTED="$(jq -c --arg id "$REGION_ID" '
      [(.regions // [])[] | select(.regionId == $id) | (.routing.files // .files // [])[] | select(.name | endswith(".rd5"))
        | {name, sourceKey: (.sourceKey // "")}]
      | sort_by(.name)
    ' "$PUBLISHED_MANIFEST" 2>/dev/null || echo "[]")"

    # Eta' del poi.db pubblicato, dal nome dell'asset (regionId--YYYY.MM.DD--poi.db, o --content.db
    # per le regioni convertite dal formato v1; dal 2026-09-27 la versione ha anche il suffisso della
    # run, YYYY.MM.DD.<run>.<tentativo>). Serve sia allo skip qui sotto sia all'aggiornamento
    # incrementale: senza, una regione con le tile invariate verrebbe saltata per sempre e i suoi
    # POI non si aggiornerebbero mai.
    PUBLISHED_REGION="$(jq -c --arg id "$REGION_ID" '[(.regions // [])[] | select(.regionId == $id)][0] // empty' "$PUBLISHED_MANIFEST" 2>/dev/null || true)"
    PUBLISHED_POI_URL="$(printf '%s' "$PUBLISHED_REGION" | jq -r '.poi.file.url // ""' 2>/dev/null || true)"
    # Rettangolo cambiato in regions.sh con le stesse tile: la regione saltata o aggiornata in modo incrementale terrebbe
    # in map.source quello pubblicato, che l'app usa per la mappa e le regioni sorelle per i POI. Si rigenera tutta.
    SAME_BBOX="$(printf '%s' "$PUBLISHED_REGION" | jq --argjson b "[$MIN_LON,$MIN_LAT,$MAX_LON,$MAX_LAT]" \
      '[.map.source.minLon, .map.source.minLat, .map.source.maxLon, .map.source.maxLat] == $b' 2>/dev/null || true)"
    [ "$SAME_BBOX" = true ] || [ -z "$PUBLISHED_REGION" ] || echo "-- $REGION_ID: rettangolo diverso da quello pubblicato: rigenero tutto"
    POI_DATE="$(printf '%s' "$PUBLISHED_POI_URL" | sed -n 's#.*--\([0-9]\{4\}\.[0-9]\{2\}\.[0-9]\{2\}\)\(\.[0-9][0-9.]*\)\?--\(poi\|content\)\.db\(\.xz\)\?$#\1#p')"
    POI_AGE_DAYS=""
    if [ -n "$POI_DATE" ]; then
      POI_AGE_DAYS="$(( ($(date -u +%s) - $(date -u -d "${POI_DATE//./-}" +%s)) / 86400 ))"
    fi
    POI_STALE=false

    # Impronta delle tile della regione nella build corrente e confronto con la mappa pubblicata.
    MAP_FINGERPRINT="$(cd "$REPO_ROOT" && ./gradlew -q :tools:data-pipeline:content:mapFingerprint \
      --args="https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles $MIN_LON $MIN_LAT $MAX_LON $MAX_LAT $MAP_FINGERPRINT_MIN_ZOOM $MAP_MAX_ZOOM" \
      | sed -n 's/.*impronta \([0-9a-f]\{64\}\).*/\1/p' | tail -1 || true)"
    [ -n "$MAP_FINGERPRINT" ] || echo "::warning::$REGION_ID: impronta delle tile non calcolata (mapFingerprint), la mappa non verra' aggiornata in questa run" >&2
    PUBLISHED_MAP_FINGERPRINT="$(printf '%s' "$PUBLISHED_REGION" | jq -r '.map.fingerprint // ""' 2>/dev/null || true)"
    PUBLISHED_MAP_DATE="$(printf '%s' "$PUBLISHED_REGION" | jq -r '.map.version // ""' 2>/dev/null | sed -n 's/^\([0-9]\{4\}\)\.\([0-9]\{2\}\)\.\([0-9]\{2\}\)\(\.[0-9][0-9.]*\)\?$/\1-\2-\3/p' || true)"
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
    # POI pubblicati senza taglio o tagliati su un altro confine (la nazione invece dello stato): si rigenerano anche
    # se recenti.
    find_border
    if [ -n "$BORDER_FILE" ] && [ "$(printf '%s' "$PUBLISHED_REGION" | jq -r '.poi.borderRelation // ""' 2>/dev/null || true)" != "$BORDER_RELATION" ]; then
      echo "-- $REGION_ID: POI pubblicati non tagliati sul confine $BORDER_RELATION: li rigenero"
      POI_STALE=true
    elif [ -n "$BORDER_FILE" ] && [ "$(printf '%s' "$PUBLISHED_REGION" | jq -r '.poi.borderRule // ""' 2>/dev/null || true)" != "$POI_BORDER_RULE" ]; then
      echo "-- $REGION_ID: POI pubblicati tagliati senza la regola $POI_BORDER_RULE del confine: li rigenero"
      POI_STALE=true
    elif [ -n "$BORDER_FILE" ] && [ "$(printf '%s' "$PUBLISHED_REGION" | jq -r '.poi.scope // ""' 2>/dev/null || true)" != "$POI_SCOPE" ]; then
      echo "-- $REGION_ID: POI pubblicati con altre regioni sorelle: li rigenero"
      POI_STALE=true
    fi

    if [ -n "$EXPECTED_SORTED" ] && [ "$EXPECTED_SORTED" != "[]" ] && [ "$PUBLISHED_SORTED" = "$EXPECTED_SORTED" ] && [ "$POI_STALE" != "true" ] && [ "$MAP_DUE" != "true" ] && [ "$SAME_BBOX" = true ] \
      && [ "$(printf '%s' "$PUBLISHED_REGION" | jq 'has("routingCar")')" = "true" ]; then
      echo "== [$REGION_ID] invariata rispetto al manifest.json pubblicato (stesse tile .rd5, stesse dimensioni): salto la rigenerazione =="
      : > "$OUTPUT_DIR/.skipped"
      # manifestVersion quella del `manifest.json` pubblicato: una regione ancora v1 viene convertita da
      # mergeManifests (vedi MergeManifests.kt), non qui.
      # Mappe pubblicate prima dell'impronta: la registra senza cambiare versione (riferimento per
      # i confronti successivi).
      jq -c --arg id "$REGION_ID" --arg fp "$MAP_FINGERPRINT" '{manifestVersion: .manifestVersion, regions: [(.regions // [])[] | select(.regionId == $id)
        | if (.map.fingerprint // "") == "" and $fp != "" then .map.fingerprint = $fp else . end]}' \
        "$PUBLISHED_MANIFEST" > "$OUTPUT_DIR/manifest-fragment.json"
      # La mappa non e' cambiata, ma una regione senza ancora una mappa d'insieme pubblicata la riceve
      # comunque una volta sola, senza aspettare la prossima rigenerazione completa.
      if [ "$(printf '%s' "$PUBLISHED_REGION" | jq 'has("preview")')" != "true" ]; then
        update_preview_entry "$OUTPUT_DIR/manifest-fragment.json" true
        if [ -f "$OUTPUT_DIR/preview.pmtiles.xz" ]; then
          rm -f "$OUTPUT_DIR/.skipped"
          : > "$OUTPUT_DIR/.incremental"
        fi
      fi
      echo "== [$REGION_ID] fatto (saltata, frammento riusato da quello pubblicato) =="
      exit 0
    fi
    # Aggiornamento incrementale: la regione e' gia' pubblicata nel formato a pacchetti (v2) con
    # le stesse tile (cambiano solo le dimensioni di alcune). Si riscaricano solo le tile cambiate
    # (nuova versione della rete stradale, e della mappa, che punta alla build Protomaps corrente; nessuna
    # delle due cambia versione se non e' cambiata nessuna tile, altrimenti l'app riscaricherebbe
    # per niente). I POI non dipendono dalle tile della rete stradale: il poi.db pubblicato resta com'e' se
    # ha al piu' POI_MAX_AGE_DAYS giorni, altrimenti si rigenera solo lui (sezione 3 in modalita'
    # POI_ONLY). Una regione ancora v1 (senza la rete stradale "routing"), o senza la variante auto (routingCar, che va
    # ricavata da tutte le tile, non solo da quelle cambiate), viene rigenerata per intero.
    if [ -n "$PUBLISHED_REGION" ] && [ "$EXPECTED_SORTED" != "[]" ] && [ "$(printf '%s' "$PUBLISHED_REGION" | jq 'has("routing") and has("routingCar")')" = "true" ]; then
      SAME_TILES="$(jq -n --argjson a "$EXPECTED_SORTED" --argjson b "$PUBLISHED_SORTED" '($a | map(.name)) == ($b | map(.name))')"
      if [ "$SAME_TILES" = "true" ] && [ "$SAME_BBOX" = true ] && [ -n "$POI_AGE_DAYS" ]; then
        if [ "$POI_STALE" = "true" ]; then
          echo "-- $REGION_ID: stesse tile, poi.db di $POI_AGE_DAYS giorni (max $POI_MAX_AGE_DAYS): aggiorno le tile cambiate e rigenero il poi.db"
          POI_ONLY=true
        else
          echo "-- $REGION_ID: stesse tile, poi.db di $POI_AGE_DAYS giorni (max $POI_MAX_AGE_DAYS): aggiorno solo le tile cambiate"
        fi
        UPDATED_TSV="$WORKDIR/updated-rd5.tsv"
        : > "$UPDATED_TSV"
        CHANGED_TILES="$(jq -r -n --argjson a "$EXPECTED_SORTED" --argjson b "$PUBLISHED_SORTED" \
          '$a[] as $e | ($b[] | select(.name == $e.name)) as $p | select($p.sourceKey != $e.sourceKey) | $e.name')"
        while read -r tileFile; do
          [ -n "$tileFile" ] || continue
          fetch_rd5 "$tileFile" "$UPDATED_TSV"
        done <<< "$CHANGED_TILES"
        jq -R -s -c "$RD5_TSV_TO_JSON" "$UPDATED_TSV" > "$WORKDIR/updated-rd5.json"
        build_car_variant "$UPDATED_TSV" "$WORKDIR/updated-car.tsv"
        jq -R -s -c "$RD5_TSV_TO_JSON" "$WORKDIR/updated-car.tsv" > "$WORKDIR/updated-car.json"
        # Mappa e rete stradale hanno versioni indipendenti: una tile .rd5 cambiata non fa ri-estrarre
        # la mappa.
        jq -c --arg id "$REGION_ID" --arg version "$VERSION" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
          --arg src "https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles" --slurpfile upd "$WORKDIR/updated-rd5.json" --slurpfile updCar "$WORKDIR/updated-car.json" \
          --arg fp "$MAP_FINGERPRINT" --argjson mapdue "$MAP_DUE" '
          ($upd[0] | map({key: .name, value: .}) | from_entries) as $u
          | ($updCar[0] | map({key: .name, value: .}) | from_entries) as $uc
          | {manifestVersion: 2, regions: [(.regions // [])[] | select(.regionId == $id)
              | .updatedAt = $now
              | .map.source.sourceUrl = $src
              | if $mapdue then .map.version = $version | .map.fingerprint = $fp
                elif (.map.fingerprint // "") == "" and $fp != "" then .map.fingerprint = $fp
                else . end
              | if ($u | length) > 0 then
                  .routing.version = $version | .routing.files |= map(if $u[.name] then $u[.name] else . end)
                  | .routingCar.version = $version | .routingCar.files |= map(if $uc[.name] then $uc[.name] else . end)
                else . end]}' \
          "$PUBLISHED_MANIFEST" > "$OUTPUT_DIR/manifest-fragment.json"
        # Mappa d'insieme: si rigenera solo se la mappa e' davvero cambiata (MAP_DUE, non solo qualche
        # tile .rd5) o se la regione non ne ha ancora una - non a ogni run incrementale.
        PREVIEW_NEEDED=false
        { [ "$MAP_DUE" = "true" ] || [ "$(printf '%s' "$PUBLISHED_REGION" | jq 'has("preview")')" != "true" ]; } && PREVIEW_NEEDED=true
        update_preview_entry "$OUTPUT_DIR/manifest-fragment.json" "$PREVIEW_NEEDED"
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
    echo "-- nessun manifest.json pubblicato su $PUBLISHED_MANIFEST_URL (HTTP 404): rigenerazione completa"
  fi
fi

# --- 3. Segmenti .rd5 + POI Overpass, tile per tile (stessa griglia 5x5 gradi) -------------------
# Le due cose sono unite in un solo giro sulla griglia:
# interrogare Overpass anche per una tile senza .rd5 (nessuna strada estratta, quasi certamente
# oceano aperto) e' tempo perso - es. il Giappone (arcipelago, fino a 42 tile nella griglia
# rettangolare che ne racchiude il territorio, la maggior parte mare), dove ogni tile oceanica
# attenderebbe i ritentativi sui mirror non raggiungibili (vedi OVERPASS_ENDPOINTS) per
# ottenere zero POI.
#
# Una singola query Overpass sull'intero bbox non regge invece per una nazione grande: gli Stati
# Uniti (bbox contiguo, 48 stati) hanno fatto scadere tutti i mirror pubblici, l'ultimo con un 504
# Gateway Timeout del reverse proxy anche dopo 900s pieni - non e' un timeout locale ritentabile,
# il server si arrende prima di finire di elaborare un'area cosi' grande. Si spezza quindi anche
# la query POI nella stessa griglia usata per i segmenti .rd5: per un paese piccolo come San
# Marino resta un solo chunk/una sola query, per uno grande diventano N query piu' leggere.
# generatePoi (piu' sotto) unisce i risultati delle tile con terra emersa.
#
# Mirror Overpass: OVERPASS_ENDPOINTS in lib.sh. ATTEMPTS = due giri sulle istanze: con tre shard insieme i mirror
# rifiutano per qualche minuto (429, risposta vuota in 2 s) e il secondo giro, dopo attese piu' lunghe, di solito passa.
ATTEMPTS=$(( ${#OVERPASS_ENDPOINTS[@]} * 2 ))

# Chiavi di tag OSM riconosciute come punti di interesse — sottoinsieme minimo, non lo schema POI
# completo di OSM (amenity/shop/tourism/leisure/historic coprono la maggior parte dei casi comuni
# per una guida di viaggio). Unica fonte di verita': passata a generatePoi via CLI (vedi POI_ARGS
# piu' sotto) invece di essere duplicata anche li', cosi' la query Overpass e il filtro dei nodi
# in GeneratePoi.kt non possono disallinearsi.
POI_TAG_KEYS=(amenity shop tourism leisure historic)
# Chiavi lette da generatePoi ma interrogate su Overpass solo con i filtri mirati di
# fetch_overpass_chunk (stazioni, aeroporti, ponti, piazze, parchi nazionali, piazze pedonali, cime, siti): con la
# chiave intera arriverebbero anche binari, passaggi a livello, segnali, strade e alberi.
POI_EXTRA_TAG_KEYS=(railway aeroway office man_made landuse boundary place highway natural type)

fmax() { awk -v a="$1" -v b="$2" 'BEGIN { print (a+0>b+0)?a:b }'; }
fmin() { awk -v a="$1" -v b="$2" 'BEGIN { print (a+0<b+0)?a:b }'; }

# Ritenta un singolo chunk sui mirror noti (stessa rilevazione del <remark> di timeout mascherato
# da XML valido, vedi sotto). Ritorna 1 se nessun mirror ha risposto validamente dopo $ATTEMPTS
# tentativi - il chiamante decide se e' fatale.
fetch_overpass_chunk() {
  local chunkMinLon="$1" chunkMinLat="$2" chunkMaxLon="$3" chunkMaxLat="$4" outFile="$5"
  # Con il confine della nazione (BORDER_AREA, vedi sotto) ogni filtro ha anche l'area della nazione: Overpass scarta
  # sul server i POI dei paesi confinanti che il rettangolo contiene.
  local bbox="(${chunkMinLat},${chunkMinLon},${chunkMaxLat},${chunkMaxLon})"
  local query="[out:xml][timeout:900];"
  if [ -n "${BORDER_AREA:-}" ]; then
    query="${query}area(id:${BORDER_AREA})->.confine;"
    bbox="(area.confine)${bbox}"
  fi
  query="${query}("
  for tag in "${POI_TAG_KEYS[@]}"; do
    query="${query}node[\"${tag}\"]${bbox};"
  done
  # Parcheggi, autostazioni, ospedali, caserme dei pompieri, chiese, monasteri e terminal dei
  # traghetti anche come aree (quasi sempre disegnati cosi'): "out center" da' alle way un punto.
  query="${query}way[\"amenity\"~\"^(parking|bus_station|hospital|fire_station|place_of_worship|monastery|ferry_terminal)$\"]${bbox};"
  query="${query}way[\"tourism\"=\"information\"][\"information\"~\"^(office|visitor_centre)$\"]${bbox};"
  # Ambasciate e consolati col tag moderno (office=diplomatic), non solo amenity=embassy.
  query="${query}nw[\"office\"=\"diplomatic\"]${bbox};"
  # Parchi pubblici e a pagamento (parchi a tema e acquatici, zoo), porti turistici e riserve naturali: quasi sempre aree
  # o relazioni. Dei parchi e delle riserve solo quelli con un nome: gli altri parchi sono per lo piu'
  # aiuole e giardinetti.
  query="${query}wr[\"leisure\"~\"^(park|nature_reserve)$\"][\"name\"]${bbox};wr[\"leisure\"~\"^(water_park|marina)$\"]${bbox};wr[\"tourism\"~\"^(theme_park|zoo)$\"]${bbox};"
  # Musei e monumenti disegnati come edifici o complessi (Tour Eiffel, Louvre, Colosseo): da soli punti
  # mancavano proprio i piu' famosi. Solo con un nome, come i parchi.
  query="${query}wr[\"tourism\"~\"^(museum|gallery|attraction)$\"][\"name\"]${bbox};wr[\"historic\"~\"^(castle|monument|ruins|archaeological_site|fort|city_gate)$\"][\"name\"]${bbox};"
  # Luoghi famosi che i filtri sopra perdevano (tra i 50 con piu' voci Wikipedia di 11 regioni ne mancava il 9%):
  # chiese e moschee disegnate come relazioni, parchi nazionali e, solo con wikidata (i famosi, non le migliaia di
  # ponticelli e giardinetti), ponti, complessi religiosi, giardini, piazze e gli altri valori di historic (mura,
  # tombe, edifici storici).
  query="${query}relation[\"amenity\"=\"place_of_worship\"]${bbox};wr[\"boundary\"=\"national_park\"][\"name\"]${bbox};"
  query="${query}wr[\"boundary\"=\"protected_area\"][\"protect_class\"=\"2\"][\"name\"]${bbox};"
  query="${query}wr[\"historic\"][\"name\"][\"wikidata\"]${bbox};wr[\"man_made\"=\"bridge\"][\"name\"][\"wikidata\"]${bbox};"
  query="${query}wr[\"landuse\"=\"religious\"][\"name\"][\"wikidata\"]${bbox};wr[\"leisure\"=\"garden\"][\"name\"][\"wikidata\"]${bbox};"
  query="${query}nwr[\"place\"=\"square\"][\"name\"][\"wikidata\"]${bbox};"
  # Altri luoghi famosi, sempre solo con wikidata: piazze pedonali (area=yes o relazione multipoligono), relazioni di vie
  # con un nome di piazza e anche wikipedia (PREFISSI_DI_PIAZZA in geofabrik_pois.py), siti monumentali (santuari di Nikko), biblioteche
  # disegnate come aree, catene montuose e le cime che hanno anche wikipedia.
  query="${query}way[\"highway\"=\"pedestrian\"][\"area\"=\"yes\"][\"name\"][\"wikidata\"]${bbox};"
  query="${query}relation[\"highway\"=\"pedestrian\"][\"type\"=\"multipolygon\"][\"name\"][\"wikidata\"]${bbox};"
  query="${query}relation[\"type\"=\"associatedStreet\"][\"name\"~\"^(Place|Piazza|Piazzale|Plaza|Platz|Square|Praça|Plac|Largo|Campo) \"][\"wikidata\"][\"wikipedia\"]${bbox};"
  query="${query}relation[\"type\"=\"site\"][\"name\"][\"wikidata\"]${bbox};wr[\"amenity\"=\"library\"][\"name\"][\"wikidata\"]${bbox};"
  query="${query}nwr[\"natural\"=\"mountain_range\"][\"name\"][\"wikidata\"]${bbox};"
  query="${query}nwr[\"natural\"=\"peak\"][\"name\"][\"wikidata\"][\"wikipedia\"]${bbox};"
  # Trasporti: stazioni (treno e metro), autostazioni, aeroporti con codice IATA (niente aviosuperfici).
  query="${query}nw[\"railway\"~\"^(station|halt)$\"]${bbox};nwr[\"aeroway\"=\"aerodrome\"][\"iata\"]${bbox};"
  query="${query});out center;"
  for attempt in $(seq 1 "$ATTEMPTS"); do
    local endpoint="${OVERPASS_ENDPOINTS[$(( (attempt - 1) % ${#OVERPASS_ENDPOINTS[@]} ))]}"
    echo "-- tentativo $attempt/$ATTEMPTS su $endpoint..."
    # Tolto prima di ogni tentativo: senza, un tentativo fallito prima di scrivere lascerebbe il file
    # del tentativo precedente.
    rm -f "$outFile"
    # Attenzione: su un timeout della query (bbox grande), Overpass non fallisce la richiesta
    # HTTP ma risponde comunque con un <osm> ben formato contenente un
    # <remark>runtime error: Query timed out...</remark> e zero nodi -- un semplice grep "<osm"
    # lo scambierebbe per una risposta valida, producendo silenziosamente 0 POI (visto con
    # l'Italia: timeout:180 troppo basso per un bbox nazionale). Serve anche l'esito di curl: un
    # download interrotto (--max-time, connessione chiusa) lascia un XML troncato che inizia con <osm.
    if download_with_progress "$outFile" "Overpass POI ($endpoint)" -sS --max-time 950 -A "$PIPELINE_USER_AGENT" "$endpoint" --data-urlencode "data=${query}" -o "$outFile" \
      && grep -q "<osm" "$outFile" && ! grep -q "<remark>" "$outFile"; then
      return 0
    fi
    if [ "$attempt" -lt "$ATTEMPTS" ]; then
      local wait=$(( attempt * 20 > 120 ? 120 : attempt * 20 ))
      echo "-- $endpoint non disponibile o in timeout, riprovo tra ${wait}s..."
      sleep "$wait"
    fi
  done
  return 1
}

# POI dagli estratti Geofabrik quando osmium c'e' (GEOFABRIK_POI=false per non usarli): stessi oggetti
# della query Overpass (confronto sulla Lettonia: 107.915 oggetti contro 107.934, la differenza sono
# le modifiche del giorno), in secondi invece che nei minuti di Overpass, che resta il ripiego se
# qualcosa va storto. GEOFABRIK_CACHE (facoltativa, la imposta il job "build") tiene gli estratti
# ridotti per le regioni successive dello stesso job.
[ -n "$BORDER_CHECKED" ] || find_border

GEOFABRIK_XML=""
SIBLING_ARGS=()
for sibling in $POI_SCOPE; do [ "${sibling%%:*}" = "$REGION_ID" ] || SIBLING_ARGS+=(--sibling "$sibling"); done
[ -z "$POI_SCOPE" ] || SIBLING_ARGS+=(--id "$REGION_ID")
if [ "${GEOFABRIK_POI:-true}" = "true" ] && command -v osmium >/dev/null; then
  echo "-- POI dagli estratti Geofabrik..."
  if python3 "$SCRIPT_DIR/geofabrik_pois.py" --bbox="$MIN_LON,$MIN_LAT,$MAX_LON,$MAX_LAT" --iso="$REGION_ISO" ${BORDER_FILE:+--border "$BORDER_FILE"} "${SIBLING_ARGS[@]}" \
    --out "$WORKDIR/poi-geofabrik.osm.xml" --cache "${GEOFABRIK_CACHE:-$WORKDIR/geofabrik}" --user-agent "$PIPELINE_USER_AGENT"; then
    GEOFABRIK_XML="$WORKDIR/poi-geofabrik.osm.xml"
  else
    # Avviso nel riepilogo della run, non solo nel log: dal 2026-10-11 un poligono vuoto nell'indice di Geofabrik ha
    # mandato tutte le regioni su Overpass, che con tre shard insieme rifiuta le richieste e lascia regioni senza POI.
    echo "::warning::estratti Geofabrik non usabili per $REGION_ID (errore sopra), interrogo Overpass"
  fi
fi

echo "-- risolvo segmenti .rd5 e interrogo Overpass per i POI, tile per tile..."
ROUTING_TSV="$WORKDIR/routing-rd5.tsv"
: > "$ROUTING_TSV"
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
      code="$(rd5_head "$url" "$WORKDIR/head.txt")"
    fi
    if [ "$code" != "200" ] && [ "$code" != "404" ]; then
      echo "ERRORE: HEAD di $tile.rd5 fallita (HTTP $code) dopo 4 tentativi, non posso sapere se la tile esiste" >&2
      exit 1
    fi
    if [ "$code" = "200" ]; then
      if [ "$POI_ONLY" != "true" ]; then
        fetch_rd5 "$tile.rd5" "$ROUTING_TSV"
      fi

      if [ -n "$GEOFABRIK_XML" ]; then
        lat=$(( lat + 5 ))
        continue
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

if [ ! -s "$ROUTING_TSV" ] && [ "$POI_ONLY" != "true" ]; then
  echo "ERRORE: nessun segmento .rd5 trovato per il bbox di $REGION_ID" >&2
  exit 1
fi
[ -z "$GEOFABRIK_XML" ] || POI_XML_FILES=("$GEOFABRIK_XML")
if [ "${#POI_XML_FILES[@]}" -eq 0 ]; then
  echo "ERRORE: nessun chunk Overpass ha prodotto dati validi per $REGION_ID" >&2
  exit 1
fi
if [ "$FAILED_CHUNKS" -gt 0 ] && [ -n "${PUBLISHED_POI_URL:-}" ]; then
  # Un poi.db parziale sostituirebbe quello completo gia' pubblicato: meglio fallire e tenere quello.
  echo "ERRORE: $FAILED_CHUNKS/$chunkIndex chunk Overpass falliti e $REGION_ID ha gia' dei POI pubblicati: li tengo" >&2
  exit 1
elif [ "$FAILED_CHUNKS" -gt 0 ]; then
  echo "-- attenzione: $FAILED_CHUNKS/$chunkIndex chunk falliti, alcuni POI di $REGION_ID mancheranno"
fi
# Ripiego Overpass: l'area della nazione nella query tiene anche le vie e le aree a cavallo del confine e nessuna
# regola divide le sorelle russe. geofabrik_pois.py --overpass applica confine e sorelle sul nodo o sul <center>;
# borderRule e scope restano fuori dalla voce (sezione 4), quindi la run dopo rigenera i POI da Geofabrik.
OVERPASS_FILTERED=false
if [ -z "$GEOFABRIK_XML" ] && { [ -n "$BORDER_FILE" ] || [ -n "$POI_SCOPE" ]; }; then
  OVERPASS_ARGS=()
  for f in "${POI_XML_FILES[@]}"; do OVERPASS_ARGS+=(--overpass "$f"); done
  if python3 "$SCRIPT_DIR/geofabrik_pois.py" --bbox="$MIN_LON,$MIN_LAT,$MAX_LON,$MAX_LAT" ${BORDER_FILE:+--border "$BORDER_FILE"} \
    "${SIBLING_ARGS[@]}" "${OVERPASS_ARGS[@]}" --out "$WORKDIR/poi-overpass.osm.xml"; then
    POI_XML_FILES=("$WORKDIR/poi-overpass.osm.xml")
    OVERPASS_FILTERED=true
  else
    echo "::warning::POI Overpass di $REGION_ID non filtrati sul confine e sulle sorelle (errore sopra)"
  fi
fi

# --- 4. poi.db e poi-extra.db, via il tool Kotlin generatePoi ------------------------------------
# poi.db: i POI che la mappa mostra. poi-extra.db: quelli scaricabili a parte (fontanelle, tavoli da
# picnic...), assente se la regione non ne ha. Gli altri non si pubblicano (regole in core:poi).
POI_DB="$OUTPUT_DIR/poi.db"
POI_EXTRA_DB="$OUTPUT_DIR/poi-extra.db"
rm -f "$POI_DB" "$POI_EXTRA_DB" "$POI_DB.xz" "$POI_EXTRA_DB.xz"
cd "$REPO_ROOT"
echo "-- genero poi.db e poi-extra.db..."
POI_TAG_KEYS_ARG="$(IFS=,; echo "${POI_TAG_KEYS[*]} ${POI_EXTRA_TAG_KEYS[*]}" | tr ' ' ,)"
POI_ARGS="\"$REGION_ID\" \"$(winpath "$POI_DB")\" \"$(winpath "$POI_EXTRA_DB")\" \"$POI_TAG_KEYS_ARG\""
for f in "${POI_XML_FILES[@]}"; do
  POI_ARGS="$POI_ARGS \"$(winpath "$f")\""
done

# poi_count <poi.db>: numero di POI del pacchetto.
poi_count() {
  python3 -c 'import sqlite3, sys; print(sqlite3.connect(sys.argv[1]).execute("SELECT count(*) FROM poi").fetchone()[0])' "$1"
}
# POI base del poi.db gia' pubblicato: generatePoi rifiuta un risultato vuoto o sotto la meta' (un
# chunk Overpass puo' essere valido ma vuoto: meglio fallire e tenere il pacchetto pubblicato). Viene
# da poi.count di `manifest.json`; le voci pubblicate prima di quel campo si contano sul poi.db pubblicato,
# che si scarica (una volta, finche' la regione non e' ripubblicata). Se non si riesce a contarli resta
# solo il controllo sul risultato vuoto. Quei poi.db vengono da Overpass sull'intero bbox, paesi
# confinanti compresi (Eritrea: 5117 POI, quasi meta' in Arabia Saudita, Yemen ed Etiopia), mentre gli
# estratti Geofabrik tengono la sola nazione: con Geofabrik non si confrontano, resta il controllo sul
# risultato vuoto (un estratto incompleto fa gia' fallire geofabrik_pois.py).
PUBLISHED_POI_COUNT="$(printf '%s' "${PUBLISHED_REGION:-}" | jq -r '.poi.count // empty' 2>/dev/null || true)"
POI_BORDER=false
POI_BORDER_RULE_JSON=null
if [ -n "$BORDER_FILE" ]; then
  POI_BORDER=true
  # La regola la applica geofabrik_pois.py sul confine completo: i POI di Overpass (ripiego) o tagliati sul confine
  # semplificato (lookup di Nominatim fallito) restano senza, e la run dopo li rigenera.
  if [ -n "$GEOFABRIK_XML" ] && [ "$(jq -r '.properties.completo // false' "$BORDER_FILE" 2>/dev/null || true)" = true ]; then
    POI_BORDER_RULE_JSON="$POI_BORDER_RULE"
  fi
fi
# Le sorelle le applica geofabrik_pois.py, sul punto dell'oggetto solo con gli estratti: con Overpass (punto dal
# <center>) la voce resta senza, e la run dopo rigenera i POI.
POI_SCOPE_JSON=null
if [ -n "$GEOFABRIK_XML" ] && [ -n "$POI_SCOPE" ]; then
  POI_SCOPE_JSON="$(jq -n --arg scope "$POI_SCOPE" '$scope')"
fi
# Primo pacchetto tagliato su questo confine: quello pubblicato aveva anche i POI dei paesi o degli stati vicini (Oman:
# due terzi negli Emirati; Virginia Occidentale: tre quarti negli stati vicini), quindi il confronto con la meta'
# scarterebbe il risultato giusto. Lo stesso per il primo pacchetto con altre sorelle, se geofabrik_pois.py le ha
# applicate (sugli estratti o sui chunk Overpass): le regioni russe cedono fino a tre quarti dei POI pubblicati, che stavano anche nelle sorelle (Nord europeo
# 25% tenuti, Volga 35%, misura del 2026-10-19). Resta il controllo sul risultato vuoto.
if [ "$POI_BORDER" = true ] && { [ "$(printf '%s' "${PUBLISHED_REGION:-}" | jq -r '.poi.borderRelation // ""' 2>/dev/null || true)" != "$BORDER_RELATION" ] \
  || { { [ -n "$GEOFABRIK_XML" ] || [ "$OVERPASS_FILTERED" = true ]; } && [ "$(printf '%s' "${PUBLISHED_REGION:-}" | jq -r '.poi.scope // ""' 2>/dev/null || true)" != "$POI_SCOPE" ]; }; }; then
  PUBLISHED_POI_COUNT=""
  PUBLISHED_POI_URL=""
fi
if [ -z "$PUBLISHED_POI_COUNT" ] && [ -n "${PUBLISHED_POI_URL:-}" ] && [ -z "$GEOFABRIK_XML" ]; then
  echo "-- conto i POI del poi.db pubblicato..."
  # Il file compresso (fileXz): nelle voci meno recenti file.url punta al database non compresso, che
  # non e' tra gli asset pubblicati.
  PUBLISHED_POI_XZ_URL="$(printf '%s' "${PUBLISHED_REGION:-}" | jq -r '.poi.fileXz.url // .poi.file.url // ""' 2>/dev/null || true)"
  if curl -fsSL --retry 3 --max-time 600 -A "$PIPELINE_USER_AGENT" "$PUBLISHED_POI_XZ_URL" -o "$WORKDIR/published-poi.db.xz" \
    && xz -dc "$WORKDIR/published-poi.db.xz" > "$WORKDIR/published-poi.db"; then
    PUBLISHED_POI_COUNT="$(poi_count "$WORKDIR/published-poi.db" || true)"
  else
    echo "::warning::poi.db pubblicato di $REGION_ID non scaricabile: controllo solo che il risultato non sia vuoto"
  fi
  rm -f "$WORKDIR/published-poi.db.xz" "$WORKDIR/published-poi.db"
fi
POI_PUBLISHED_COUNT="$PUBLISHED_POI_COUNT" ./gradlew -q :tools:data-pipeline:content:generatePoi --args="$POI_ARGS"
POI_COUNT="$(poi_count "$POI_DB")"
# Fama (sitelinks) e patrimonio (heritage) dei POI con un elemento Wikidata, con la cache di POI_WIKIDATA_CACHE se c'e'.
# Mai fatale: senza Wikidata i POI restano senza le due colonne.
python3 "$SCRIPT_DIR/poi_wikidata.py" "$POI_DB" "$POI_EXTRA_DB" \
  || echo "::warning::fama e patrimonio dei POI di $REGION_ID non scritti (errore sopra)"

# I pacchetti POI si pubblicano solo compressi con xz (<file>.xz): Spagna 110 MB -> 26 MB, contro i
# 44 MB di gzip. In `manifest.json` "file" descrive il database non compresso (nome, dimensione e sha256,
# che l'app ricontrolla dopo la decompressione) e "fileXz" il file da scaricare; tutti e due hanno
# l'URL del .xz. xz_entry (dizionario da 16 MiB, un solo thread) e' in lib.sh: stessa convenzione
# per guides.db (build-guides.sh).

# --- 5. Frammento di catalogo (poi.db e poi-extra.db generati qui + rd5 ri-ospitati + sorgente mappa)
POI_DB_URL="${ASSET_BASE_URL}/${REGION_ID}--${VERSION}--poi.db.xz"
POI_EXTRA_DB_URL="${ASSET_BASE_URL}/${REGION_ID}--${VERSION}--poi-extra.db.xz"
if [ "$POI_ONLY" = "true" ]; then
  # Il frammento incrementale scritto dalla sezione 2bis ha gia' mappa, rete stradale e le voci POI
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
      # Stessi POI con la regola nuova del confine: la voce pubblicata la registra, insieme a confine, relazione e
      # conteggio (assenti nelle voci piu' vecchie), altrimenti ogni run li rigenera.
      if [ "$key" = "poi" ]; then
        jq -c --argjson count "$POI_COUNT" --argjson border "$POI_BORDER" --argjson borderRelation "${BORDER_RELATION:-null}" \
          --argjson borderRule "$POI_BORDER_RULE_JSON" --argjson scope "$POI_SCOPE_JSON" \
          '.regions |= map(.poi.count = $count | .poi.border = $border | .poi.borderRelation = $borderRelation | .poi.borderRule = $borderRule
            | if $scope then .poi.scope = $scope else del(.poi.scope) end)' \
          "$MANIFEST_FRAGMENT" > "$WORKDIR/fragment.json"
        mv "$WORKDIR/fragment.json" "$MANIFEST_FRAGMENT"
      fi
      return 0
    fi
    jq -c --arg key "$key" --arg version "$VERSION" --arg name "$name" --arg url "$url" \
      --argjson size "$(wc -c < "$file" | tr -d ' ')" --arg hash "$hash" --argjson xz "$(xz_entry "$file" "$url")" --argjson count "$POI_COUNT" --argjson border "$POI_BORDER" --argjson borderRelation "${BORDER_RELATION:-null}" \
      --argjson borderRule "$POI_BORDER_RULE_JSON" --argjson scope "$POI_SCOPE_JSON" '
      .regions |= map(.[$key] = {version: $version, file: {name: $name, url: $url, sizeBytes: $size, sha256: $hash}, fileXz: $xz}
        + (if $key == "poi" then {count: $count, border: $border, borderRelation: $borderRelation, borderRule: $borderRule}
          + (if $scope then {scope: $scope} else {} end) else {} end))' \
      "$MANIFEST_FRAGMENT" > "$WORKDIR/fragment.json"
    mv "$WORKDIR/fragment.json" "$MANIFEST_FRAGMENT"
  }
  update_poi_entry poi "$POI_DB" poi.db "$POI_DB_URL"
  update_poi_entry poiExtra "$POI_EXTRA_DB" poi-extra.db "$POI_EXTRA_DB_URL"
  if [ ! -f "$POI_DB" ] && [ ! -f "$POI_EXTRA_DB" ]; then
    CHANGED_RD5=false
    for rd5 in "$OUTPUT_DIR"/*.rd5; do
      [ -e "$rd5" ] && CHANGED_RD5=true
    done
    # Non se la sezione 2bis ha dato una nuova versione alla mappa (MAP_DUE) o ha (ri)generato
    # la mappa d'insieme: il frammento pubblicato le perderebbe.
    if [ "$CHANGED_RD5" != "true" ] && [ "$MAP_DUE" != "true" ] && [ ! -f "$OUTPUT_DIR/preview.pmtiles.xz" ] \
      && [ "$(jq -c '.regions[0] | {poi, poiExtra}' "$MANIFEST_FRAGMENT")" = "$(printf '%s' "$PUBLISHED_REGION" | jq -c '{poi, poiExtra}')" ]; then
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
ROUTING_CAR_TSV="$WORKDIR/routing-car.tsv"
build_car_variant "$ROUTING_TSV" "$ROUTING_CAR_TSV"
POI_EXTRA_SPEC="null"
if [ -f "$POI_EXTRA_DB" ]; then
  POI_EXTRA_SPEC="$(jq -n -c --arg path "$(winpath "$POI_EXTRA_DB")" --arg url "$POI_EXTRA_DB_URL" '{path: $path, url: $url}')"
fi
# Mappa d'insieme: la mappa e' comunque appena (ri)estratta dalla build Protomaps corrente in questa
# rigenerazione completa, quindi si genera sempre (non solo quando cambia rispetto alla precedente,
# a differenza delle sezioni incrementali sopra). Solo "file" passa dalla spec (come poiDb, hash
# calcolato da generateManifest sul file locale); "fileXz" si aggiunge dopo coi valori gia' pronti
# di build_preview, senza ricomprimere (vedi PREVIEW_XZ_SIZE/PREVIEW_XZ_SHA256 piu' sotto).
PREVIEW_URL="${ASSET_BASE_URL}/${REGION_ID}--${VERSION}--preview.pmtiles.xz"
PREVIEW_SPEC="null"
PUBLISHED_PREVIEW="null"
if [ -n "$PMTILES_BIN" ] && build_preview "https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles"; then
  PREVIEW_SPEC="$(jq -n -c --arg path "$(winpath "$OUTPUT_DIR/preview.pmtiles")" --arg url "$PREVIEW_URL" \
    --argjson maxZoom "$PREVIEW_ZOOM_USED" '{path: $path, url: $url, maxZoom: $maxZoom}')"
elif [ -n "$PMTILES_BIN" ]; then
  # Protomaps non ha risposto: si tiene la mappa d'insieme pubblicata (il suo asset resta referenziato dal
  # `manifest.json`), o la regione esce senza se non ne aveva una.
  rm -f "$OUTPUT_DIR/preview.pmtiles" "$OUTPUT_DIR/preview.pmtiles.xz"
  if [ -n "${PUBLISHED_MANIFEST:-}" ] && [ -s "$PUBLISHED_MANIFEST" ]; then
    PUBLISHED_PREVIEW="$(jq -c --arg id "$REGION_ID" '[(.regions // [])[] | select(.regionId == $id)][0].preview // null' "$PUBLISHED_MANIFEST" 2>/dev/null || echo null)"
  fi
  echo "::warning::mappa d'insieme di $REGION_ID non estratta (build.protomaps.com): $([ "$PUBLISHED_PREVIEW" = null ] && echo "regione senza mappa d'insieme" || echo 'resta quella pubblicata')"
else
  echo "::warning::go-pmtiles non trovato: $REGION_ID senza mappa d'insieme"
fi
# Costruita con jq (--arg), non con un heredoc: un displayName con virgolette o backslash
# produrrebbe altrimenti un JSON non valido.
SPEC_FILE="$WORKDIR/spec.json"
jq -n \
  --arg regionId "$REGION_ID" --arg displayName "$DISPLAY_NAME" --arg version "$VERSION" \
  --arg poiPath "$(winpath "$POI_DB")" --arg poiUrl "$POI_DB_URL" \
  --argjson poiExtra "$POI_EXTRA_SPEC" --argjson preview "$PREVIEW_SPEC" \
  --argjson routingFiles "$(jq -R -s -c "$RD5_TSV_TO_JSON" "$ROUTING_TSV")" \
  --arg sourceUrl "https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles" \
  --argjson minLon "$MIN_LON" --argjson minLat "$MIN_LAT" --argjson maxLon "$MAX_LON" --argjson maxLat "$MAX_LAT" \
  --argjson minZoom "$MAP_MIN_ZOOM" --argjson maxZoom "$MAP_MAX_ZOOM" '
  {regionId: $regionId, displayName: $displayName, version: $version,
   poiDb: {path: $poiPath, url: $poiUrl}}
  + (if $poiExtra then {poiExtraDb: $poiExtra} else {} end)
  + (if $preview then {previewPmtiles: $preview} else {} end)
  + {routingFiles: $routingFiles,
     mapSource: {sourceUrl: $sourceUrl, minLon: $minLon, minLat: $minLat, maxLon: $maxLon, maxLat: $maxLat,
                 minZoom: $minZoom, maxZoom: $maxZoom}}' > "$SPEC_FILE"

MANIFEST_FRAGMENT="$OUTPUT_DIR/manifest-fragment.json"
echo "-- genero il frammento di catalogo..."
./gradlew -q :tools:data-pipeline:content:generateManifest \
  --args="\"$(winpath "$SPEC_FILE")\" \"$(winpath "$MANIFEST_FRAGMENT")\""
# POI compressi (voce fileXz, vedi xz_entry).
POI_XZ="$(xz_entry "$POI_DB" "$POI_DB_URL")"
POI_EXTRA_XZ="null"
[ -f "$POI_EXTRA_DB" ] && POI_EXTRA_XZ="$(xz_entry "$POI_EXTRA_DB" "$POI_EXTRA_DB_URL")"
# border: POI tagliati su un confine OSM; borderRelation: la relazione (nazione o suddivisione); borderRule: la regola
# del taglio (POI_BORDER_RULE); scope: le regioni sorelle (POI_SCOPE), assente senza. Il controllo del conteggio sopra
# e la rigenerazione anticipata confrontano solo pacchetti tagliati sulla stessa relazione, con le stesse sorelle.
jq -c --argjson xz "$POI_XZ" --argjson extraXz "$POI_EXTRA_XZ" --argjson count "$POI_COUNT" --argjson border "$POI_BORDER" \
  --argjson borderRelation "${BORDER_RELATION:-null}" --argjson borderRule "$POI_BORDER_RULE_JSON" --argjson scope "$POI_SCOPE_JSON" \
  '.regions |= map(.poi.fileXz = $xz | .poi.count = $count | .poi.border = $border | .poi.borderRelation = $borderRelation | .poi.borderRule = $borderRule
    | (if $scope then .poi.scope = $scope else del(.poi.scope) end) | if .poiExtra and $extraXz then .poiExtra.fileXz = $extraXz else . end)' \
  "$MANIFEST_FRAGMENT" > "$WORKDIR/fragment.json"
mv "$WORKDIR/fragment.json" "$MANIFEST_FRAGMENT"
# Mappa d'insieme compressa (voce fileXz): dimensione/hash gia' calcolati da build_preview, niente
# ricompressione (a differenza di xz_entry sopra, che lavora su un file non ancora compresso).
if [ -f "$OUTPUT_DIR/preview.pmtiles" ]; then
  PREVIEW_XZ_JSON="$(jq -n -c --arg url "$PREVIEW_URL" --argjson size "$PREVIEW_XZ_SIZE" --arg hash "$PREVIEW_XZ_SHA256" \
    '{name: "preview.pmtiles.xz", url: $url, sizeBytes: $size, sha256: $hash}')"
  jq -c --argjson xz "$PREVIEW_XZ_JSON" '.regions |= map(if .preview then .preview.fileXz = $xz else . end)' \
    "$MANIFEST_FRAGMENT" > "$WORKDIR/fragment.json"
  mv "$WORKDIR/fragment.json" "$MANIFEST_FRAGMENT"
elif [ "$PUBLISHED_PREVIEW" != "null" ]; then
  jq -c --argjson preview "$PUBLISHED_PREVIEW" '.regions |= map(.preview = $preview)' "$MANIFEST_FRAGMENT" > "$WORKDIR/fragment.json"
  mv "$WORKDIR/fragment.json" "$MANIFEST_FRAGMENT"
fi
# Variante "solo auto" della rete stradale: stessa versione e stesse tile di "routing".
jq -c --arg version "$VERSION" --argjson files "$(jq -R -s -c "$RD5_TSV_TO_JSON" "$ROUTING_CAR_TSV")" \
  '.regions |= map(.routingCar = {version: $version, files: $files})' "$MANIFEST_FRAGMENT" > "$WORKDIR/fragment.json"
mv "$WORKDIR/fragment.json" "$MANIFEST_FRAGMENT"
# Impronta delle tile della mappa appena pubblicata (riferimento per la prossima versione).
if [ -z "${MAP_FINGERPRINT:-}" ]; then
  MAP_FINGERPRINT="$(./gradlew -q :tools:data-pipeline:content:mapFingerprint \
    --args="https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles $MIN_LON $MIN_LAT $MAX_LON $MAX_LAT $MAP_FINGERPRINT_MIN_ZOOM $MAP_MAX_ZOOM" \
    | sed -n 's/.*impronta \([0-9a-f]\{64\}\).*/\1/p' | tail -1 || true)"
fi
if [ -z "$MAP_FINGERPRINT" ]; then
  echo "::warning::$REGION_ID: impronta delle tile non calcolata (mapFingerprint), manifest.json esce senza map.fingerprint" >&2
fi
if [ -n "$MAP_FINGERPRINT" ]; then
  jq -c --arg fp "$MAP_FINGERPRINT" '.regions |= map(.map.fingerprint = $fp)' "$MANIFEST_FRAGMENT" > "$WORKDIR/fragment.json"
  mv "$WORKDIR/fragment.json" "$MANIFEST_FRAGMENT"
fi

echo "== [$REGION_ID] fatto: $POI_DB, $MANIFEST_FRAGMENT =="
