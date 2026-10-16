#!/usr/bin/env bash
# Passo "Controlla le reti escluse per gli orari" del job "transit" di publish-regions.yml, una volta a settimana e
# nelle run manuali: per ogni rete di tools/data-pipeline/transit-missing.tsv con motivo "orari" e url_feed riscarica
# il feed (build-transit.sh --download-only) e legge fino a quando valgono gli orari (gtfs_end_date). Se valgono
# almeno MIN_DAYS giorni da oggi lascia un ::notice:: per rimettere la rete in transit-feeds.tsv. Non tocca le liste
# e non pubblica nulla; il resoconto va nel riepilogo della run.
set -euo pipefail
source .github/scripts/env.sh
# shellcheck source=tools/data-pipeline/scripts/lib.sh
source tools/data-pipeline/scripts/lib.sh
MIN_DAYS=30  # come il criterio di esclusione di transit-missing.tsv: "validi meno di un mese"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
LIMIT="$(date -u -d "+$MIN_DAYS days" +%Y-%m-%d)"
mapfile -t ROWS < <(awk -F'\t' '$0 !~ /^#/ && $4 == "orari" && $5 != ""' tools/data-pipeline/transit-missing.tsv)
REPORT=()
for row in "${ROWS[@]}"; do
  IFS=$'\t' read -r id regions name _ url find <<< "$row"
  # Riga di una sola rete nel formato di transit-feeds.tsv. "-" nelle colonne che il download non usa: build-transit.sh
  # legge la riga con IFS=tab, per cui due tab di fila ne varrebbero uno e le colonne slitterebbero.
  printf '%s\t%s\t%s\t-\t-\t-\t%s\t%s\n' "$id" "$regions" "$name" "$url" "$find" > "$WORK/feeds.tsv"
  if ! TRANSIT_FEEDS="$WORK/feeds.tsv" tools/data-pipeline/scripts/build-transit.sh --download-only "$id" "$WORK" < /dev/null; then
    REPORT+=("- $id: feed non scaricato, si riprova la prossima settimana")
    continue
  fi
  end="$(gtfs_end_date "$WORK/$id.zip")"
  rm -f "$WORK/$id.zip"
  if [ "$end" != "?" ] && [[ ! "$end" < "$LIMIT" ]]; then
    echo "::notice::rete $id ($name): orari fino al $end, da verificare e rimettere in transit-feeds.tsv (e togliere da transit-missing.tsv)"
    REPORT+=("- $id: orari fino al $end, **da verificare e rimettere in transit-feeds.tsv**")
  else
    REPORT+=("- $id: orari fino al $end, ancora esclusa")
  fi
done
{
  echo "### Reti escluse per gli orari"
  if [ "${#REPORT[@]}" -gt 0 ]; then
    printf '%s\n' "${REPORT[@]}"
  else
    echo "Nessuna rete con url_feed in transit-missing.tsv."
  fi
} >> "$GITHUB_STEP_SUMMARY"
