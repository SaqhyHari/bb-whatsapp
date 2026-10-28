#!/bin/bash
# Builds android/build/bbwhatsapp-<version>.apk with the project-local JDK and Android SDK (no Gradle).
# Version comes from android/VERSION (x.y.z); versionCode = x*10000 + y*100 + z.
# Pass --publish to also copy the APK into the drop-box's shared folder.
set -euo pipefail
APP="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
export JAVA_HOME="$ROOT/tools/jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$ROOT/tools/android-sdk"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-17/android.jar"
OUT="$APP/build"
VER="$(tr -d '[:space:]' < "$APP/VERSION")"
CODE="$(echo "$VER" | awk -F. '{ print $1*10000 + $2*100 + $3 }')"
APK="$OUT/bbwhatsapp-$VER.apk"
KS="$APP/keys/debug.jks"
KS_PASS="${KS_PASS:-android}" # throwaway local signing key; set KS_PASS to use your own password

# launcher icons are not committed (see README, Credits): draw a plain placeholder if none exist
[ -f "$APP/res/drawable/ic_launcher.png" ] || python3 -I "$APP/tools/make_placeholder_icon.py"

# index of the emoji pictures, so the app need not list 4000 asset files at run time
(cd "$APP/assets/emoji" && ls *.png | sed 's/\.png$//' > names.txt)

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" --manifest "$APP/AndroidManifest.xml" -A "$APP/assets" \
  --java "$OUT/gen" --min-sdk-version 16 --target-sdk-version 17 \
  --version-code "$CODE" --version-name "$VER" "$OUT/res.zip"
# third-party jars in libs/ (ZXing) go on the compile classpath and into the dex
LIBS=()
for j in "$APP"/libs/*.jar; do [ -e "$j" ] && LIBS+=("$j"); done
CP="$(IFS=:; echo "${LIBS[*]:-}")"
# the project path contains a space, so hand javac a quoted argument file
find "$APP/src" "$OUT/gen" -name '*.java' | sed 's/.*/"&"/' > "$OUT/sources.txt"
javac -source 8 -target 8 -Xlint:-options -Xlint:deprecation -bootclasspath "$AJ" ${CP:+-classpath "$CP"} -d "$OUT/classes" @"$OUT/sources.txt"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8" --release --min-api 16 --lib "$AJ" --output "$OUT" "$OUT/classes.jar" ${LIBS[@]+"${LIBS[@]}"}
(cd "$OUT" && zip -q base.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
if [ ! -f "$KS" ]; then
  mkdir -p "$APP/keys"
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias bbwa \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=BB Whatsapp, O=Personal" >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version 16 --v1-signing-enabled true --v2-signing-enabled false \
  --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 16 -v "$APK" | head -3
ls -l "$APK"
if [ "${1:-}" = "--publish" ]; then
  mkdir -p "$ROOT/dropbox/shared"
  cp "$APK" "$ROOT/dropbox/shared/"
  echo "published: dropbox/shared/bbwhatsapp-$VER.apk"
fi
