#!/usr/bin/env bash
# Installa go-pmtiles (pmtiles) in $RUNNER_TEMP/bin e lo aggiunge al PATH dei passi successivi ($GITHUB_PATH); fuori da
# GitHub Actions stampa la cartella da mettere nel PATH. Linux x86_64 o Windows x86_64 (Git Bash).
# Versione e sha256 degli archivi sono fissati qui, una volta sola, per tutti i job di
# publish-regions.yml che usano go-pmtiles (mappa del mondo, celle dei civici, mappe d'insieme delle regioni).
# Uso, dalla root del repository: bash .github/scripts/install-go-pmtiles.sh
set -euo pipefail
source .github/scripts/env.sh

GO_PMTILES_VERSION="1.31.2"
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*)
    file="go-pmtiles_${GO_PMTILES_VERSION}_Windows_x86_64.zip"
    sha256="a658baa4d7e55020aef6ca17bd9ff9faa1582671266b36f58c52db0ac8e785a1"
    ;;
  *)
    file="go-pmtiles_${GO_PMTILES_VERSION}_Linux_x86_64.tar.gz"
    sha256="3ed7dbf4ec2e6dfe5e25b6f70d1ffc932729f93c86db353bf514dd71010a312f"
    ;;
esac

archive="$RUNNER_TEMP/$file"
curl -sSfL --retry 5 --retry-all-errors --retry-delay 5 -o "$archive" \
  "https://github.com/protomaps/go-pmtiles/releases/download/v${GO_PMTILES_VERSION}/$file"
echo "$sha256  $archive" | sha256sum -c -
mkdir -p "$RUNNER_TEMP/bin"
case "$file" in
  *.zip) unzip -o -q "$archive" pmtiles.exe -d "$RUNNER_TEMP/bin" ;;
  *) tar -xzf "$archive" -C "$RUNNER_TEMP/bin" pmtiles ;;
esac
if [ -n "${GITHUB_PATH:-}" ]; then
  echo "$RUNNER_TEMP/bin" >> "$GITHUB_PATH"
fi
if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
  echo "-- pmtiles in $RUNNER_TEMP/bin: export PATH=\"$RUNNER_TEMP/bin:\$PATH\""
fi
