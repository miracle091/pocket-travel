#!/usr/bin/env bash
# Job "publish" di publish-regions.yml: assembla il sito di Pages in $RUNNER_TEMP/site (manifest.json unito ai frammenti
# della run e a quelli in sospeso, region-timings.tsv, address-grid.json, transit.json, pagine delle licenze). Scarica
# anche i file pubblicati prima di questa run (previous-*.json in $RUNNER_TEMP), che usano il controllo sulle regioni
# perse e la pulizia degli asset. Scrive site_dir e pages_host in $GITHUB_OUTPUT.
# Senza frammenti da unire la run finisce in verde con nothing=true in $GITHUB_OUTPUT (guide e mappa del mondo non
# cambiate), tranne per ONLY_INPUT vuoto, all o regions: li' la run costruisce regioni e zero frammenti e' un errore.
# Ambiente: PAGES_BASE_URL, GITHUB_REPOSITORY_OWNER, ONLY_INPUT (input "only" della run).
set -euo pipefail
source .github/scripts/env.sh
# shellcheck source=tools/data-pipeline/scripts/lib.sh
source tools/data-pipeline/scripts/lib.sh
source .github/scripts/retry.sh
shopt -s nullglob

SITE_DIR="$RUNNER_TEMP/site"
mkdir -p "$SITE_DIR"
# Pages risponde con max-age=600 dietro una CDN: senza un parametro nuovo a ogni richiesta e Cache-Control: no-cache una
# run a meno di 10 minuti dal deploy precedente leggerebbe i file di prima, e il merge e il controllo sulle regioni
# perse partirebbero da un catalogo vecchio.
FRESH="cb=$(date +%s)"
# fetch_code <url> <file> [opzioni di curl...]: scarica <url> in <file> e stampa il codice HTTP (000 se la rete
# fallisce). Gli errori di rete e i 408, 429 e 5xx si ritentano con retry (attese di 20 e 40 s); un 404 no.
fetch_code() {
  local url="$1" out="$2" code_file="$RUNNER_TEMP/http-code"
  shift 2
  rm -f "$code_file"
  # shellcheck disable=SC2329 # chiamata da retry, qui sotto
  _fetch_once() {
    local code
    curl -sSL --max-time 120 -H 'Cache-Control: no-cache' "$@" -o "$out" -w '%{http_code}' "$url" > "$code_file" || return 1
    code="$(cat "$code_file")"
    case "$code" in 408 | 429 | 5??) return 1 ;; esac
  }
  retry _fetch_once "$@" >&2 || true
  cat "$code_file" 2> /dev/null || echo 000
}
# `manifest.json` pubblicato prima di questa run: lo usano il controllo sulle regioni perse (prima
# del deploy) e la pulizia degli asset per il periodo di grazia di una generazione.
# Solo un 404 vale come "prima pubblicazione" (segnata da first-publication): ogni altro
# esito (rete, 5xx) dopo i tentativi ferma il passo, altrimenti il controllo sulle regioni
# perse e la pulizia degli asset verrebbero saltati in silenzio.
rm -f "$RUNNER_TEMP/previous-manifest.json" "$RUNNER_TEMP/previous-manifest.json.sig" "$RUNNER_TEMP/first-publication"
# Come l'app, il catalogo precedente si usa solo se la firma (manifest.json.sig) e' valida: un file manomesso sul sito
# non deve finire nel merge. Subito dopo un deploy la CDN di Pages puo' servire per qualche minuto un manifest.json e una
# firma di versioni diverse: fino a 4 tentativi a 20 s l'uno, ognuno con un parametro anti-cache nuovo.
: "${SIGNATURE_RETRY_DELAY:=20}"  # secondi tra un tentativo e l'altro; i test lo azzerano
SIGNATURE_OK=false
MANIFEST_HTTP=""
for attempt in 1 2 3 4; do
  FRESH="cb=$(date +%s)"
  MANIFEST_HTTP="$(fetch_code "$PAGES_BASE_URL/manifest.json?$FRESH" "$RUNNER_TEMP/previous-manifest.json")"
  [ "$MANIFEST_HTTP" = 200 ] || break
  if verify_published_signature "$RUNNER_TEMP/previous-manifest.json" "$PAGES_BASE_URL/manifest.json?$FRESH"; then
    SIGNATURE_OK=true
    break
  fi
  [ "$attempt" -lt 4 ] || break
  echo "::warning::firma di manifest.json pubblicato non valida (tentativo $attempt/4), riprovo tra ${SIGNATURE_RETRY_DELAY}s" >&2
  sleep "$SIGNATURE_RETRY_DELAY"
