#!/usr/bin/env bash
# Builds the website and copies it into the server's checkout, which serves it (.ai-notes/site/02,
# Q212). The server repository is private, so the private pages may live there; the server image
# picks them up from site/ (Dockerfile: UNITOOL_SITE_DIR).
#
#   site/publish.sh [path-to-unitool-server]     # default: ../unitool-server next to site/
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
server="$(cd "${1:-$here/../unitool-server}" && pwd)"
[ -f "$server/layers/app-unitool/index.ts" ] || { echo "not a unitool-server checkout: $server" >&2; exit 2; }

cd "$here"
[ -d node_modules ] || npm ci --silent
node build.mjs

rm -rf "$server/site/public" "$server/site/private"
mkdir -p "$server/site"
cp -R dist "$server/site/public"
cp -R dist-private "$server/site/private"
commit="$(git -C "$here" rev-parse --short HEAD 2>/dev/null || echo unknown)"
printf 'Built from unitool-app/client %s by site/publish.sh. Do not edit: change site/ there and publish again.\n' "$commit" > "$server/site/README.md"
echo "published to $server/site (public, private)"
