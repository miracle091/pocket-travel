#!/usr/bin/env bash
# Job "publish" di publish-regions.yml: assembla il sito di Pages in $RUNNER_TEMP/site (manifest.json unito ai frammenti
# della run e a quelli in sospeso, region-timings.tsv, address-grid.json, transit.json, pagine delle licenze). Scarica
# anche i file pubblicati prima di questa run (previous-*.json in $RUNNER_TEMP), che usano il controllo sulle regioni
# perse e la pulizia degli asset. Scrive site_dir e pages_host in $GITHUB_OUTPUT.
# Ambiente: PAGES_BASE_URL, GITHUB_REPOSITORY_OWNER.
set -euo pipefail
source .github/scripts/env.sh
shopt -s nullglob

SITE_DIR="$RUNNER_TEMP/site"
mkdir -p "$SITE_DIR"
# `manifest.json` pubblicato prima di questa run: lo usano il controllo sulle regioni perse (prima
# del deploy) e la pulizia degli asset per il periodo di grazia di una generazione.
# Solo un 404 vale come "prima pubblicazione" (segnata da first-publication): ogni altro
# esito (rete, 5xx) dopo i tentativi ferma il passo, altrimenti il controllo sulle regioni
# perse e la pulizia degli asset verrebbero saltati in silenzio.
rm -f "$RUNNER_TEMP/previous-manifest.json" "$RUNNER_TEMP/first-publication"
MANIFEST_HTTP="$(curl -sSL --max-time 120 --retry 5 --retry-all-errors --retry-delay 5 "$PAGES_BASE_URL/manifest.json" \
  -o "$RUNNER_TEMP/previous-manifest.json" -w '%{http_code}' || true)"
case "$MANIFEST_HTTP" in
  200) ;;
  404)
    rm -f "$RUNNER_TEMP/previous-manifest.json"
    touch "$RUNNER_TEMP/first-publication"
    echo "::notice::nessun manifest.json pubblicato (404): prima pubblicazione"
    ;;
  *)
    echo "::error::manifest.json pubblicato non scaricabile da $PAGES_BASE_URL/manifest.json (HTTP ${MANIFEST_HTTP:-000}): non pubblico senza il confronto con il precedente" >&2
    exit 1
    ;;
esac
# address-grid.json pubblicato prima di questa run: gli URL delle celle non toccate oggi
# vivono solo li' (non in manifest.json, che ha solo il puntatore "addressGrid"), servono
# alla pulizia degli asset per lo stesso periodo di grazia.
# Stesso motivo per transit.json (URL degli orari delle reti). Come per `manifest.json`, solo
# un 404 vale come "indice non ancora pubblicato": ogni altro esito dopo i tentativi ferma
# il passo, altrimenti il sito si ripubblicherebbe senza l'indice precedente e le sue voci
# sparirebbero (e la pulizia degli asset cancellerebbe i file a cui puntavano).
for index_name in address-grid transit; do
  INDEX_FILE="$RUNNER_TEMP/previous-$index_name.json"
  rm -f "$INDEX_FILE"
  INDEX_HTTP="$(curl -sSL --max-time 120 --retry 5 --retry-all-errors --retry-delay 5 "$PAGES_BASE_URL/$index_name.json" \
    -o "$INDEX_FILE" -w '%{http_code}' || true)"
  case "$INDEX_HTTP" in
    200) ;;
    404)
      rm -f "$INDEX_FILE"
      echo "::notice::nessun $index_name.json pubblicato (404)"
      ;;
    *)
      echo "::error::$index_name.json pubblicato non scaricabile da $PAGES_BASE_URL/$index_name.json (HTTP ${INDEX_HTTP:-000}): non pubblico senza l'indice precedente" >&2
      exit 1
      ;;
  esac
done
# Stesso file anche per il merge (curl legge file://), senza scaricarlo una seconda volta; se
# il download sopra e' fallito assemble-site.sh riprova dall'URL e decide lui (errore, o
# BOOTSTRAP=1 alla prima pubblicazione).
MERGE_MANIFEST_URL="$PAGES_BASE_URL/manifest.json"
[ ! -s "$RUNNER_TEMP/previous-manifest.json" ] || MERGE_MANIFEST_URL="file://$RUNNER_TEMP/previous-manifest.json"

