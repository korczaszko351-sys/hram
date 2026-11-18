#!/usr/bin/env bash
# Pobiera źródła LZ4 1.9.4 do app/src/main/cpp/lz4.
# LZ4 nie jest trzymany w repozytorium — pobierany z oficjalnego taga,
# żeby nie kopiować kodu liczonego w setki kilobajtów (licencja BSD-2).
set -euo pipefail

DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/cpp/lz4"
BASE="https://raw.githubusercontent.com/lz4/lz4/v1.9.4/lib"
mkdir -p "$DEST"

for f in lz4.c lz4.h LICENSE; do
  echo "Pobieram $f"
  curl -sSL --fail --retry 3 -o "$DEST/$f" "$BASE/$f"
done

mv -f "$DEST/LICENSE" "$DEST/LICENSE.txt"
ls -la "$DEST"
echo "LZ4 gotowe w $DEST"
