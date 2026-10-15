#!/usr/bin/env bash
# Job "address-grid" di publish-regions.yml: costruisce le celle dei civici della quota di oggi (o quelle scelte a mano),
# le carica sulle release "address-cells-<n>" e genera l'indice address-grid.json con il suo frammento di catalogo
# ($RUNNER_TEMP/address-grid-index/address-grid.json, $RUNNER_TEMP/fragments/address-grid.json).
# Ambiente: PAGES_BASE_URL, ADDRESS_CELL_RELEASES, ADDRESS_CELL_MAX_AGE_DAYS (letto da build-address-cell.sh),
# ADDRESS_CELLS_INPUT (input della run, mai interpolato nello script), GITHUB_REPOSITORY, GITHUB_RUN_NUMBER,
# GITHUB_RUN_ATTEMPT, GH_TOKEN.
set -euo pipefail
source .github/scripts/env.sh
# Fuori dal workflow, gli stessi valori (ADDRESS_CELLS_INPUT vuoto = quota di oggi).
: "${ADDRESS_CELL_RELEASES:=12}" "${ADDRESS_CELLS_INPUT:=}"
# shellcheck source=tools/data-pipeline/scripts/lib.sh
source tools/data-pipeline/scripts/lib.sh
INDEX_URL="$PAGES_BASE_URL/address-grid.json"

# Partizione attuale: le celle dell'indice pubblicato (fonte di verita', include le celle
# divise da run precedenti) piu' le celle del seme versionato nel repo non ancora coperte,
# cioe' ne' pubblicate ne' antenate di una cella pubblicata (divisa). Senza il seme la
# partizione resterebbe ferma alle celle della prima quota pubblicata. Alla primissima
# pubblicazione (404) e' il solo seme.
IDS_FILE="$RUNNER_TEMP/all-cell-ids.txt"
PUBLISHED_INDEX_FILE="$RUNNER_TEMP/published-index.json"
# File locale scaricato una volta per l'intero job (non uno per cella): passato cosi'
# com'e' a build-address-cell.sh piu' sotto (fetch_published_manifest, lib.sh, accetta
# un path al posto dell'URL).
PUBLISHED_INDEX_ARG=""
PUBLISHED_INDEX_HTTP="$(fetch_published_manifest "$INDEX_URL" "$PUBLISHED_INDEX_FILE")"
if [ "$PUBLISHED_INDEX_HTTP" = "200" ]; then
  jq -r '.cells[].id' "$PUBLISHED_INDEX_FILE" > "$IDS_FILE"
  # Prima passata (le celle pubblicate): segna ognuna e tutti i suoi antenati come
  # coperti. Seconda passata (il seme): aggiunge le celle non coperte.
  awk -F/ 'NR == FNR { z = $1; x = $2; y = $3
                       while (z >= 0) { covered[z "/" x "/" y] = 1; z--; x = int(x / 2); y = int(y / 2) }
                       next }
           $0 != "" && !($0 in covered)' "$IDS_FILE" tools/data-pipeline/address-grid-seed.tsv \
    > "$RUNNER_TEMP/uncovered-seed-ids.txt"
  cat "$RUNNER_TEMP/uncovered-seed-ids.txt" >> "$IDS_FILE"
  PUBLISHED_INDEX_ARG="$PUBLISHED_INDEX_FILE"
elif [ "$PUBLISHED_INDEX_HTTP" = "404" ]; then
  cp tools/data-pipeline/address-grid-seed.tsv "$IDS_FILE"
  rm -f "$PUBLISHED_INDEX_FILE"
else
  echo "ERRORE: indice civici non scaricabile da $INDEX_URL (HTTP ${PUBLISHED_INDEX_HTTP:-000})" >&2
  exit 1
fi
echo "-- $(wc -l < "$IDS_FILE") celle nella partizione attuale"

# Slot 0..29 stabile per id (cksum: nessuna dipendenza in piu', basta per distribuire il
# lavoro sui giorni - non serve crittografico). Giorni dall'epoca, non giorno del mese:
# niente bias sui bordi del mese.
TODAY_SLOT=$(( $(date -u +%s) / 86400 % 30 ))
SELECTED_FILE="$RUNNER_TEMP/selected-cells.txt"
: > "$SELECTED_FILE"
if [ -n "$ADDRESS_CELLS_INPUT" ]; then
  # Celle scelte a mano (input address_cells): solo id della partizione attuale, cosi' una
  # cella gia' divisa o inesistente non rientra nell'indice.
  IFS=',' read -ra CHOSEN_CELLS <<< "$(printf '%s' "$ADDRESS_CELLS_INPUT" | tr -d '[:space:]')"
  for id in "${CHOSEN_CELLS[@]}"; do
    if ! [[ "$id" =~ ^[0-9]+/[0-9]+/[0-9]+$ ]]; then
      echo "address_cells non valido: '$id' (id z/x/y separati da virgola)" >&2
      exit 1
    fi
    if ! grep -qxF "$id" "$IDS_FILE"; then
      echo "address_cells: la cella $id non e' nella partizione attuale" >&2
      exit 1
    fi
    echo "$id" >> "$SELECTED_FILE"
  done
  echo "-- celle scelte a mano: $(wc -l < "$SELECTED_FILE")"
else
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    slot=$(( $(printf '%s' "$id" | cksum | cut -d' ' -f1) % 30 ))
    [ "$slot" -eq "$TODAY_SLOT" ] && echo "$id" >> "$SELECTED_FILE"
  done < "$IDS_FILE"
  echo "-- quota di oggi (slot $TODAY_SLOT/30): $(wc -l < "$SELECTED_FILE") celle"
fi

