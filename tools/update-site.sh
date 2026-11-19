#!/bin/sh
# Rebuilds the RadioFoundry web site from upstream source and copies it into both apps.
#   tools/update-site.sh [git ref]        (default: main)
set -eu
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REF="${1:-main}"
WORK="$(mktemp -d)"
git clone --depth 1 --branch "$REF" https://github.com/radiofoundry/radiofoundry "$WORK/rf"
python3 -m pip install -q -r "$WORK/rf/site/requirements.txt"
(cd "$WORK/rf" && python3 site/build.py --out "$WORK/site")
for dest in "$HERE/windows/app/site" "$HERE/android/app/src/main/assets/site"; do
	rm -rf "$dest"
	cp -r "$WORK/site" "$dest"
done
(cd "$WORK/rf" && git rev-parse --short HEAD) > "$HERE/SITE_VERSION"
rm -rf "$WORK"
echo "site updated to $(cat "$HERE/SITE_VERSION")"
