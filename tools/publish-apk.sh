#!/bin/sh
# Build and publish both APKs, and record what was published.
#
#   local  -> server/public/downloads/  with this laptop's URL and screenshot upload enabled
#   public -> docs/downloads/           with neither
#
# The version.json it writes is what the dashboard reads, so the Install button can always say
# which build it is handing out. Run this instead of calling gradle by hand.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(cd "$ROOT/.." && pwd)
# Your dashboard URL: put it in .laptop-url (gitignored) or pass LAPTOP_URL=... .
# Left empty the local build simply discovers the laptop over UDP like the public one.
LAPTOP_URL=${LAPTOP_URL:-$(cat "$ROOT/.laptop-url" 2>/dev/null || true)}

export ANDROID_HOME="$WORK/android-sdk"
export ANDROID_SDK_ROOT="$WORK/android-sdk"
GRADLE="$WORK/tools/gradle-8.13/bin/gradle"

NAME=$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" "$ROOT/app/build.gradle")
CODE=$(sed -n 's/.*versionCode \([0-9]*\).*/\1/p' "$ROOT/app/build.gradle")

cd "$ROOT"
echo "==> local build $NAME ($CODE)"
"$GRADLE" -q assembleDebug -PdiagnosticServerUrl="$LAPTOP_URL" -PscreenshotUpload=true
cp app/build/outputs/apk/debug/app-debug.apk server/public/downloads/ergatta-row-diagnostic-debug.apk
cp app/build/outputs/apk/debug/app-debug.apk "$WORK/../outputs/ergatta-row-diagnostic-debug.apk" 2>/dev/null || true

echo "==> public build $NAME ($CODE)"
"$GRADLE" -q assembleDebug
rm -f docs/downloads/wake-*.apk
cp app/build/outputs/apk/debug/app-debug.apk "docs/downloads/wake-$NAME-debug.apk"
sed -i '' "s/wake-[0-9][0-9.]*-debug\.apk/wake-$NAME-debug.apk/g; s/Download APK · [0-9][0-9.]*/Download APK · $NAME/" docs/index.html

BYTES=$(wc -c < server/public/downloads/ergatta-row-diagnostic-debug.apk | tr -d ' ')
cat > server/public/downloads/version.json <<JSON
{
  "versionName": "$NAME-debug",
  "versionCode": $CODE,
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "bytes": $BYTES
}
JSON

echo "==> published $NAME ($CODE), $BYTES bytes"
grep -o "wake-[0-9.]*-debug.apk" docs/index.html | sort -u
