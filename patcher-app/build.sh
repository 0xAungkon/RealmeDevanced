#!/usr/bin/env bash
# Build RealmeVancedPatcher_v5.8.811.2814.apk.
#
# The patcher embeds the patched classes36/38/40.dex from a locally built
# Realme Link clone.  Those files are proprietary derivatives and are NOT
# committed: this APK is for personal use and must not be redistributed.
set -euo pipefail

cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-35/android.jar"
KS="$HOME/.local/opt/rea-mod.keystore"
PASSFILE="$HOME/.local/opt/rea-mod.pass"
BASE_APK="${BASE_APK:-/tmp/base-devanced.apk}"
VERSION="5.8.811.2814"
OUT="build/RealmeVancedPatcher_v${VERSION}.apk"

for f in "$BT/d8" "$BT/aapt2" "$BT/zipalign" "$BT/apksigner" "$AJ" "$KS" "$PASSFILE" "$BASE_APK"; do
    [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

echo "== assets =="
mkdir -p assets build/classes build/dex
for dex in classes36.dex classes38.dex classes40.dex; do
    unzip -p "$BASE_APK" "$dex" > "assets/$dex"
done
cp "$KS" assets/rea-mod.p12
cp "$PASSFILE" assets/ks.pass

echo "== compile =="
rm -rf build/classes build/dex
mkdir -p build/classes build/dex
javac --release 8 -Xlint:-options \
    -cp "$AJ:$BT/lib/apksigner.jar" \
    -d build/classes $(find src -name '*.java')
(cd build/classes && jar cf ../classes.jar .)

echo "== dex =="
"$BT/d8" --lib "$AJ" --min-api 26 --output build/dex \
    build/classes.jar "$BT/lib/apksigner.jar"

echo "== package =="
rm -f build/app-unsigned.apk
"$BT/aapt2" link -o build/app-unsigned.apk -I "$AJ" \
    --manifest AndroidManifest.xml
(cd build && zip -q -j app-unsigned.apk dex/classes.dex)
zip -q -r build/app-unsigned.apk assets

echo "== sign =="
"$BT/zipalign" -p -f 4 build/app-unsigned.apk build/app-aligned.apk
"$BT/apksigner" sign --ks "$KS" \
    --ks-pass "pass:$(cat "$PASSFILE")" \
    --key-pass "pass:$(cat "$PASSFILE")" \
    --ks-key-alias rea-mod \
    --out "$OUT" build/app-aligned.apk

echo "built: $OUT"
"$BT/aapt2" dump badging "$OUT" | head -2
