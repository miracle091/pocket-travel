#!/usr/bin/env bash
# Script una tantum (NON eseguito in CI): genera weekly-schedule.sh, l'assegnazione statica delle
# regioni di pilot-regions.sh ai 7 giorni della settimana usata da publish-regions.yml per
# spalmare la ripubblicazione automatica.
# Va rilanciato a mano quando pilot-regions.sh cambia (regione
# aggiunta/rimossa/bbox modificato) o quando si vuole aggiornare il peso misurato delle regioni
# (la copertura .rd5 di BRouter puo' cambiare nel tempo) — nessun automatismo lo richiama da solo.
#
# Algoritmo:
#   1. Peso di ogni regione = tile .rd5 REALI (non geometriche): stessa griglia 5x5 gradi di
#      build-region.sh, una richiesta HEAD per tile unica su brouter.de per scartare le tile
#      oceaniche (nessun .rd5 pubblicato li'). Le tile sono deduplicate globalmente prima delle
#      richieste HEAD (alcune regioni confinano/si toccano: Stati Uniti/Russia/Kiribati/Figi sono
#      spezzate in piu' bbox adiacenti), cosi' ogni tile viene richiesta una sola volta.
#   2. Rank turistico noto solo per una manciata di nazioni (fonte UNWTO/Statista/Wikipedia 2024,
#      alta confidenza solo sulla top ~12): le altre regioni sono
#      "senza rank", in coda, nell'ordine di pilot-regions.sh (cioe' per continente).
#   3. Le regioni "gigante" (>50 tile land) vengono spalmate un giorno diverso a testa (ordine
#      decrescente per tile land, giorno = indice a rotazione sui 7 giorni) finche' i 7 giorni non
#      sono coperti, poi si ricomincia dal giorno 1 — cosi' il gigante piu' grande finisce
#      abbinato, quando serve un secondo giro, al gigante piu' piccolo, non a un altro enorme.
#   4. Le regioni con rank noto scelgono solo fra i giorni 1-4 (le piu' visitate spalmate nella
#      prima meta' della settimana); tutte le altre (incluse quelle senza rank) scelgono fra tutti
#      e 7 i giorni; in entrambi i casi si sceglie il giorno con il carico cumulativo piu' basso al
#      momento (bin-packing goloso).
#
# Uso: generate-weekly-schedule.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BROUTER_BASE="https://brouter.de/brouter/segments4"
OUT_FILE="$SCRIPT_DIR/weekly-schedule.sh"
GIANT_THRESHOLD=50
CONCURRENCY=8

# shellcheck source=./pilot-regions.sh
source "$SCRIPT_DIR/pilot-regions.sh"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

# --- passo 1: tile per regione + insieme globale deduplicato -----------------------------------
echo "== calcolo la griglia di tile per ${#PILOT_REGIONS[@]} regioni ==" >&2
declare -A REGION_TILES
ALL_TILES_FILE="$WORKDIR/all-tiles.txt"
: > "$ALL_TILES_FILE"
for spec in "${PILOT_REGIONS[@]}"; do
  IFS='|' read -r regionId _ minLon minLat maxLon maxLat _ _ _ _ _ <<< "$spec"
  lonStart="$(floor5 "$minLon")"; lonEnd="$(floor5 "$maxLon")"
  latStart="$(floor5 "$minLat")"; latEnd="$(floor5 "$maxLat")"
  tiles=""
  lon="$lonStart"
  while [ "$lon" -le "$lonEnd" ]; do
    lat="$latStart"
    while [ "$lat" -le "$latEnd" ]; do
      tile="$(tile_name "$lon" "$lat")"
      tiles="$tiles $tile"
      echo "$tile" >> "$ALL_TILES_FILE"
      lat=$((lat + 5))
    done
    lon=$((lon + 5))
  done
  REGION_TILES["$regionId"]="$tiles"
done

