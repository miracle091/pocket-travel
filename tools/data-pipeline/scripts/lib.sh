#!/usr/bin/env bash
# Helper condivisi dagli script della pipeline publish-region (build-region.sh, build-guides.sh, assemble-site.sh,
# build-pilot-regions.sh, generate-weekly-schedule.sh). Solo funzioni, nessun effetto collaterale:
# sourcing sicuro da qualunque script con "set -euo pipefail" gia' attivo.
# shellcheck shell=bash

# gradlew invoca java.exe nativo di Windows: gli argomenti --args vogliono path Windows reali (con
# lettera di unita'), non il path POSIX virtuale di git-bash/MSYS (es. /tmp/xxx), altrimenti
# java.exe non li trova (visto: FileNotFoundException su un path tipo "\tmp\xxx\dump.txt", senza
# lettera di unita'). Su Linux (CI) cygpath non esiste e non serve: bash e java concordano gia'
# sullo stesso path POSIX.
winpath() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

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
    code="$(curl -s -o /dev/null -w '%{http_code}' -I "https://build.protomaps.com/${d}.pmtiles")"
    if [ "$code" = "200" ]; then
      echo "$d"
      return 0
    fi
  done
  echo "ERRORE: nessuna build Protomaps trovata negli ultimi giorni" >&2
  return 1
}

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
wikimedia_curl() { curl -sSf --retry 3 --retry-delay 5 -A "PocketTravelDataPipeline/1.0 (https://github.com/miracle091/pocket-travel)" "$@"; }
fetch_wikivoyage_dump() {
  local wikiTitle="$1" outFile="$2"
  local langlinks itTitle itTitleUrl
  langlinks="$(wikimedia_curl "https://en.wikivoyage.org/w/api.php?action=query&titles=${wikiTitle}&prop=langlinks&lllang=it&format=json&utf8=1&redirects=1" 2>/dev/null || true)"
  itTitle="$(printf '%s' "$langlinks" | sed -n 's/.*"lang":"it","\*":"\([^"]*\)".*/\1/p')"
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

# Solo la pagina inglese: stesso contratto di fetch_wikivoyage_dump (URL su stdout, 1 se vuota).
# build-guides.sh la usa anche come ripiego per le pagine italiane con i soli titoli.
fetch_wikivoyage_en_dump() {
  local wikiTitle="$1" outFile="$2"
  wikimedia_curl "https://en.wikivoyage.org/w/index.php?title=${wikiTitle}&action=raw" -o "$outFile" 2>/dev/null || : > "$outFile"
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
    if curl -sS --max-time 950 -A "PocketTravelDataPipeline/1.0 (https://github.com/miracle091/pocket-travel)" \
      "$endpoint" --data-urlencode "data=$query" -o "$outFile" &&
      jq -e '(.elements | type) == "array" and (.remark == null)' "$outFile" >/dev/null 2>&1; then
      return 0
    fi
    echo "-- Overpass $endpoint non disponibile o in timeout, provo il successivo" >&2
  done
  return 1
}
