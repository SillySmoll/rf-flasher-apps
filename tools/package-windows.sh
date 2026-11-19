#!/bin/sh
# Builds the portable Windows app: Electron's Windows runtime with windows/app inside.
#   tools/package-windows.sh [electron version]     -> dist/RadioFoundryFlasher-win-x64.zip
set -eu
HERE="$(cd "$(dirname "$0")/.." && pwd)"
V="${1:-38.0.0}"
OUT="$HERE/dist/RadioFoundryFlasher"
rm -rf "$HERE/dist" && mkdir -p "$OUT"
cd "$HERE/dist"
curl -fsSL -o electron.zip "https://github.com/electron/electron/releases/download/v$V/electron-v$V-win32-x64.zip"
curl -fsSL -o SHASUMS256.txt "https://github.com/electron/electron/releases/download/v$V/SHASUMS256.txt"
grep " \*electron-v$V-win32-x64.zip" SHASUMS256.txt | sed "s/\*electron-v$V-win32-x64.zip/electron.zip/" | sha256sum -c -
unzip -q electron.zip -d "$OUT"
mv "$OUT/electron.exe" "$OUT/RadioFoundryFlasher.exe"
rm -f "$OUT/resources/default_app.asar"
cp -r "$HERE/windows/app" "$OUT/resources/app"
cp "$HERE/windows/README.txt" "$OUT/README.txt"
cp "$HERE/LICENSE.RadioFoundry.txt" "$OUT/"
(cd "$HERE/dist" && zip -qr9 RadioFoundryFlasher-win-x64.zip RadioFoundryFlasher)
echo "$HERE/dist/RadioFoundryFlasher-win-x64.zip"