UNIQUE_TILES_FILE="$WORKDIR/unique-tiles.txt"
sort -u "$ALL_TILES_FILE" > "$UNIQUE_TILES_FILE"
TOTAL_UNIQUE="$(wc -l < "$UNIQUE_TILES_FILE" | tr -d ' ')"
echo "== risolvo $TOTAL_UNIQUE tile uniche su brouter.de ($CONCURRENCY richieste concorrenti, puo' richiedere qualche minuto) ==" >&2

# --- passo 2: HEAD concorrente per tile unica, una sola volta ----------------------------------
TILE_STATUS_FILE="$WORKDIR/tile-status.tsv"
: > "$TILE_STATUS_FILE"
running=0
resolved=0
while IFS= read -r tile; do
  (
    code="$(curl -s -o /dev/null -w '%{http_code}' -I "${BROUTER_BASE}/${tile}.rd5")"
    status="ocean"; [ "$code" = "200" ] && status="land"
    printf '%s\t%s\n' "$tile" "$status" >> "$TILE_STATUS_FILE"
  ) &
  running=$((running + 1))
  if [ "$running" -ge "$CONCURRENCY" ]; then
    wait -n
    running=$((running - 1))
    resolved=$((resolved + 1))
    [ $((resolved % 200)) -eq 0 ] && echo "-- $resolved/$TOTAL_UNIQUE tile risolte..." >&2
  fi
done < "$UNIQUE_TILES_FILE"
wait
echo "-- $TOTAL_UNIQUE/$TOTAL_UNIQUE tile risolte" >&2

declare -A TILE_STATUS
while IFS=$'\t' read -r tile status; do
  TILE_STATUS["$tile"]="$status"
done < "$TILE_STATUS_FILE"

# --- passo 3: tile land per regione, nell'ordine di pilot-regions.sh ---------------------------
LOADS_FILE="$WORKDIR/loads.tsv"
: > "$LOADS_FILE"
orderIndex=0
totalLand=0
for spec in "${PILOT_REGIONS[@]}"; do
  IFS='|' read -r regionId _ <<< "$spec"
  landCount=0
  for tile in ${REGION_TILES["$regionId"]}; do
    [ "${TILE_STATUS[$tile]:-ocean}" = "land" ] && landCount=$((landCount + 1))
  done
  printf '%s\t%d\t%d\n' "$regionId" "$landCount" "$orderIndex" >> "$LOADS_FILE"
  orderIndex=$((orderIndex + 1))
  totalLand=$((totalLand + landCount))
done
echo "== $totalLand tile land totali su ${#PILOT_REGIONS[@]} regioni ==" >&2

# --- passo 4: rank turistico noto (fonte UNWTO/Statista/Wikipedia 2024 — solo la top ~12 ad alta confidenza,
# il resto resta "senza rank" e va in coda nell'ordine di pilot-regions.sh) ---------------------
RANKS_FILE="$WORKDIR/ranks.tsv"
cat > "$RANKS_FILE" <<'RANKS'
francia	1
spagna	2
stati-uniti	3
cina	4
turchia	5
italia	6
messico	7
regno-unito	8
germania	9
grecia	10
austria	11
hong-kong	12
RANKS

COMBINED_FILE="$WORKDIR/combined.tsv"
awk -F'\t' '
  NR==FNR { rank[$1] = $2; next }
  { r = ($1 in rank) ? rank[$1] : 999999; printf "%s\t%d\t%d\t%d\n", $1, $2, $3, r }
' "$RANKS_FILE" "$LOADS_FILE" > "$COMBINED_FILE"
# colonne: regionId, landCount, orderIndex, visitRank

GIANTS_FILE="$WORKDIR/giants.tsv"
awk -F'\t' -v t="$GIANT_THRESHOLD" '$2 > t' "$COMBINED_FILE" | sort -t $'\t' -k2,2nr > "$GIANTS_FILE"

