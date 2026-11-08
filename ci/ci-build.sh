#!/usr/bin/env bash
# Builds ZerotierFix APKs on a GitHub Actions Ubuntu runner.
# Keep all build logic here: .github/workflows/build.yml only bootstraps this script.
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK_ROOT" ]; then
  echo "ANDROID_SDK_ROOT/ANDROID_HOME is not set" >&2
  exit 1
fi
echo "Android SDK: $SDK_ROOT"
echo "Java: $(java -version 2>&1 | head -n 1)"

NDK_VERSION="28.2.13676358"
CMAKE_VERSION="3.22.1"

# The runner image ships cmdline-tools under an arbitrary revision directory
# (e.g. cmdline-tools/16.0 or /20.0), and setup-android may add another one to
# PATH, so never hardcode cmdline-tools/latest.
SDKMANAGER="$(command -v sdkmanager || true)"
if [ -z "$SDKMANAGER" ]; then
  for candidate in "$SDK_ROOT"/cmdline-tools/*/bin/sdkmanager; do
    if [ -x "$candidate" ]; then SDKMANAGER="$candidate"; break; fi
  done
fi
if [ -z "$SDKMANAGER" ]; then
  echo "sdkmanager not found under $SDK_ROOT" >&2
  exit 1
fi
echo "sdkmanager: $SDKMANAGER"

install_if_missing() {
  local pkg="$1" path="$2"
  if [ ! -e "$SDK_ROOT/$path" ]; then
    echo "Installing $pkg ..."
    yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1 || true
    "$SDKMANAGER" --sdk_root="$SDK_ROOT" --install "$pkg" >/dev/null
  else
    echo "Already present: $pkg"
  fi
}

install_if_missing "platform-tools" "platform-tools"
install_if_missing "platforms;android-36" "platforms/android-36"
install_if_missing "build-tools;35.0.0" "build-tools/35.0.0"
install_if_missing "build-tools;36.0.0" "build-tools/36.0.0"
install_if_missing "ndk;$NDK_VERSION" "ndk/$NDK_VERSION"
install_if_missing "cmake;$CMAKE_VERSION" "cmake/$CMAKE_VERSION"

chmod +x ./gradlew
./gradlew --no-daemon --stacktrace assembleDebug assembleRelease

VERSION_NAME="$(sed -n 's/.*versionName "\(.*\)".*/\1/p' app/build.gradle | head -n 1)"
VERSION_NAME="${VERSION_NAME:-dev}"
mkdir -p dist
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
[ -f "$DEBUG_APK" ] || { echo "missing $DEBUG_APK" >&2; exit 1; }
cp "$DEBUG_APK" "dist/ZerotierFix-$VERSION_NAME-debug.apk"
if [ -f "$RELEASE_APK" ]; then
  cp "$RELEASE_APK" "dist/ZerotierFix-$VERSION_NAME-release-unsigned.apk"
fi
# Sanity check: the debug APK must be a validly signed, well-formed package.
APKSIGNER="$SDK_ROOT/build-tools/36.0.0/apksigner"
if [ -x "$APKSIGNER" ]; then
  "$APKSIGNER" verify --verbose "dist/ZerotierFix-$VERSION_NAME-debug.apk"
else
  echo "apksigner not found, skipping verification" >&2
fi
echo "[ci] apksigner verification finished (exit $?)"

# Android 15+ devices may use 16 KB pages: the arm64 native library and the
# uncompressed .so entries in the APK must be 16 KB aligned, otherwise the
# package fails to install on those devices (and Play rejects it).
READELF="$SDK_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
echo "[ci] readelf: $READELF exist=$([ -x "$READELF" ] && echo yes || echo no)"
if [ -x "$READELF" ]; then
  for so in core/build/intermediates/cxx/*/*/obj/arm64-v8a/libZeroTierOneJNI.so; do
    [ -f "$so" ] || continue
    ALIGN="$("$READELF" -lW "$so" | awk "/LOAD/ {print \$NF; exit}")"
    echo "16 KB check: $so LOAD align=$ALIGN"
    case "$ALIGN" in
      0x4000|0x10000) ;;
      *) echo "native library is not 16 KB aligned: $so" >&2; exit 1 ;;
    esac
  done
fi


echo "[ci] writing checksums"
(cd dist && sha256sum ./*.apk > SHA256SUMS.txt && cat SHA256SUMS.txt)
ls -lh dist
