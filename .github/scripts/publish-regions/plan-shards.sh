#!/usr/bin/env bash
# Job "plan" di publish-regions.yml: sceglie le regioni della run (bucket del giorno, input manuale o tutte) e le
# divide nei 3 shard del job "build", con la coda della quota adattiva. Scrive in $GITHUB_OUTPUT shard0..2 e
# shard0_extra..shard2_extra.
# Ambiente: REGIONS_INPUT e POI_MAX_AGE_INPUT (input della run, mai interpolati nello script: niente iniezione di
# comandi), SCHEDULE (stringa cron che ha innescato la run, vuota nelle run manuali), PAGES_BASE_URL.
set -euo pipefail
source .github/scripts/env.sh
source tools/data-pipeline/scripts/regions.sh
# Serve sempre (non solo nel ramo schedule sotto): WEEKLY_SCHEDULE_TILES e' il peso per
# regione riusato piu' in basso per bilanciare gli shard.
source tools/data-pipeline/scripts/weekly-schedule.sh

# Spazi tolti ("italia, francia" -> "italia,francia"); ogni regionId deve essere un id
# valido, perche' finisce negli output del job e da li' negli script del job "build".
SELECTED="$(printf '%s' "${REGIONS_INPUT:-}" | tr -d '[:space:]')"
if [ -n "$SELECTED" ] && ! [[ "$SELECTED" =~ ^[a-z0-9-]+(,[a-z0-9-]+)*$ ]]; then
  echo "regions non valido: '$SELECTED' (regionId separati da virgola, solo a-z, 0-9 e -)" >&2
  exit 1
fi
if [ -z "$SELECTED" ] && [ -n "${SCHEDULE:-}" ]; then
  # SCHEDULE e' la stringa cron esatta che ha innescato la run (una delle 7 del workflow):
  # l'ultimo campo e' il giorno della settimana cron (0=domenica..6=sabato).
  # weekly-schedule.sh usa invece 1=lunedi..7=domenica (piu' naturale per un bucket
  # "giorno N della settimana lavorativa+weekend"), da cui il rimappaggio di 0 a 7.
  DOW="${SCHEDULE##* }"
  [ "$DOW" = "0" ] && DOW=7
  varName="WEEKLY_SCHEDULE_DAY_${DOW}"
  SELECTED="${!varName}"
  echo "-- trigger schedule ($SCHEDULE): bucket del giorno $DOW"
  ADAPTIVE_DOW="$DOW"
fi
# Un bucket del giorno vuoto resta vuoto (restano le regioni in ritardo della quota adattiva): "tutte" vale solo per
# le run manuali.
if [ -z "$SELECTED" ] && [ -z "${SCHEDULE:-}" ]; then
  # workflow_dispatch senza regions esplicite (fuori da uno schedule): tutto l'elenco delle regioni.
  ids=()
  for spec in "${ALL_REGIONS[@]}"; do
    IFS='|' read -r regionId _ <<< "$spec"
    ids+=("$regionId")
  done
  SELECTED="$(IFS=,; echo "${ids[*]}")"
fi
echo "-- regioni selezionate: $SELECTED"

# Sharding a matrice in 3 shard fissi, bilanciato per peso (non un semplice round-robin
# per indice, vedi il README della pipeline): le regioni selezionate vengono ordinate
# per peso decrescente e assegnate una a una, dalla piu' pesante alla piu' leggera, allo shard
# con il carico cumulativo piu' basso al momento (bin-packing goloso, stesso principio di
# generate-weekly-schedule.sh ma sui 3 shard invece che sui 7 giorni). Per run piccole/manuali
# uno o due shard possono restare vuoti - il job "build" per quello shard non fa nulla e
# termina subito, non e' un errore.
# Peso = secondi dell'ultima generazione completa della regione (region-timings.tsv,
# pubblicato accanto a `manifest.json` dal job "publish"): le tile contano poco per i POI,
# rigenerati ogni settimana e lunghi dove i punti sono tanti (Germania: poche tile, molti
# POI). Senza misura: 50 s per tile land (WEEKLY_SCHEDULE_TILES), la media delle regioni mai
# generate nella run completa del 2026-09-28; una regione assente anche li' pesa 50 s.
declare -A TIMINGS=()
if curl -sSfL --retry 3 --retry-delay 5 -A "PocketTravelDataPipeline/1.0" "$PAGES_BASE_URL/region-timings.tsv" -o "$RUNNER_TEMP/region-timings.tsv"; then
  while IFS=$'\t' read -r regionId seconds; do
    [[ "$seconds" =~ ^[0-9]+$ ]] && TIMINGS[$regionId]="$seconds"
  done < "$RUNNER_TEMP/region-timings.tsv"
fi
echo "-- tempi misurati per ${#TIMINGS[@]} regioni"
REGION_ARR=()
[ -z "$SELECTED" ] || IFS=',' read -ra REGION_ARR <<< "$SELECTED"
SORTED_FILE="$RUNNER_TEMP/sorted-regions.tsv"
for r in "${REGION_ARR[@]}"; do
  printf '%d\t%s\n' "${TIMINGS[$r]:-$(( ${WEEKLY_SCHEDULE_TILES[$r]:-1} * 50 ))}" "$r"
done | sort -t $'\t' -k1,1nr > "$SORTED_FILE"

SHARD_COUNT=3
declare -a SHARDS
declare -a SHARD_LOAD
for ((n = 0; n < SHARD_COUNT; n++)); do SHARDS[n]=""; SHARD_LOAD[n]=0; done
while IFS=$'\t' read -r weight regionId; do
  lightest=0
  for ((n = 1; n < SHARD_COUNT; n++)); do
    [ "${SHARD_LOAD[$n]}" -lt "${SHARD_LOAD[$lightest]}" ] && lightest=$n
  done
  if [ -z "${SHARDS[$lightest]}" ]; then SHARDS[lightest]="$regionId"; else SHARDS[lightest]="${SHARDS[$lightest]},$regionId"; fi
  SHARD_LOAD[lightest]=$((SHARD_LOAD[lightest] + weight))
