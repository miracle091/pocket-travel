#!/usr/bin/env bash
# Job "plan" di publish-regions.yml: crea le release che ospitano i pacchetti, se mancano.
# Gira una volta, prima della matrice, e non dentro il job "build": se gli shard 0/1/2 eseguissero tutti
# "gh release view || gh release create" in parallelo, alla primissima run in assoluto (release ancora
# inesistente) andrebbero in race, e il secondo "gh release create" fallirebbe facendo fallire lo shard.
# Ambiente: GITHUB_REPOSITORY, GH_TOKEN.
set -euo pipefail
source .github/scripts/env.sh
source .github/scripts/retry.sh
source tools/data-pipeline/scripts/regions.sh

# Un solo elenco delle release esistenti invece di un "gh release view" per ognuna (~70): ensure_release
# solo per le mancanti. Elenco non leggibile o troncato dal limite: restano vuote/mancanti e si ricade su
# ensure_release, che controlla da se'.
EXISTING_TAGS="$(retry gh release list --repo "$GITHUB_REPOSITORY" --limit 300 --json tagName --jq '.[].tagName')" || EXISTING_TAGS=""
ensure_release_if_missing() {
  if grep -qxF "$1" <<< "$EXISTING_TAGS"; then return 0; fi
  ensure_release "$@"
}

# Le regioni si spartiscono su REGION_RELEASES release "region-data-rNN" (region_release_tag in
# regions.sh): GitHub limita ogni release a 1000 asset (HTTP 422 "file_count limited to 1000
# assets per release"). Le si creano tutte, anche quelle che oggi non hanno ancora regioni.
for n in $(seq 0 $((REGION_RELEASES - 1))); do
  tag="$(printf 'region-data-r%02d' "$n")"
  ensure_release_if_missing "$tag" \
    "Dati regionali ($((n + 1))/$REGION_RELEASES)" \
    "Asset binari (poi.db + segmenti .rd5) per l'app Pocket Travel, referenziati da manifest.json su GitHub Pages. Non e' una release software."
  # Variante "solo auto" dei segmenti (routingCar, build-region.sh): stessa ripartizione, release a parte
  # per non sforare i 1000 asset di region-data-rNN.
  ensure_release_if_missing "$(printf 'region-data-car-r%02d' "$n")" \
    "Rete stradale solo auto ($((n + 1))/$REGION_RELEASES)" \
    "Segmenti .rd5 con le sole strade per l'auto, per l'app Pocket Travel, referenziati da manifest.json su GitHub Pages. Non e' una release software."
done
# Orari delle reti dei mezzi pubblici (job "transit"), referenziati da transit.json su Pages.
ensure_release_if_missing "transit-feeds" \
  "Orari dei mezzi pubblici" \
  "Pacchetti transit.db (orari GTFS convertiti) per l'app Pocket Travel, referenziati da transit.json su GitHub Pages. Non e' una release software."
# Frammenti di catalogo salvati da ogni shard subito dopo ogni regione (<VERSION>--<regionId>.json):
# se il job muore prima di pubblicare, il job "publish" di una run successiva li recupera.
ensure_release_if_missing "region-fragments" \
  "Frammenti di catalogo in sospeso" \
  "Frammenti di catalogo delle regioni costruite e non ancora pubblicate, per la pipeline di Pocket Travel. Non e' una release software."
# Release del pacchetto guide unico: stesso prefisso "region-data" cosi' la pulizia degli
# asset non piu' referenziati nel job "publish" la copre senza casi speciali.
ensure_release_if_missing "region-data-guide" \
  "Guide di viaggio" \
  "guides.db (guide Wikivoyage e numeri di emergenza di tutte le regioni) per l'app Pocket Travel, referenziato da manifest.json su GitHub Pages. Non e' una release software."
