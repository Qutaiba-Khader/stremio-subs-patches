#!/usr/bin/env bash
# Build the patch bundle and patch a Stremio for Android TV APK with it, plus Morphe's own "Clone app" patch so the
# result installs beside the original Stremio.
#
#   GITHUB_ACTOR=<you> GITHUB_TOKEN=<token with read:packages> ANDROID_HOME=<sdk> \
#   MORPHE_CLI=morphe-desktop-<ver>-all.jar MORPHE_PATCHES=patches-<ver>.mpp \
#   KEYSTORE=my.keystore KEYSTORE_PASSWORD=... \
#   tools/patch.sh com.stremio.one-1.11.3-<code>-arm64-v8a.apk [out.apk]
#
# MORPHE_CLI: https://github.com/MorpheApp/morphe-desktop/releases (the -all.jar)
# MORPHE_PATCHES: https://github.com/MorpheApp/morphe-patches/releases (the .mpp; only "Clone app" is used)
# The APK: https://www.stremio.com/downloads → Stremio for Android TV → manual install.
# KEYSTORE is created by the Morphe CLI on first use if it does not exist yet — keep it: updates need the same key.
set -euo pipefail
cd "$(dirname "$0")/.."
APK="${1:?usage: tools/patch.sh <stremio-tv.apk> [out.apk]}"
OUT="${2:-stremio-subs.apk}"
: "${MORPHE_CLI:?set MORPHE_CLI}" "${MORPHE_PATCHES:?set MORPHE_PATCHES}" "${KEYSTORE:?set KEYSTORE}" "${KEYSTORE_PASSWORD:?set KEYSTORE_PASSWORD}"
PACKAGE="${PACKAGE:-com.stremio.one.subs}"

./gradlew --no-daemon -q :patches:build
VER=$(sed -n 's/^version = //p' gradle.properties)
BUNDLE="patches/build/libs/patches-$VER.mpp"

rm -f "$OUT"
java -jar "$MORPHE_CLI" patch \
    -p "$BUNDLE" -e "Subtitle auto-refresh" -e "Test app name" \
    -p "$MORPHE_PATCHES" --exclusive -e "Clone app" -OpackageName="$PACKAGE" \
    --keystore="$KEYSTORE" --keystore-password="$KEYSTORE_PASSWORD" --keystore-entry-password="$KEYSTORE_PASSWORD" \
    --keystore-entry-alias="${KEYSTORE_ALIAS:-StremioSubs}" -o "$OUT" "$APK"
echo "patched: $OUT"