done
case "$MANIFEST_HTTP" in
  200)
    if [ "$SIGNATURE_OK" != true ]; then
      echo "::error::firma di manifest.json pubblicato mancante o non valida: non uso un manifest.json non verificabile" >&2
      exit 1
    fi
    ;;
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
  INDEX_HTTP="$(fetch_code "$PAGES_BASE_URL/$index_name.json?$FRESH" "$INDEX_FILE")"
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
MERGE_MANIFEST_URL="$PAGES_BASE_URL/manifest.json?$FRESH"
if [ -s "$RUNNER_TEMP/previous-manifest.json" ]; then
  # Su Git Bash curl e' nativo di Windows: vuole file:///C:/..., non il path POSIX virtuale (su Linux winpath non cambia nulla).
  PREVIOUS_MANIFEST_PATH="$(winpath "$RUNNER_TEMP/previous-manifest.json")"
  case "$PREVIOUS_MANIFEST_PATH" in
    /*) MERGE_MANIFEST_URL="file://$PREVIOUS_MANIFEST_PATH" ;;
    *) MERGE_MANIFEST_URL="file:///$PREVIOUS_MANIFEST_PATH" ;;
  esac
fi

# Frammenti da unire (selectFragments): quelli di questa run validati regione per regione (una
# regione non valida si scarta, le altre si pubblicano) e quelli in sospeso di run precedenti
# piu' recenti del pubblicato e non piu' vecchi di 7 giorni.
RUN_FRAGMENTS=("$RUNNER_TEMP/fragments"/*.json)
PENDING_FRAGMENTS=("$RUNNER_TEMP/pending-fragments"/*.json)
SELECTED_LIST="$RUNNER_TEMP/selected-fragments.txt"
SELECTION_REPORT="$RUNNER_TEMP/fragments-report.tsv"
# java.exe (via gradlew) vuole path Windows reali: winpath (vedi lib.sh), che su Linux lascia i path invariati.
SELECT_ARGS="--pages-host $GITHUB_REPOSITORY_OWNER.github.io --max-age-days 7 --out-list \"$(winpath "$SELECTED_LIST")\" --out-report \"$(winpath "$SELECTION_REPORT")\""
[ ! -s "$RUNNER_TEMP/previous-manifest.json" ] || SELECT_ARGS="$SELECT_ARGS --published \"$(winpath "$RUNNER_TEMP/previous-manifest.json")\""
if [ "${#RUN_FRAGMENTS[@]}" -gt 0 ]; then
  SELECT_ARGS="$SELECT_ARGS --run"
  for f in "${RUN_FRAGMENTS[@]}"; do SELECT_ARGS="$SELECT_ARGS \"$(winpath "$f")\""; done
fi
if [ "${#PENDING_FRAGMENTS[@]}" -gt 0 ]; then
  SELECT_ARGS="$SELECT_ARGS --pending"
  for f in "${PENDING_FRAGMENTS[@]}"; do SELECT_ARGS="$SELECT_ARGS \"$(winpath "$f")\""; done
fi
./gradlew --no-daemon -q :tools:data-pipeline:content:selectFragments --args="$SELECT_ARGS"
mapfile -t FRAGMENT_FILES < "$SELECTED_LIST"
{
  echo "### Frammenti di catalogo"
  echo "| Regione | Esito | Motivo |"
  echo "|---|---|---|"
  awk -F'\t' '$3 != "superata" { printf "| %s | %s | %s |\n", ($2 == "" ? $1 : $2), $3, $4 }' "$SELECTION_REPORT"
  echo
  echo "Superate (gia' pubblicate o sostituite da un frammento piu' recente): $(awk -F'\t' '$3 == "superata"' "$SELECTION_REPORT" | wc -l)"
} >> "$GITHUB_STEP_SUMMARY"
# Colonne separate da \x1f e non da tab: con `IFS=$'\t' read` i tab vuoti consecutivi (file<TAB><TAB>scartata, senza
# regione) si fonderebbero e le colonne si sposterebbero.
while IFS=$'\x1f' read -r regionId reason; do
  echo "::warning::frammento di ${regionId:-?} scartato: $reason"
done < <(awk -F'\t' '$3 == "scartata" { printf "%s\037%s\n", $2, $4 }' "$SELECTION_REPORT")
if [ "${#FRAGMENT_FILES[@]}" -eq 0 ]; then
  case "${ONLY_INPUT:-all}" in
    all | regions)
      echo "Nessun frammento di catalogo da unire (ne' di questa run ne' in sospeso), niente da pubblicare" >&2
      exit 1
      ;;
    *)
      echo "::notice::nessun frammento di catalogo da unire (ne' di questa run ne' in sospeso): niente da pubblicare, il sito resta com'e'"
      echo "nothing=true" >> "$GITHUB_OUTPUT"
      exit 0
      ;;
  esac
fi
tools/data-pipeline/scripts/assemble-site.sh "$SITE_DIR" "$MERGE_MANIFEST_URL" "${FRAGMENT_FILES[@]}"
# Tempi per regione (peso degli shard nel job "plan"): quelli pubblicati, aggiornati con
# quelli di questa run. Pages sostituisce l'intero sito, quindi si riportano ogni volta.
PUBLISHED_TIMINGS="$RUNNER_TEMP/published-timings.tsv"
# Solo un 404 (prima pubblicazione) parte da tempi vuoti: un errore passeggero fermerebbe altrimenti la run, perche'
# azzererebbe i tempi di tutte le regioni non toccate da questa (e con loro il peso degli shard).
TIMINGS_HTTP="$(fetch_code "$PAGES_BASE_URL/region-timings.tsv?$FRESH" "$PUBLISHED_TIMINGS" -A "PocketTravelDataPipeline/1.0")"
case "$TIMINGS_HTTP" in
  200) ;;
  404)
    echo "::warning::region-timings.tsv non ancora pubblicato: tempi solo di questa run"
    : > "$PUBLISHED_TIMINGS"
    ;;
  *)
    echo "::error::region-timings.tsv pubblicato non scaricabile (HTTP ${TIMINGS_HTTP:-000})" >&2
    exit 1
    ;;
esac
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
