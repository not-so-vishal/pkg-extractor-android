#!/bin/sh
# Usage: ./setup.sh /path/to/shadPS4Plus   (a clone with submodules: git submodule update --init --recursive)
set -e
SRC="${1:?path to shadPS4Plus clone}"
DST="$(dirname "$0")/app/src/main/cpp/shadps4"
mkdir -p "$DST"
rm -rf "$DST/src" "$DST/externals"
cp -r "$SRC/src" "$DST/src"
mkdir -p "$DST/externals"
for d in cryptopp fmt; do [ -d "$SRC/externals/$d" ] && cp -r "$SRC/externals/$d" "$DST/externals/$d"; done
# Anything else the extractor's headers pull from externals/ (check build errors) can be copied the same way.
echo "Done. Sync Gradle in Android Studio."
