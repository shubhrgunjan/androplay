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
python3 scripts/publish_update.py --notes "$NOTES"

NAME=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)
TMP="$(mktemp -d)/androplay-v$NAME.apk"
cp app/build/outputs/apk/release/app-release.apk "$TMP"
gh release create "v$NAME" "$TMP" --prerelease --title "AndroPlay v$NAME" \
  --notes "${NOTES:-AndroPlay v$NAME}

Devices on v0.6.0+ update in-app automatically. Keep this release private (contains lab credentials)."
echo "Done: v$NAME"
