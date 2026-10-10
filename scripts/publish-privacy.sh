#!/usr/bin/env bash
# The privacy policy is written here (play/PRIVACY.md — what the stores and the app link to) and
# served by the website, which is a layer of the server (layers/app-unitool-site, .ai-notes/site/03).
# This copies it there; commit the server afterwards. --check only says whether they differ.
#
#   scripts/publish-privacy.sh [--check] [path-to-unitool-server]   # default: ./unitool-server
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
check=false
[ "${1:-}" = "--check" ] && { check=true; shift; }
server="$(cd "${1:-$here/unitool-server}" && pwd)"
target="$server/layers/app-unitool-site/content/privacy.md"
[ -f "$target" ] || { echo "not a unitool-server checkout with the site layer: $server" >&2; exit 2; }
if cmp -s "$here/play/PRIVACY.md" "$target"; then
  echo "privacy policy: the website has the current one"
  exit 0
fi
if $check; then
  echo "privacy policy: the website's copy differs from play/PRIVACY.md" >&2
  exit 1
fi
cp "$here/play/PRIVACY.md" "$target"
echo "privacy policy copied to $target"
