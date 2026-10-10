#!/usr/bin/env bash
# Riallinea le note di ogni release dell'app (tag vX.Y.Z) alla sua sezione "## [X.Y.Z] - <data>" di CHANGELOG.md, nello
# stesso formato di publish-apk.yml: cosi' una voce aggiunta dopo il rilascio (per esempio un cambiamento
# incompatibile emerso piu' tardi) compare anche nella release. Modifica solo le note diverse; una release senza
# sezione nel changelog resta com'e'. Da lanciare dalla radice del repository, con GH_TOKEN e GITHUB_REPOSITORY.
# Uso: bash .github/scripts/sync-release-notes.sh
set -euo pipefail
source .github/scripts/retry.sh

changelog_url="https://github.com/$GITHUB_REPOSITORY/blob/main/CHANGELOG.md"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

retry gh release list --repo "$GITHUB_REPOSITORY" --limit 1000 --json tagName \
  -q '.[].tagName | select(test("^v[0-9]+[.][0-9]+[.][0-9]+$"))' > "$work/tags"

while IFS= read -r tag; do
  version="${tag#v}"
  section="$(awk -v ver="## [$version]" '
    $0 == ver || index($0, ver " - ") == 1 { found=1; next }
    found && /^## \[/ { exit }
    found { print }
  ' CHANGELOG.md)"
  if [ -z "$(printf '%s' "$section" | tr -d '[:space:]')" ]; then
    echo "-- $tag: nessuna sezione in CHANGELOG.md, note invariate"
    continue
  fi
  { echo "Changelog completo: $changelog_url"; echo ""; printf '%s\n' "$section"; } > "$work/new.md"
  retry gh release view "$tag" --repo "$GITHUB_REPOSITORY" --json body -q .body | tr -d '\r' > "$work/current.md"
  # -B: le righe vuote in fondo, che GitHub toglie o aggiunge, non contano.
  if diff -B -q "$work/current.md" "$work/new.md" > /dev/null; then
    echo "-- $tag: note gia' allineate"
  else
    retry gh release edit "$tag" --repo "$GITHUB_REPOSITORY" --notes-file "$work/new.md" > /dev/null
    echo "-- $tag: note aggiornate dal changelog"
  fi
done < "$work/tags"
