#!/usr/bin/env bash
# Helper condivisi dagli script della pipeline publish-region (build-region.sh, assemble-site.sh,
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
  local lon="$1" lat="$2" ew="E" ns="N" alon="$lon" alat="$lat"
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
