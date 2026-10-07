#!/usr/bin/env bash
# Helper condivisi dagli script della pipeline publish-region (build-region.sh, build-guides.sh,
# build-address-cell.sh, assemble-site.sh, build-all-regions.sh, generate-weekly-schedule.sh). Solo
# funzioni, nessun effetto collaterale:
# sourcing sicuro da qualunque script con "set -euo pipefail" gia' attivo.
# shellcheck shell=bash

# gradlew invoca java.exe nativo di Windows: gli argomenti --args vogliono path Windows reali (con
# lettera di unita'), non il path POSIX virtuale di git-bash/MSYS (es. /tmp/xxx), altrimenti
# java.exe non li trova (visto: FileNotFoundException su un path tipo "\tmp\xxx\dump.txt", senza
# lettera di unita'). Su Linux (CI) cygpath non esiste e non serve: bash e java concordano gia'
# sullo stesso path POSIX.
winpath() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

# xz_entry <file> <url>: comprime <file> in <file>.xz (dizionario da 16 MiB: il telefono decomprime
# con ~17 MB di memoria, il preset 9e ne vorrebbe 65, perdendo meno del 2%; un solo thread, cosi' lo
# stesso file da' sempre lo stesso .xz) e stampa su stdout la voce "fileXz" del manifest (nome,
# url, dimensione e sha256 del .xz). Usata da build-region.sh (poi.db, poi-extra.db, preview.pmtiles)
# e build-guides.sh (guides.db): stesso schema ovunque, "file"
# nel manifest descrive il file non compresso (nome, dimensione e sha256 che l'app ricontrolla dopo
# la decompressione) e "fileXz" il file da scaricare, con l'URL del .xz.
xz_entry() {
  local file="$1" url="$2"
  xz -T1 --lzma2=preset=9e,dict=16MiB -c "$file" > "$file.xz"
  jq -n -c --arg name "$(basename "$file").xz" --arg url "$url" --argjson size "$(wc -c < "$file.xz" | tr -d ' ')" \
    --arg hash "$(sha256sum < "$file.xz" | awk '{print $1}')" '{name: $name, url: $url, sizeBytes: $size, sha256: $hash}'
}

# Griglia 5x5 gradi usata sia per i segmenti .rd5 di BRouter che per le query Overpass (vedi
# build-region.sh) sia per il peso delle regioni nello scheduling settimanale/sharding
# (generate-weekly-schedule.sh, publish-regions.yml): DEVE restare la stessa griglia nei tre punti,
# altrimenti i pesi calcolati non corrisponderebbero piu' alle tile vere generate da build-region.sh.
floor5() {
  awk -v v="$1" 'BEGIN { x = v / 5; ix = int(x); if (x < ix) ix -= 1; printf "%d", ix * 5 }'
}
tile_name() {
  local lon="$1" lat="$2"
  local ew="E" ns="N" alon="$lon" alat="$lat"
  if [ "$lon" -lt 0 ]; then ew="W"; alon=$(( -lon )); fi
  if [ "$lat" -lt 0 ]; then ns="S"; alat=$(( -lat )); fi
  echo "${ew}${alon}_${ns}${alat}"
}

# Data (YYYYMMDD) della build Protomaps whole-planet piu' recente disponibile, stessa per l'intera
# giornata: chi genera piu' regioni nella stessa run (build-all-regions.sh, publish-regions.yml)
# la risolve una volta sola e la passa alle chiamate di build-region.sh via PROTOMAPS_DATE_OVERRIDE,
# invece di rifare fino a 4 richieste HEAD identiche per ogni singola regione.
resolve_protomaps_date() {
  for days_ago in 0 1 2 3; do
    local d
    d="$(date -u -d "-${days_ago} day" +%Y%m%d)"
    local code
    code="$(curl -s --max-time 30 --retry 3 -o /dev/null -w '%{http_code}' -I "https://build.protomaps.com/${d}.pmtiles")"
    if [ "$code" = "200" ]; then
      echo "$d"
      return 0
    fi
  done
  echo "ERRORE: nessuna build Protomaps trovata negli ultimi giorni" >&2
  return 1
}

