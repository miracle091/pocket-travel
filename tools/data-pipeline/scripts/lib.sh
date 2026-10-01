#!/usr/bin/env bash
# Helper condivisi dagli script della pipeline publish-region (build-region.sh, build-guides.sh,
# build-address-cell.sh, assemble-site.sh, build-pilot-regions.sh, generate-weekly-schedule.sh). Solo
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
# giornata: chi genera piu' regioni nella stessa run (build-pilot-regions.sh, publish-regions.yml)
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
# "leggibile" di qualunque pipeline di traduzione aggiunta qui, senza dipendenze nuove (vedi "no
# hosting infra" nella memoria di progetto). Il titolo IT non si puo' indovinare dal titolo EN
# (es. "Giappone" per "Japan", "Palau (stato)" per "Palau"): si risolve dai langlinks interwiki
# della pagina EN via l'API MediaWiki, gia' tenuti allineati da Wikivoyage stesso — evita di
# mantenere a mano una seconda colonna di titoli IT in pilot-regions.sh, che si disallineerebbe
# silenziosamente ad ogni rinomina di pagina. La risposta si parsa con sed per non rendere jq
# obbligatorio: utf8=1 fa arrivare il titolo in UTF-8 invece che con gli escape \uXXXX (con gli
# escape "Faer Oer" diventava un titolo non valido), e --data-urlencode lo codifica per l'URL.
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

# Scarica l'ultimo dump completo di Wikivoyage IT (pages-articles, ~50 MB compressi) in <outFile> e
# ne verifica lo sha1 dal file sha1sums pubblicato accanto, con fino a 3 tentativi (stesso motivo
# del ritentativo su build.protomaps.com in build-address-cell.sh: un download cosi' grande puo'
# interrompersi a meta'). Usato da build-cities-dump.sh (guide
# delle citta') per trovare con un solo passaggio le pagine {{QuickbarCity}} di tutte le regioni,
# invece di migliaia di richieste API. "latest" (non una data precisa): dumps.wikimedia.org
# mantiene un alias sempre aggiornato accanto alla cartella datata, evitando di dover risolvere la
# data della corsa piu' recente come fa resolve_protomaps_date per le build Protomaps.
WIKIVOYAGE_IT_DUMP_NAME="itwikivoyage-latest-pages-articles.xml.bz2"
# Dump di Wikivoyage EN (~130 MB compressi): citta' in inglese (build-cities-dump.sh ... en).
WIKIVOYAGE_EN_DUMP_NAME="enwikivoyage-latest-pages-articles.xml.bz2"
fetch_wikivoyage_it_dump() { fetch_wikivoyage_lang_dump it "$1"; }
fetch_wikivoyage_en_full_dump() { fetch_wikivoyage_lang_dump en "$1"; }
fetch_wikivoyage_lang_dump() {
  local lang="$1" outFile="$2" attempt sha1File sha1
  local baseUrl="https://dumps.wikimedia.org/${lang}wikivoyage/latest"
  local dumpName="${lang}wikivoyage-latest-pages-articles.xml.bz2"
  local sha1Url="$baseUrl/${lang}wikivoyage-latest-sha1sums.txt"
  sha1File="$(mktemp)"
  # A inizio mese "latest" punta gia' al dump in corso, senza pages-articles (EN il 2026-10-01: sha1sums
  # di 740 byte): si usa la cartella datata piu' recente che lo ha, stesso file che "latest" darebbe
  # a dump finito. Il nome nella cache resta quello di "latest": lo sha1 decide comunque.
  if ! { wikimedia_curl -o "$sha1File" "$sha1Url" 2>/dev/null && grep -qE "^[0-9a-f]+  ${lang}wikivoyage-[0-9]+-pages-articles[.]xml[.]bz2$" "$sha1File"; }; then
    local date
    for date in $(wikimedia_curl "https://dumps.wikimedia.org/${lang}wikivoyage/" 2>/dev/null | grep -oE 'href="[0-9]{8}/"' | grep -oE '[0-9]{8}' | sort -r | head -3); do
      if wikimedia_curl -o "$sha1File" "https://dumps.wikimedia.org/${lang}wikivoyage/$date/${lang}wikivoyage-$date-sha1sums.txt" 2>/dev/null \
        && grep -qE "  ${lang}wikivoyage-$date-pages-articles[.]xml[.]bz2$" "$sha1File"; then
        echo "-- dump Wikivoyage ${lang}: latest incompleto, uso quello del $date" >&2
        baseUrl="https://dumps.wikimedia.org/${lang}wikivoyage/$date"
        sha1Url="$baseUrl/${lang}wikivoyage-$date-sha1sums.txt"
        local remoteName="${lang}wikivoyage-$date-pages-articles.xml.bz2"
        break
      fi
    done
  fi
  local remoteName="${remoteName:-$dumpName}"
  # Cache facoltativa (WIKIVOYAGE_DUMP_CACHE, la riempie la cache di Actions nel job "build"): un
  # dump gia' scaricato con lo sha1 giusto non si riscarica. Lo sha1 si controlla sempre.
  local cached="${WIKIVOYAGE_DUMP_CACHE:+$WIKIVOYAGE_DUMP_CACHE/$dumpName}"
  for attempt in 1 2 3; do
    if wikimedia_curl -o "$sha1File" "$sha1Url" 2>/dev/null; then
      # Il sha1sums di "latest" elenca i file con la data del dump ("itwikivoyage-20260901-pages-
      # articles.xml.bz2"), non con "latest": si cerca quel nome, stesso contenuto dell'alias.
      sha1="$(awk -v re="^${lang}wikivoyage-[0-9]+-pages-articles[.]xml[.]bz2\$" '$2 ~ re {print $1; exit}' "$sha1File")"
      if [ -n "$sha1" ] && [ -n "$cached" ] && printf '%s  %s\n' "$sha1" "$cached" | sha1sum -c - >/dev/null 2>&1; then
        echo "-- dump Wikivoyage ${lang}: dalla cache" >&2
        cp "$cached" "$outFile"
        rm -f "$sha1File"
        return 0
      fi
      if [ -n "$sha1" ] && wikimedia_curl --max-time 1800 -o "$outFile" "$baseUrl/$remoteName" 2>/dev/null \
        && printf '%s  %s\n' "$sha1" "$outFile" | sha1sum -c - >/dev/null 2>&1; then
        if [ -n "$cached" ]; then mkdir -p "$WIKIVOYAGE_DUMP_CACHE" && cp "$outFile" "$cached"; fi
        rm -f "$sha1File"
        return 0
      fi
    fi
    if [ "$attempt" -lt 3 ]; then
      echo "-- dump Wikivoyage ${lang}: tentativo $attempt/3 fallito (download o sha1), riprovo tra $((attempt * 30))s" >&2
      sleep $((attempt * 30))
    fi
  done
  rm -f "$sha1File" "$outFile"
  return 1
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
# overpass.openstreetmap.fr tolto: dal 2026-09 risponde solo a usi autorizzati. La disponibilita'
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