# Frammenti da unire (selectFragments): quelli di questa run validati regione per regione (una
# regione non valida si scarta, le altre si pubblicano) e quelli in sospeso di run precedenti
# piu' recenti del pubblicato e non piu' vecchi di 7 giorni.
RUN_FRAGMENTS=("$RUNNER_TEMP/fragments"/*.json)
PENDING_FRAGMENTS=("$RUNNER_TEMP/pending-fragments"/*.json)
SELECTED_LIST="$RUNNER_TEMP/selected-fragments.txt"
SELECTION_REPORT="$RUNNER_TEMP/fragments-report.tsv"
SELECT_ARGS="--pages-host $GITHUB_REPOSITORY_OWNER.github.io --max-age-days 7 --out-list $SELECTED_LIST --out-report $SELECTION_REPORT"
[ ! -s "$RUNNER_TEMP/previous-manifest.json" ] || SELECT_ARGS="$SELECT_ARGS --published $RUNNER_TEMP/previous-manifest.json"
[ "${#RUN_FRAGMENTS[@]}" -eq 0 ] || SELECT_ARGS="$SELECT_ARGS --run ${RUN_FRAGMENTS[*]}"
[ "${#PENDING_FRAGMENTS[@]}" -eq 0 ] || SELECT_ARGS="$SELECT_ARGS --pending ${PENDING_FRAGMENTS[*]}"
./gradlew -q :tools:data-pipeline:content:selectFragments --args="$SELECT_ARGS"
mapfile -t FRAGMENT_FILES < "$SELECTED_LIST"
{
  echo "### Frammenti di catalogo"
  echo "| Regione | Esito | Motivo |"
  echo "|---|---|---|"
  awk -F'\t' '$3 != "superata" { printf "| %s | %s | %s |\n", ($2 == "" ? $1 : $2), $3, $4 }' "$SELECTION_REPORT"
  echo
  echo "Superate (gia' pubblicate o sostituite da un frammento piu' recente): $(awk -F'\t' '$3 == "superata"' "$SELECTION_REPORT" | wc -l)"
} >> "$GITHUB_STEP_SUMMARY"
while IFS=$'\t' read -r _ regionId outcome reason; do
  if [ "$outcome" = "scartata" ]; then echo "::warning::frammento di ${regionId:-?} scartato: $reason"; fi
done < "$SELECTION_REPORT"
if [ "${#FRAGMENT_FILES[@]}" -eq 0 ]; then
  echo "Nessun frammento di catalogo da unire (ne' di questa run ne' in sospeso), niente da pubblicare" >&2
  exit 1
fi
tools/data-pipeline/scripts/assemble-site.sh "$SITE_DIR" "$MERGE_MANIFEST_URL" "${FRAGMENT_FILES[@]}"
# Tempi per regione (peso degli shard nel job "plan"): quelli pubblicati, aggiornati con
# quelli di questa run. Pages sostituisce l'intero sito, quindi si riportano ogni volta.
PUBLISHED_TIMINGS="$RUNNER_TEMP/published-timings.tsv"
curl -sSfL --retry 3 --retry-delay 5 -A "PocketTravelDataPipeline/1.0" "$PAGES_BASE_URL/region-timings.tsv" -o "$PUBLISHED_TIMINGS" || : > "$PUBLISHED_TIMINGS"
cat "$PUBLISHED_TIMINGS" "$RUNNER_TEMP/fragments"/timings-*.tsv \
  | awk -F'\t' 'NF == 2 && $2 ~ /^[0-9]+$/ { t[$1] = $2 } END { for (r in t) print r "\t" t[r] }' \
  | sort > "$SITE_DIR/region-timings.tsv"

# address-grid.json (indice dei civici a celle, job "address-grid"): accanto a
# manifest.json su Pages, come region-timings.tsv sopra - non e' un frammento da
# unire (e' gia' il risultato finale del merge fatto da generateAddressGrid nel suo
# job). Se quel job non ha prodotto nulla (download-artifact in continue-on-error),
# mergeManifests tiene la voce "addressGrid" del `manifest.json` pubblicato: si ripubblica
# allora l'indice precedente, lo stesso file (e sha256) a cui quella voce punta, invece
# di lasciare il puntatore verso un file sparito dal sito.
ADDRESS_GRID_FILE="$RUNNER_TEMP/address-grid-index/address-grid.json"
if [ -s "$ADDRESS_GRID_FILE" ]; then
  cp "$ADDRESS_GRID_FILE" "$SITE_DIR/address-grid.json"
elif [ -s "$RUNNER_TEMP/previous-address-grid.json" ]; then
  cp "$RUNNER_TEMP/previous-address-grid.json" "$SITE_DIR/address-grid.json"
fi
if [ -s "$RUNNER_TEMP/transit-index/transit.json" ]; then
  cp "$RUNNER_TEMP/transit-index/transit.json" "$SITE_DIR/transit.json"
elif [ -s "$RUNNER_TEMP/previous-transit.json" ]; then
  cp "$RUNNER_TEMP/previous-transit.json" "$SITE_DIR/transit.json"
fi

# Pagine delle licenze (licenses.html, licenses-en.html), collegate dal README: dagli indici
# appena copiati, cosi' fonti dei civici e reti dei mezzi pubblici corrispondono ai dati pubblicati. Non blocca la
# pubblicazione: se fallisce, Pages resta senza le pagine fino alla run successiva.
python3 tools/data-pipeline/scripts/licenses_page.py "$SITE_DIR" \
  || echo "::warning::pagine delle licenze non generate"

echo "site_dir=$SITE_DIR" >> "$GITHUB_OUTPUT"
echo "pages_host=$GITHUB_REPOSITORY_OWNER.github.io" >> "$GITHUB_OUTPUT"