done < "$SORTED_FILE"
echo "-- carico stimato per shard (secondi): ${SHARD_LOAD[0]}, ${SHARD_LOAD[1]}, ${SHARD_LOAD[2]}"

# Quota adattiva (solo nelle run del calendario): dopo le regioni del giorno, ogni shard riceve
# una coda di regioni "in piu'" da fare solo se gli resta tempo (il job build le avvia solo se
# la stima ci sta nel budget, e non le rimanda mai alla continuazione). In ordine:
#   1. regioni in ritardo: POI pubblicati da piu' di POI_MAX_AGE_DAYS giorni o mai pubblicati
#      (fallite, rimandate oltre le continuazioni, nuove), dalla piu' vecchia;
#   2. le regioni di domani, cosi' una giornata tranquilla alleggerisce quella dopo.
# Una regione fatta in anticipo il suo giorno ha i POI giovani e si salta in fretta; se slitta
# oltre il limite d'eta' rientra nella coda 1. Le giornate troppo piene restano gestite dal
# budget e dalla continuazione.
declare -a EXTRAS=("" "" "")
if [ -n "${ADAPTIVE_DOW:-}" ]; then
  POI_MAX_AGE="${POI_MAX_AGE_INPUT:-6}"
  declare -A TODAY=()
  for r in "${REGION_ARR[@]}"; do TODAY[$r]=1; done
  # Eta' dei POI pubblicati, dal nome dell'asset (regionId--YYYY.MM.DD[.run.tentativo]--poi.db...).
  declare -A POI_AGE=()
  if curl -sSfL --retry 3 --retry-delay 5 --max-time 120 -A "PocketTravelDataPipeline/1.0" "$PAGES_BASE_URL/manifest.json" -o "$RUNNER_TEMP/plan-manifest.json"; then
    NOW_EPOCH="$(date -u +%s)"
    while IFS=$'\t' read -r regionId url; do
      d="$(printf '%s' "$url" | sed -n 's#.*--\([0-9]\{4\}\.[0-9]\{2\}\.[0-9]\{2\}\)\(\.[0-9][0-9.]*\)\?--\(poi\|content\)\.db\(\.xz\)\?$#\1#p')"
      [ -n "$d" ] || continue
      POI_AGE[$regionId]=$(( (NOW_EPOCH - $(date -u -d "${d//./-}" +%s)) / 86400 ))
    done < <(jq -r '.regions[] | [.regionId, (.poi.file.url // "")] | @tsv' "$RUNNER_TEMP/plan-manifest.json")
  else
    echo "::warning::manifest.json pubblicato non leggibile: niente regioni in ritardo nella quota adattiva"
  fi
  EXTRA_FILE="$RUNNER_TEMP/extra-regions.tsv"
  : > "$EXTRA_FILE"
  if [ "${#POI_AGE[@]}" -gt 0 ]; then
    for spec in "${ALL_REGIONS[@]}"; do
      IFS='|' read -r regionId _ <<< "$spec"
      [ -z "${TODAY[$regionId]:-}" ] || continue
      age="${POI_AGE[$regionId]:-9999}"
      if [ "$age" -gt "$POI_MAX_AGE" ]; then printf '%d\t%s\n' "$age" "$regionId"; fi
    done | sort -t $'\t' -k1,1nr | cut -f2 >> "$EXTRA_FILE"
  fi
  OVERDUE_COUNT="$(wc -l < "$EXTRA_FILE")"
  nextVar="WEEKLY_SCHEDULE_DAY_$(( ADAPTIVE_DOW % 7 + 1 ))"
  IFS=',' read -ra TOMORROW <<< "${!nextVar}"
  for r in "${TOMORROW[@]}"; do
    if [ -n "$r" ] && [ -z "${TODAY[$r]:-}" ] && ! grep -qxF "$r" "$EXTRA_FILE"; then echo "$r" >> "$EXTRA_FILE"; fi
  done
  echo "-- quota adattiva: $OVERDUE_COUNT regioni in ritardo e $(( $(wc -l < "$EXTRA_FILE") - OVERDUE_COUNT )) di domani in coda, se resta tempo"
  # Stessa spartizione golosa, con il carico gia' assegnato: "id:secondi stimati", in ordine di priorita'.
  while read -r regionId; do
    [ -n "$regionId" ] || continue
    weight="${TIMINGS[$regionId]:-$(( ${WEEKLY_SCHEDULE_TILES[$regionId]:-1} * 50 ))}"
    lightest=0
    for ((n = 1; n < SHARD_COUNT; n++)); do
      [ "${SHARD_LOAD[$n]}" -lt "${SHARD_LOAD[$lightest]}" ] && lightest=$n
    done
    EXTRAS[lightest]="${EXTRAS[$lightest]:+${EXTRAS[$lightest]},}$regionId:$weight"
    SHARD_LOAD[lightest]=$((SHARD_LOAD[lightest] + weight))
  done < "$EXTRA_FILE"
fi
{
  echo "shard0_extra=${EXTRAS[0]}"
  echo "shard1_extra=${EXTRAS[1]}"
  echo "shard2_extra=${EXTRAS[2]}"
  echo "shard0=${SHARDS[0]}"
  echo "shard1=${SHARDS[1]}"
  echo "shard2=${SHARDS[2]}"
} >> "$GITHUB_OUTPUT"