NONGIANT_FILE="$WORKDIR/nongiant.tsv"
awk -F'\t' -v t="$GIANT_THRESHOLD" '$2 <= t' "$COMBINED_FILE" | sort -t $'\t' -k4,4n -k3,3n > "$NONGIANT_FILE"

echo "== $(wc -l < "$GIANTS_FILE" | tr -d ' ') regioni gigante (>$GIANT_THRESHOLD tile land) ==" >&2

# --- passo 5: assegnazione ai 7 giorni (bin-packing goloso, vedi commento in testa al file) -----
ASSIGN_FILE="$WORKDIR/assignments.tsv"
awk -F'\t' -v giantsFile="$GIANTS_FILE" -v nonGiantFile="$NONGIANT_FILE" '
  BEGIN {
    for (d = 1; d <= 7; d++) dayLoad[d] = 0

    i = 0
    while ((getline line < giantsFile) > 0) {
      split(line, f, "\t")
      day = (i % 7) + 1
      print f[1] "\t" day "\t" f[3]
      dayLoad[day] += f[2]
      i++
    }
    close(giantsFile)

    while ((getline line < nonGiantFile) > 0) {
      split(line, f, "\t")
      regionId = f[1]; landCount = f[2] + 0; orderIndex = f[3]; visitRank = f[4] + 0
      maxDay = (visitRank < 999999) ? 4 : 7
      bestDay = 1; bestLoad = dayLoad[1]
      for (d = 2; d <= maxDay; d++) {
        if (dayLoad[d] < bestLoad) { bestLoad = dayLoad[d]; bestDay = d }
      }
      print regionId "\t" bestDay "\t" orderIndex
      dayLoad[bestDay] += landCount
    }
    close(nonGiantFile)
  }
' < /dev/null > "$ASSIGN_FILE"
# colonne: regionId, day, orderIndex

# --- passo 6: scrivo weekly-schedule.sh, un giorno per regione nell'ordine di pilot-regions.sh --
{
  echo "#!/usr/bin/env bash"
  echo "# Generato da generate-weekly-schedule.sh il $(date -u +%Y-%m-%d) — NON MODIFICARE A MANO."
  echo "# Rilancia generate-weekly-schedule.sh per rigenerarlo (es. dopo una modifica a"
  echo "# pilot-regions.sh). Ogni WEEKLY_SCHEDULE_DAY_N e' l'elenco regionId, separati da virgola,"
  echo "# da processare nel giorno N (1=lunedi ... 7=domenica)."
  echo "#"
  echo "# Carico misurato (tile land) per giorno al momento della generazione:"
  for d in 1 2 3 4 5 6 7; do
    dLoad="$(awk -F'\t' -v d="$d" -v loadsFile="$LOADS_FILE" '
      BEGIN {
        while ((getline line < loadsFile) > 0) { split(line, f, "\t"); land[f[1]] = f[2] }
      }
      $2 == d { sum += land[$1] }
      END { print sum + 0 }
    ' "$ASSIGN_FILE")"
    echo "#   giorno $d: $dLoad tile land"
  done
  echo ""
  for d in 1 2 3 4 5 6 7; do
    ids="$(sort -t $'\t' -k3,3n "$ASSIGN_FILE" | awk -F'\t' -v d="$d" '$2 == d { print $1 }' | paste -sd, -)"
    echo "WEEKLY_SCHEDULE_DAY_${d}=\"${ids}\""
  done
  echo ""
  echo "# Peso (tile land misurate) per regione, riusato da publish-regions.yml per bilanciare gli"
  echo "# shard dentro il bucket del giorno (bin-packing goloso, stesso principio di sopra ma sui 3"
  echo "# shard invece che sui 7 giorni)."
  echo "declare -A WEEKLY_SCHEDULE_TILES=("
  awk -F'\t' '{ printf "  [%s]=%d\n", $1, $2 }' "$LOADS_FILE"
  echo ")"
} > "$OUT_FILE"

echo "== scritto $OUT_FILE ==" >&2
