#!/usr/bin/env bash
# Compiles the figure's Filament material and studio lighting. Needs the Filament desktop tools
# (matc, cmgen) of the same version as the app's Filament dependency: FILAMENT_BIN=/path/to/filament/bin
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../app/src/main/assets/figure"
bin="${FILAMENT_BIN:?set FILAMENT_BIN to the filament/bin directory}"
tmp="$(mktemp -d)"
"$bin/matc" -p mobile -a opengl -o "$out/contact_shadow.filamat" "$here/contact_shadow.mat"
python3 -I "$here/studio_env.py" "$tmp/studio.hdr"
"$bin/cmgen" -q --format=ktx --size=64 --deploy="$tmp/out" "$tmp/studio.hdr"
cp "$tmp/out/out_ibl.ktx" "$out/studio_ibl.ktx"
rm -rf "$tmp"
ls -la "$out"