# Riprova <cmd...> fino a <attempts> volte con attesa crescente (30s, 2min, 8min...), rimuovendo
# <cleanupFile> (se non vuoto) tra un tentativo e l'altro: build.protomaps.com a volte chiude la
# connessione a meta' (filippine al 96%, finlandia al 70%, run del 2026-09-24). <label> solo per i
# messaggi. Usata da build-address-cell.sh (bbox di una cella della griglia dei civici).
with_retries() {
  local label="$1" attempts="$2" cleanupFile="$3" attempt wait
  shift 3
  for attempt in $(seq 1 "$attempts"); do
    if "$@"; then
      return 0
    fi
    if [ "$attempt" -lt "$attempts" ]; then
      wait=$(( 30 * 4 ** (attempt - 1) ))
      echo "-- $label: tentativo $attempt/$attempts fallito, riprovo tra ${wait}s" >&2
      [ -z "$cleanupFile" ] || rm -f "$cleanupFile"
      sleep "$wait"
    fi
  done
  # Anche dopo l'ultimo fallimento: un file parziale non deve restare in giro.
  [ -z "$cleanupFile" ] || rm -f "$cleanupFile"
  return 1
}

# Civici da Overpass per un bbox (fonte di riserva quando l'estrazione Protomaps e' troppo
# grande, vedi build-address-cell.sh): scrive "lat<TAB>lon<TAB>numero<TAB>via<TAB>citta'" in
# <outFile>, solo se il conteggio e' <= <maxCount>. <areaQuery> e' la clausola Overpass della
# selezione (es. "nwr[\"addr:housenumber\"](minLat,minLon,maxLat,maxLon)"). Scrive l'esito in
# <noteVar> (nameref): vuoto se riuscito, altrimenti il motivo per il riepilogo del chiamante.
fetch_overpass_address_points() {
  local label="$1" areaQuery="$2" maxCount="$3" outFile="$4" noteVar="$5"
  local -n note_ref="$noteVar"
  local workdir count
  workdir="$(mktemp -d)"
  note_ref=""
  if ! overpass_json "[out:json][timeout:900];$areaQuery;out count;" "$workdir/count.json"; then
    note_ref=", Overpass non disponibile"
    rm -rf "$workdir"
    return 1
  fi
  count="$(jq -r '.elements[0].tags.total // 0' "$workdir/count.json")"
  echo "-- $label: $count civici su Overpass"
  if [ "$count" -eq 0 ] || [ "$count" -gt "$maxCount" ]; then
    note_ref=", $count civici su Overpass (max $maxCount)"
    rm -rf "$workdir"
    return 1
  fi
  if ! overpass_json "[out:json][timeout:900];$areaQuery;out tags center qt;" "$workdir/addresses.json"; then
    note_ref=", Overpass non disponibile"
    rm -rf "$workdir"
    return 1
  fi
  # Via da addr:street (o addr:place, per i paesi senza strade con nome) e citta' da addr:city; tab e
  # a capo dentro i valori diventano spazi, per non rompere le colonne.
  jq -r 'def clean: if . == null then null else gsub("[\\t\\r\\n]+"; " ") end;
    .elements[] | [(.lat // .center.lat), (.lon // .center.lon), .tags["addr:housenumber"],
      (.tags["addr:street"] // .tags["addr:place"] | clean), (.tags["addr:city"] | clean)] | @tsv' \
    "$workdir/addresses.json" > "$outFile"
  rm -rf "$workdir"
}

# Riquadro (minLon minLat maxLon maxLat, separati da spazio) di una cella z/x/y del quadtree Web
# Mercator (stessa curva delle tile, z<=12): standard "slippy map tile
# bounds", usato da build-address-cell.sh per l'estrazione Protomaps/Overpass e la query Overture.
cell_bbox() {
  local z="$1" x="$2" y="$3"
  # awk non ha sinh()/atan() a un argomento in modo portabile: implementati qui sotto (atan2 si',
  # e' nel POSIX awk) senza dipendere da estensioni gawk.
  awk -v z="$z" -v x="$x" -v y="$y" 'BEGIN {
    pi = atan2(0, -1)
    n = 2 ^ z
    minLon = x / n * 360 - 180
    maxLon = (x + 1) / n * 360 - 180
    maxLat = tile_lat(y, n, pi)
    minLat = tile_lat(y + 1, n, pi)
    printf "%.10f %.10f %.10f %.10f\n", minLon, minLat, maxLon, maxLat
  }
  function tile_lat(ty, n, pi,    yFrac, sinhArg, ex, eNegX) {
    yFrac = pi * (1 - 2 * ty / n)
    ex = exp(yFrac); eNegX = exp(-yFrac)
    sinhArg = (ex - eNegX) / 2
    return atan2(sinhArg, 1) * 180 / pi
  }'
}

# fetch_published_manifest <urlOPath> <out>: copia in <out> il manifest pubblicato e stampa il codice
# HTTP (200 = trovato, 404 = nessun manifest pubblicato, altro = non scaricabile). <urlOPath> puo'
# essere un file locale gia' scaricato: publish-regions.yml scarica il manifest una volta per shard
# e lo passa a build-region.sh e build-cities.sh, invece di due download per
# regione (centinaia per run).
fetch_published_manifest() {
  if [ -f "$1" ]; then
    cp "$1" "$2" && echo 200
    return 0
  fi
  curl -sSf --max-time 120 --speed-limit 1000 --speed-time 30 --retry 5 --retry-all-errors -o "$2" -w '%{http_code}' "$1" 2>/dev/null || true
}

# User-Agent descrittivo della pipeline: richiesto dalla policy di Wikimedia; senza, overpass-api.de
# risponde 406 (visto il 2026-09-25).
PIPELINE_USER_AGENT="PocketTravelDataPipeline/1.0 (https://github.com/miracle091/pocket-travel)"

# Scarica il wikitext grezzo della pagina Wikivoyage di una regione in <outFile> e stampa l'URL
# della pagina usata (per il campo sourceUrl delle sezioni). Preferisce l'edizione italiana:
# Wikivoyage IT e' scritto da editor italiani, non una traduzione automatica — piu' "tradotto" e
# "leggibile" di qualunque pipeline di traduzione aggiunta qui, senza dipendenze nuove ne' servizi
# da ospitare. Il titolo IT non si puo' indovinare dal titolo EN
# (es. "Giappone" per "Japan", "Palau (stato)" per "Palau"): si risolve dai langlinks interwiki
# della pagina EN via l'API MediaWiki, gia' tenuti allineati da Wikivoyage stesso — evita di
# mantenere a mano una seconda colonna di titoli IT in regions.sh, che si disallineerebbe
# silenziosamente ad ogni rinomina di pagina. La risposta si parsa con sed per non rendere jq
# obbligatorio: utf8=1 fa arrivare il titolo in UTF-8 invece che con gli escape \uXXXX (con gli
# escape "Faer Oer" diventerebbe un titolo non valido), e --data-urlencode lo codifica per l'URL.
# Nessun langlink IT (o pagina IT vuota): fallback sull'originale inglese.
# -f: una risposta HTTP di errore (400, 403, 429) non deve finire in <outFile> come se fosse la
# pagina, altrimenti la regione esce senza sezioni invece di tenere la guida gia' pubblicata.
# Lo User-Agent descrittivo e' richiesto dalla policy di Wikimedia.
# Ritorna 1 (e nessun URL) se anche la pagina EN risulta vuota (titolo errato o errore di rete).
wikimedia_curl() { curl -sSf --max-time 120 --speed-limit 1000 --speed-time 30 --retry 3 --retry-delay 5 -A "$PIPELINE_USER_AGENT" "$@"; }

# Titolo IT Wikivoyage di una pagina (dal suo titolo EN, via langlink interwiki) o vuoto se non
# esiste una pagina IT - stesso approccio di fetch_wikivoyage_dump piu' sotto, estratto perche'
# build-cities-dump.sh lo riusa senza scaricare anche il testo della pagina (gli serve solo il
# titolo, per abbinare le pagine {{QuickbarCity}} del dump alla regione).
resolve_it_wikivoyage_title() {
  local wikiTitle="$1" langlinks
  # Titolo codificato (--data-urlencode): con lettere accentate in chiaro ("Île-de-France")
  # Wikimedia risponde 400.
  langlinks="$(wikimedia_curl -G "https://en.wikivoyage.org/w/api.php" --data-urlencode "titles=${wikiTitle}" \
    -d action=query -d prop=langlinks -d lllang=it -d format=json -d utf8=1 -d redirects=1 2>/dev/null || true)"
  printf '%s' "$langlinks" | sed -n 's/.*"lang":"it","\*":"\([^"]*\)".*/\1/p'
}

fetch_wikivoyage_dump() {
  local wikiTitle="$1" outFile="$2"
  local itTitle itTitleUrl
  itTitle="$(resolve_it_wikivoyage_title "$wikiTitle")"
  : > "$outFile"
  if [ -n "$itTitle" ]; then
    itTitleUrl="${itTitle// /_}"
    wikimedia_curl -G "https://it.wikivoyage.org/w/index.php" --data-urlencode "title=${itTitleUrl}" -d action=raw -o "$outFile" 2>/dev/null || : > "$outFile"
    if [ -s "$outFile" ]; then
      echo "https://it.wikivoyage.org/wiki/${itTitleUrl}"
      return 0
    fi
  fi
  fetch_wikivoyage_en_dump "$wikiTitle" "$outFile"
}

# Dump di Wikivoyage dai MediaWiki Content File Exports di Wikimedia (contenuto attuale, uno al mese il 1°):
# https://dumps.wikimedia.org/other/mediawiki_content_current/<wiki>/<AAAA-MM-GG>/xml/bzip2/, con una o piu' parti
# <wiki>-<AAAA-MM-GG>-p<da>p<a>.xml.bz2 e i loro sha256 in SHA256SUMS, scritto per ultimo a dump finito. Usati da
# build-cities-dump.sh (guide delle citta') per trovare con un solo passaggio le pagine citta' di tutte le regioni,
# invece di migliaia di richieste API.
WIKIMEDIA_EXPORTS_URL="https://dumps.wikimedia.org/other/mediawiki_content_current"

# latest_wikimedia_export <wiki>: data (AAAA-MM-GG) del dump completo piu' recente di <wiki>, l'ultima cartella con
# un SHA256SUMS non vuoto: il 1° del mese quella nuova puo' essere ancora in corso, e si usa quella del mese prima.
# Non _SUCCESS: il dump del 2026-10-01 non lo ha, pur completo.
latest_wikimedia_export() {
  local wiki="$1" date sums
  for date in $(wikimedia_curl "$WIKIMEDIA_EXPORTS_URL/$wiki/" 2>/dev/null | grep -oE 'href="[0-9]{4}-[0-9]{2}-[0-9]{2}/"' \
      | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' | sort -r | head -3); do
    # prima in una variabile: "curl | grep -q" con pipefail fallisce se grep esce prima che curl finisca di scrivere
    sums="$(wikimedia_curl "$WIKIMEDIA_EXPORTS_URL/$wiki/$date/xml/bzip2/SHA256SUMS" 2>/dev/null)" || continue
    if grep -qE "^[0-9a-f]{64}  $wiki-$date-p[0-9]+p[0-9]+[.]xml[.]bz2$" <<< "$sums"; then
      echo "$date"
      return 0
    fi
  done
  return 1
}

# normalize_dump_date <data>: la data di un dump (AAAA-MM-GG) da MM-AAAA o AAAA-MM (separatori -, / o .; il giorno e'
# sempre il 1°), oppure da AAAA-MM-GG per sperimentare. Come normalize_date in wiki_dump.py. 1 se il formato, il mese o
# il giorno non valgono.
normalize_dump_date() {
  local d="$1" y m day=01
  if [[ "$d" =~ ^([0-9]{1,2})[-/.]([0-9]{4})$ ]]; then m="${BASH_REMATCH[1]}"; y="${BASH_REMATCH[2]}"
  elif [[ "$d" =~ ^([0-9]{4})[-/.]([0-9]{1,2})$ ]]; then y="${BASH_REMATCH[1]}"; m="${BASH_REMATCH[2]}"
  elif [[ "$d" =~ ^([0-9]{4})-([0-9]{2})-([0-9]{2})$ ]]; then y="${BASH_REMATCH[1]}"; m="${BASH_REMATCH[2]}"; day="${BASH_REMATCH[3]}"
  else m=0; fi
  m="$(printf '%02d' "$((10#$m))")"
  if [ "$m" = 00 ] || ! date -d "$y-$m-$day" >/dev/null 2>&1; then
    echo "data non valida: $d (formati: MM-AAAA o AAAA-MM, separati da - / o ., oppure AAAA-MM-GG)" >&2
    return 1
  fi
  echo "$y-$m-$day"
}

# wikivoyage_dumps_key: chiave della cache di Actions dei dump di Wikivoyage IT ed EN (publish-regions.yml), dagli
# SHA256SUMS dei dump correnti: cambia solo quando esce un dump nuovo.
wikivoyage_dumps_key() {
  local key="wikivoyage-dumps" lang date sums
  for lang in it en; do
    date="$(latest_wikimedia_export "${lang}wikivoyage")" || return 1
    sums="$(wikimedia_curl "$WIKIMEDIA_EXPORTS_URL/${lang}wikivoyage/$date/xml/bzip2/SHA256SUMS")" || return 1
    key="$key-$lang$(printf '%s' "$sums" | sha256sum | cut -c1-12)"
  done
  echo "$key"
}

# fetch_wikivoyage_dump_parts <it|en> <outDir>: scarica in <outDir> le parti dell'ultimo dump completo di Wikivoyage
# (IT ~57 MB, EN ~190 MB compressi) e ne verifica lo sha256, con fino a 3 tentativi per parte (un download cosi' grande
# puo' interrompersi a meta'). Cache facoltativa WIKIVOYAGE_DUMP_CACHE (la riempie la cache di Actions nel job
# "build"): una parte gia' scaricata con lo sha256 giusto non si riscarica. Senza tutte le parti verificate, 1 e
# nessuna parte in <outDir>.
fetch_wikivoyage_dump_parts() {
  local lang="$1" outDir="$2" wiki="${1}wikivoyage" date sums sha file cached attempt ok
  mkdir -p "$outDir"
  if ! date="$(latest_wikimedia_export "$wiki")"; then
    echo "-- dump Wikivoyage ${lang}: nessun dump completo" >&2
    return 1
  fi
  sums="$(wikimedia_curl "$WIKIMEDIA_EXPORTS_URL/$wiki/$date/xml/bzip2/SHA256SUMS" 2>/dev/null)" || return 1
  [ -n "$sums" ] || return 1
  local part_re="^$wiki-$date-p[0-9]+p[0-9]+[.]xml[.]bz2$"
  while read -r sha file; do
    [ -n "$file" ] || continue
    # i nomi vengono da un file remoto: solo parti di questo dump, mai un percorso (finirebbe fuori da <outDir>)
    [[ "$sha" =~ ^[0-9a-f]{64}$ && "$file" =~ $part_re ]] || { rm -f "$outDir"/*.xml.bz2; return 1; }
    cached="${WIKIVOYAGE_DUMP_CACHE:+$WIKIVOYAGE_DUMP_CACHE/$file}"
    if [ -n "$cached" ] && printf '%s  %s\n' "$sha" "$cached" | sha256sum -c - >/dev/null 2>&1; then
      echo "-- dump Wikivoyage ${lang}: $file dalla cache" >&2
      # chiamata da "if !": set -e qui non vale, e una copia fallita (disco pieno) lascerebbe una parte mancante
      cp "$cached" "$outDir/$file" || { rm -f "$outDir"/*.xml.bz2; return 1; }
      continue
    fi
    ok=""
    for attempt in 1 2 3; do
      if wikimedia_curl --max-time 1800 -o "$outDir/$file" "$WIKIMEDIA_EXPORTS_URL/$wiki/$date/xml/bzip2/$file" 2>/dev/null \
        && printf '%s  %s\n' "$sha" "$outDir/$file" | sha256sum -c - >/dev/null 2>&1; then
        ok=1
        break
      fi
      if [ "$attempt" -lt 3 ]; then
        echo "-- dump Wikivoyage ${lang}: $file, tentativo $attempt/3 fallito (download o sha256), riprovo tra $((attempt * 30))s" >&2
        sleep $((attempt * 30))
      fi
    done
    if [ -z "$ok" ]; then
      rm -f "$outDir"/*.xml.bz2
      return 1
    fi
    if [ -n "$cached" ]; then mkdir -p "$WIKIVOYAGE_DUMP_CACHE" && cp "$outDir/$file" "$cached"; fi
  done <<< "$sums"
  # le parti dei dump vecchi non servono piu': fuori dalla cache, che altrimenti crescerebbe a ogni dump
  if [ -n "${WIKIVOYAGE_DUMP_CACHE:-}" ] && [ -d "$WIKIVOYAGE_DUMP_CACHE" ]; then
    for cached in "$WIKIVOYAGE_DUMP_CACHE/$wiki"-*.xml.bz2; do
      [ -e "$cached" ] || continue
      grep -qF "  $(basename "$cached")" <<< "$sums" || rm -f "$cached"
    done
  fi
  echo "-- dump Wikivoyage ${lang}: dump del $date" >&2
}

# Solo la pagina inglese: stesso contratto di fetch_wikivoyage_dump (URL su stdout, 1 se vuota).
# build-guides.sh la usa anche come ripiego per le pagine italiane con i soli titoli.
fetch_wikivoyage_en_dump() {
  local wikiTitle="$1" outFile="$2"
  wikimedia_curl -G "https://en.wikivoyage.org/w/index.php" --data-urlencode "title=${wikiTitle}" -d action=raw -o "$outFile" 2>/dev/null || : > "$outFile"
  if [ -s "$outFile" ]; then
    echo "https://en.wikivoyage.org/wiki/${wikiTitle}"
    return 0
  fi
  return 1
}

# Istanze gratuite e senza chiave con copertura mondiale (wiki OSM, "Overpass API - Instances
# with global data coverage", verificato il 2026-09-24): prima quelle senza limiti dichiarati
# (VK Maps, private.coffee), poi le due FOSSGIS, che chiedono meno di 10.000 richieste al giorno.
# overpass.openstreetmap.fr escluso: dal 2026-09 risponde solo a usi autorizzati. La disponibilita'
# cambia di ora in ora (stesso giorno: una istanza in timeout, un'altra con 504), quindi
# ATTEMPTS = numero di istanze, cosi' ogni chunk le prova tutte.
OVERPASS_ENDPOINTS=(
  "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
  "https://overpass.private.coffee/api/interpreter"
  "https://overpass-api.de/api/interpreter"
  "https://z.overpass-api.de/api/interpreter"
)

# Query Overpass con risposta JSON ([out:json]), scritta in <outFile>: prova i mirror uno dopo l'altro
# finche' uno risponde con un JSON valido e senza "remark" (un timeout della query arriva come
# risposta valida con il solo remark). Lo User-Agent descrittivo serve: overpass-api.de risponde
# 406 senza (visto il 2026-09-25). Ritorna 1 se nessun mirror risponde.
overpass_json() {
  local query="$1" outFile="$2" endpoint
  for endpoint in "${OVERPASS_ENDPOINTS[@]}"; do
    if curl -sS --max-time 950 -A "$PIPELINE_USER_AGENT" \
      "$endpoint" --data-urlencode "data=$query" -o "$outFile" &&
      jq -e '(.elements | type) == "array" and (.remark == null)' "$outFile" >/dev/null 2>&1; then
      return 0
    fi
    echo "-- Overpass $endpoint non disponibile o in timeout, provo il successivo" >&2
  done
  return 1
}

# pmtiles_log: filtro dell'output di "pmtiles extract" per il log della CI. go-pmtiles disegna barre di
# avanzamento con \r e righe vuote (migliaia di righe per una run delle celle dei civici): restano solo
# le righe utili (dimensioni, tile, tempo totale, errori). Si usa in coda: cmd 2>&1 | pmtiles_log (con
# pipefail l'esito resta quello di cmd).
pmtiles_log() {
  tr '\r' '\n' | awk 'NF && !/fetching chunks|^[[:space:]]*[0-9]+%/'
}

# translate_overlay <guides|cities> <db da arricchire> <db nell'altra lingua> <overlay.jsonl> <en|it> [maxSecondi]:
# sezioni povere tradotte dall'altra lingua (translate_guides.py, <en|it> e' la lingua d'origine), scritte in <overlay.jsonl>
# per generateGuides/generateCities --translated. Ritorna 1, senza scrivere nulla, se la traduzione non e' attiva (senza la
# cache TRANSLATE_CACHE_DIR e il modello di quella direzione, TRANSLATE_MODEL_EN_IT / TRANSLATE_MODEL_IT_EN, preparati dal
# passo setup-translation del workflow) o non riesce: il chiamante tiene allora la guida com'e'. TRANSLATE_PYTHON e' il
# python con ctranslate2; TRANSLATE_DEADLINE (secondi dal 1970) e' un tetto comune a piu' chiamate.
translate_overlay() {
  local kind="$1" own="$2" source="$3" overlay="$4" from="$5" maxSeconds="${6:-${TRANSLATE_MAX_SECONDS:-3600}}" model
  case "$from" in
    en) model="${TRANSLATE_MODEL_EN_IT:-}" ;;
    it) model="${TRANSLATE_MODEL_IT_EN:-}" ;;
    *) return 1 ;;
  esac
  [ -n "${TRANSLATE_CACHE_DIR:-}" ] && [ -d "$model" ] && [ -s "$own" ] && [ -s "$source" ] || return 1
  rm -f "$overlay"
  if ! "${TRANSLATE_PYTHON:-python3}" "$(dirname "${BASH_SOURCE[0]}")/translate_guides.py" "$kind" \
    "$(winpath "$own")" "$(winpath "$source")" "$(winpath "$overlay")" --from "$from" \
    --cache-dir "$(winpath "$TRANSLATE_CACHE_DIR")" --model-dir "$(winpath "$model")" --max-seconds "$maxSeconds" \
    ${TRANSLATE_DEADLINE:+--deadline "$TRANSLATE_DEADLINE"}; then
    echo "::warning::traduzione $from ($kind) non riuscita, resta il contenuto originale" >&2
    rm -f "$overlay"
    return 1
  fi
  [ -s "$overlay" ]
}
