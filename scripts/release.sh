#!/usr/bin/env bash
# Build, publish as an in-app update, and create the GitHub release.
#   scripts/release.sh "What changed"
# Bump versionCode/versionName in app/build.gradle.kts first.
set -euo pipefail
cd "$(dirname "$0")/.."

NOTES="${1:-}"
JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [[ -z "${JAVA_HOME:-}" && -d "$JBR" ]]; then export JAVA_HOME="$JBR"; fi

./gradlew :protocol:test :app:assembleRelease -q --console=plain

# Devices reject an update signed with a different key, so never publish one.
LAB_CERT="8bf73d57e2916f627a81d0ce4b87f9b68ddd7f4e77afd00c83525434f885ffcd"
SDK=$(sed -nE 's/^sdk\.dir=(.*)/\1/p' local.properties)
APKSIGNER=$(ls "${SDK:-$HOME/Library/Android/sdk}"/build-tools/*/apksigner | tail -1)
CERT=$("$APKSIGNER" verify --print-certs app/build/outputs/apk/release/app-release.apk \
  | sed -nE 's/^Signer #1 certificate SHA-256 digest: //p')
if [[ "$CERT" != "$LAB_CERT" ]]; then
  echo "APK signed with $CERT, not the lab key ($LAB_CERT)." >&2
  echo "Copy the lab keystore to this machine and set androplay.keystore in local.properties (see README)." >&2
  exit 1
fi
python3 scripts/publish_update.py --notes "$NOTES"

NAME=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)
TMP="$(mktemp -d)/androplay-v$NAME.apk"
cp app/build/outputs/apk/release/app-release.apk "$TMP"
gh release create "v$NAME" "$TMP" --prerelease --title "AndroPlay v$NAME" \
  --notes "${NOTES:-AndroPlay v$NAME}

Devices on v0.6.0+ update in-app automatically. Keep this release private (contains lab credentials)."
echo "Done: v$NAME"
