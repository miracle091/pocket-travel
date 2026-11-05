#!/usr/bin/env bash
# Job "address-grid" di publish-regions.yml: rigenera il seme della griglia dei civici per un rilascio Overture.
# Legge i metadati dei row group del tema addresses (overture_rowgroups.py), poi li unisce ai civici gia' pubblicati
# (seed-address-grid.py, che legge il manifest.json pubblicato) e scrive $ADDRESS_SEED_DIR/address-grid-seed.tsv.
# Il seme compare solo a fine lavoro: un'esecuzione interrotta non lascia un file a meta'.
# Ambiente: OVERTURE_RELEASE, ADDRESS_SEED_DIR, ADDRESS_CELL_MIN_ZOOM (default 7), PAGES_BASE_URL.
set -euo pipefail
source .github/scripts/env.sh
: "${OVERTURE_RELEASE:?}" "${ADDRESS_SEED_DIR:?}" "${ADDRESS_CELL_MIN_ZOOM:=7}"

SCRIPTS=tools/data-pipeline/scripts
ROWGROUPS="$RUNNER_TEMP/overture-rowgroups.csv"
mkdir -p "$ADDRESS_SEED_DIR"
rm -f "$ADDRESS_SEED_DIR/address-grid-seed.tsv"

python3 "$SCRIPTS/overture_rowgroups.py" "$ROWGROUPS" --release "$OVERTURE_RELEASE"
python3 "$SCRIPTS/seed-address-grid.py" "$PAGES_BASE_URL/manifest.json" "$ROWGROUPS" \
  "$ADDRESS_SEED_DIR/address-grid-seed.tsv.tmp" --min-zoom "$ADDRESS_CELL_MIN_ZOOM"
if [ ! -s "$ADDRESS_SEED_DIR/address-grid-seed.tsv.tmp" ]; then
  echo "seme vuoto per il rilascio $OVERTURE_RELEASE" >&2
  exit 1
fi
mv "$ADDRESS_SEED_DIR/address-grid-seed.tsv.tmp" "$ADDRESS_SEED_DIR/address-grid-seed.tsv"
echo "-- seme rigenerato per il rilascio $OVERTURE_RELEASE: $(wc -l < "$ADDRESS_SEED_DIR/address-grid-seed.tsv") celle"
