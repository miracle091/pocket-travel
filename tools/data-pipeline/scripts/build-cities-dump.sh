#!/usr/bin/env bash
# Un solo passaggio sul dump di Wikivoyage IT (fase 1 di rag-knowledge-plan.md, "Contratto tra le
# parti") per TUTTE le regioni passate: risolve la pagina IT di ciascuna (stesso approccio a
# langlink di fetch_wikivoyage_dump, vedi resolve_it_wikivoyage_title in lib.sh) e scrive
# <outDir>/<regionId>.cities.jsonl con le pagine {{QuickbarCity}} che la citano (Stato/Stato
# federato/Regione/Territorio), lette poi da build-cities.sh (una regione alla volta, come
# build-addresses.sh legge le z15) per generare cities.db.
#
# Mai fatale per il chiamante (vedi publish-regions.yml): se il dump non si scarica o non passa la
# verifica sha1 (fetch_wikivoyage_it_dump in lib.sh), o se nessuna pagina IT si risolve, esce 1
# senza scrivere nulla in <outDir> - le regioni di questo run restano senza citta' nuove
# (build-cities.sh tiene la voce "cities" gia' pubblicata, se c'e').
#
# Uso: build-cities-dump.sh <regioni.tsv> <outDir>
# regioni.tsv: righe "regionId<TAB>wikiTitle" (titolo EN Wikivoyage, da pilot-regions.sh).
#
# Richiede: curl, python3, gli stessi di lib.sh (nessuna dipendenza in piu').
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Uso: $0 <regioni.tsv> <outDir>" >&2
  exit 1
fi

REGIONS_TSV="$1"
OUT_DIR="$2"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"

mkdir -p "$OUT_DIR"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

echo "-- citta': risolvo la pagina IT Wikivoyage di ogni regione..."
RESOLVED_TSV="$WORKDIR/resolved.tsv"
: > "$RESOLVED_TSV"
while IFS=$'\t' read -r regionId wikiTitle; do
  [ -n "$regionId" ] || continue
  itTitle="$(resolve_it_wikivoyage_title "$wikiTitle")"
  [ -n "$itTitle" ] && printf '%s\t%s\n' "$regionId" "$itTitle" >> "$RESOLVED_TSV"
done < "$REGIONS_TSV"

if [ ! -s "$RESOLVED_TSV" ]; then
  echo "::warning::citta': nessuna pagina IT Wikivoyage risolta per queste regioni"
  exit 1
fi

DUMP="$WORKDIR/$WIKIVOYAGE_IT_DUMP_NAME"
echo "-- citta': scarico il dump di Wikivoyage IT..."
if ! fetch_wikivoyage_it_dump "$DUMP"; then
  echo "::warning::citta': dump Wikivoyage IT non scaricato/verificato"
  exit 1
fi

echo "-- citta': estraggo le pagine citta' dal dump..."
python3 "$SCRIPT_DIR/extract-cities-dump.py" "$DUMP" "$RESOLVED_TSV" "$OUT_DIR"
