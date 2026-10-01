#!/usr/bin/env bash
# Installa go-pmtiles (pmtiles) in $RUNNER_TEMP/bin e lo aggiunge al PATH dei passi successivi.
# Versione e sha256 dell'archivio sono fissati qui, una volta sola, per tutti i job di
# publish-regions.yml che usano go-pmtiles (mappa del mondo, celle dei civici, anteprime delle regioni).
# Uso (in un passo di workflow): bash .github/scripts/install-go-pmtiles.sh
set -euo pipefail

GO_PMTILES_VERSION="1.31.2"
GO_PMTILES_SHA256="3ed7dbf4ec2e6dfe5e25b6f70d1ffc932729f93c86db353bf514dd71010a312f"

archive="$RUNNER_TEMP/go-pmtiles.tar.gz"
curl -sSfL --retry 5 --retry-all-errors --retry-delay 5 -o "$archive" \
  "https://github.com/protomaps/go-pmtiles/releases/download/v${GO_PMTILES_VERSION}/go-pmtiles_${GO_PMTILES_VERSION}_Linux_x86_64.tar.gz"
echo "$GO_PMTILES_SHA256  $archive" | sha256sum -c -
mkdir -p "$RUNNER_TEMP/bin"
tar -xzf "$archive" -C "$RUNNER_TEMP/bin" pmtiles
echo "$RUNNER_TEMP/bin" >> "$GITHUB_PATH"
