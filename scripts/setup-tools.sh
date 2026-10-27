#!/bin/bash
# Downloads the build tools into ./tools (project-local, no sudo, nothing installed system-wide):
#   Node 24, Temurin JDK 17, Android command-line tools + platform android-17 + build-tools 34,
#   and the ZXing core 3.3.0 jar into android/libs.
# macOS on Apple Silicon only. On other systems see "Other platforms" in README.md.
#
# Every download is checksum-verified. Total download is roughly 350 MB.
#   ./scripts/setup-tools.sh --accept-android-licenses
set -euo pipefail

if [ "${1:-}" != "--accept-android-licenses" ]; then
  echo "This downloads ~350 MB (Node, JDK 17, Android SDK pieces, ZXing) into ./tools."
  echo "The Android SDK is covered by Google's license: https://developer.android.com/studio/terms"
  echo "Re-run with --accept-android-licenses to confirm you accept it and want to proceed."
  exit 1
fi
[ "$(uname -s)-$(uname -m)" = "Darwin-arm64" ] || { echo "This script supports macOS arm64 only; see README.md (Other platforms)."; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$ROOT/tools"
mkdir -p "$T/_download" "$ROOT/android/libs"

fetch() { # url file sha256|sha1 hash
  local url="$1" file="$T/_download/$2" algo="$3" want="$4"
  [ -f "$file" ] || curl -fL --progress-bar -o "$file" "$url"
  local got
  if [ "$algo" = sha1 ]; then got="$(shasum -a 1 "$file" | cut -d' ' -f1)"; else got="$(shasum -a 256 "$file" | cut -d' ' -f1)"; fi
  [ "$got" = "$want" ] || { echo "CHECKSUM MISMATCH for $2 (got $got)"; rm -f "$file"; exit 1; }
  echo "verified $2"
}

NODE=node-v24.22.1-darwin-arm64.tar.gz
JDK=OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.21_9.tar.gz
CMD=commandlinetools-mac_arm64-16111833_latest.zip
ZX=core-3.3.0.jar

if [ ! -x "$T/node/bin/node" ]; then
  fetch "https://nodejs.org/dist/v24.22.1/$NODE" "$NODE" sha256 89c682593d76cd44233fa53b1563264e7de9cbb189cad169135c5b4fadfbc0a7
  mkdir -p "$T/node" && tar -xzf "$T/_download/$NODE" -C "$T/node" --strip-components=1
fi
if [ ! -d "$T/jdk/Contents/Home" ]; then
  fetch "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/$JDK" "$JDK" sha256 78b08c7f5a0598242d5719c022e19479cb4a3aa45c37c19726a757a469ed1c15
  mkdir -p "$T/jdk" && tar -xzf "$T/_download/$JDK" -C "$T/jdk" --strip-components=1
fi
if [ ! -x "$T/android-sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
  fetch "https://dl.google.com/android/repository/$CMD" "$CMD" sha1 ad03dc49bfacfd52c110b14104ea548b8a07e830
  mkdir -p "$T/android-sdk/cmdline-tools"
  unzip -q -o "$T/_download/$CMD" -d "$T/_download/cmdline-unpacked"
  rm -rf "$T/android-sdk/cmdline-tools/latest"
  mv "$T/_download/cmdline-unpacked/cmdline-tools" "$T/android-sdk/cmdline-tools/latest"
fi
if [ ! -d "$T/android-sdk/build-tools/34.0.0" ] || [ ! -d "$T/android-sdk/platforms/android-17" ]; then
  export JAVA_HOME="$T/jdk/Contents/Home"
  yes | "$T/android-sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$T/android-sdk" "platforms;android-17" "build-tools;34.0.0"
fi
# ZXing 3.3.0 on purpose: 3.5.x needs java.nio.charset.StandardCharsets (API 19) and crashes on BB10's Android 4.2/4.3.
fetch "https://repo1.maven.org/maven2/com/google/zxing/core/3.3.0/$ZX" "$ZX" sha1 73c49077166faa4c3c0059c5f583d1d7bd1475fe
cp "$T/_download/$ZX" "$ROOT/android/libs/$ZX"

echo "Tools ready. Next: (cd server && PATH=../tools/node/bin:\$PATH npm install) and ./android/build.sh"