PROTOMAPS_DATE="$(resolve_protomaps_date)"
SOURCE_URL="https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles"
echo "-- build Protomaps: ${PROTOMAPS_DATE}.pmtiles"
# Stesso rilascio Overture per tutte le celle della run (il piu' recente, risolto una volta).
OVERTURE_RELEASE="$(python3 tools/data-pipeline/scripts/overture_addresses.py --print-release)"
export OVERTURE_RELEASE
echo "-- rilascio Overture: $OVERTURE_RELEASE"
VERSION="$(date -u +%Y.%m.%d).$GITHUB_RUN_NUMBER.$GITHUB_RUN_ATTEMPT"

ALL_ENTRIES="$RUNNER_TEMP/all-entries.jsonl"
: > "$ALL_ENTRIES"
FAILED_CELLS=()
while IFS= read -r id; do
  [ -n "$id" ] || continue
  n=$(( $(printf '%s' "$id" | cksum | cut -d' ' -f1) % ADDRESS_CELL_RELEASES ))
  tag="address-cells-$n"
  CELL_OUT="$RUNNER_TEMP/cell-out"
  rm -rf "$CELL_OUT"
  mkdir -p "$CELL_OUT"
  echo "::group::$id"
  if ! tools/data-pipeline/scripts/build-address-cell.sh "$id" "$VERSION" "$SOURCE_URL" \
    "https://github.com/$GITHUB_REPOSITORY/releases/download/$tag" "$CELL_OUT" \
    "$PUBLISHED_INDEX_ARG"; then
    echo "::warning::cella $id fallita, salto e continuo"
    FAILED_CELLS+=("$id")
    echo "::endgroup::"
    continue
  fi
  shopt -s nullglob
  # Civici e indice di ricerca per via (quest'ultimo solo se nuovo o cambiato).
  assets=("$CELL_OUT"/cell-*"--${VERSION}--addresses.pmtiles.xz" "$CELL_OUT"/cell-*"--${VERSION}--addresses-search.db.xz")
  if [ "${#assets[@]}" -gt 0 ]; then
    uploadOk=false
    for attempt in 1 2 3; do
      if gh release upload "$tag" "${assets[@]}" --repo "$GITHUB_REPOSITORY" --clobber; then
        uploadOk=true
        break
      fi
      [ "$attempt" -lt 3 ] && sleep $((attempt * 30))
    done
    if [ "$uploadOk" != true ]; then
      echo "::warning::upload della cella $id fallito, salto e continuo"
      FAILED_CELLS+=("$id")
      echo "::endgroup::"
      continue
    fi
  fi
  [ ! -f "$CELL_OUT/address-grid-entries.jsonl" ] || cat "$CELL_OUT/address-grid-entries.jsonl" >> "$ALL_ENTRIES"
  echo "::endgroup::"
done < "$SELECTED_FILE"
if [ "${#FAILED_CELLS[@]}" -gt 0 ]; then
  echo "::warning::celle fallite in questa run (restano con la voce gia' pubblicata, se c'era): ${FAILED_CELLS[*]}"
fi

# Attribuzioni per la schermata Licenze: OSM, Overture e i distributori (OpenAddresses,
# AddressForAll), piu' il testo richiesto da ogni fonte ammessa dalla lista bianca (tutte
# quelle che la griglia puo' usare nel tempo, non solo quelle toccate dalle celle di oggi -
# sapere quali finiscono davvero in OGNI cella pubblicata richiederebbe di leggerle tutte a
# ogni run). {anno} e {data} nei testi: data del rilascio Overture usato. Testi uguali una volta sola.
ATTRIBUTIONS_TSV="$RUNNER_TEMP/attributions.tsv"
OVERTURE_DATE="${OVERTURE_RELEASE%%.*}"
{
  printf 'OpenStreetMap\tODbL-1.0\thttps://www.openstreetmap.org/copyright\n'
  printf 'Overture Maps Foundation (overturemaps.org), OpenAddresses, AddressForAll\tvarie\thttps://docs.overturemaps.org/attribution/\n'
  awk -F'\t' -v anno="${OVERTURE_DATE%%-*}" -v data="$OVERTURE_DATE" \
    '$0 !~ /^#/ && NF >= 5 && $4 == "allow" && !seen[$5]++ {
       text = $5; gsub(/\{anno\}/, anno, text); gsub(/\{data\}/, data, text)
       printf "%s\t%s\thttps://docs.overturemaps.org/attribution/\n", text, $3
     }' tools/data-pipeline/overture-address-sources.tsv
} > "$ATTRIBUTIONS_TSV"

mkdir -p "$RUNNER_TEMP/fragments" "$RUNNER_TEMP/address-grid-index"
INDEX_OUT="$RUNNER_TEMP/address-grid-index/address-grid.json"
FRAGMENT_OUT="$RUNNER_TEMP/fragments/address-grid.json"
# Stesso schema di ARGS_STR in assemble-site.sh: costruita per pezzi (non con
# un'espansione di array dentro una stringa gia' tra doppi apici, che non separerebbe
# correttamente gli elementi).
ARGS_STR="\"$VERSION\" \"$INDEX_URL\" \"$(winpath "$INDEX_OUT")\" \"$(winpath "$FRAGMENT_OUT")\" \"$(winpath "$ATTRIBUTIONS_TSV")\""
[ ! -s "$PUBLISHED_INDEX_FILE" ] || ARGS_STR="$ARGS_STR --published \"$(winpath "$PUBLISHED_INDEX_FILE")\""
[ ! -s "$ALL_ENTRIES" ] || ARGS_STR="$ARGS_STR --entries \"$(winpath "$ALL_ENTRIES")\""
./gradlew -q :tools:data-pipeline:content:generateAddressGrid --args="$ARGS_STR"
