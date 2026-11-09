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

# AGP prints "Unable to strip the following libraries" for the native core, so
# probe the NDK strip tool directly to keep the underlying error visible.
NDK_STRIP="$SDK_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
for so in core/build/intermediates/cxx/*/*/obj/arm64-v8a/libZeroTierOneJNI.so; do
  [ -f "$so" ] || continue
  cp "$so" /tmp/strip-probe.so
  if "$NDK_STRIP" --strip-unneeded /tmp/strip-probe.so 2>/tmp/strip-probe.err; then
    echo "[ci] llvm-strip ok: $so ($(stat -c%s "$so") -> $(stat -c%s /tmp/strip-probe.so) bytes)"
  else
    echo "[ci] llvm-strip failed for $so: $(cat /tmp/strip-probe.err)"
  fi
done

VERSION_NAME="$(sed -n 's/.*versionName "\(.*\)".*/\1/p' app/build.gradle | head -n 1)"
VERSION_NAME="${VERSION_NAME:-dev}"
mkdir -p dist
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
RELEASE_UNSIGNED_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
[ -f "$DEBUG_APK" ] || { echo "missing $DEBUG_APK" >&2; exit 1; }
cp "$DEBUG_APK" "dist/ZerotierFix-$VERSION_NAME-debug.apk"
if [ -f "$RELEASE_APK" ]; then
  cp "$RELEASE_APK" "dist/ZerotierFix-$VERSION_NAME-release.apk"
else
  # 没有 keystore/zerotierfix.jks 时 AGP 只产出未签名 release 包，不发布它
  echo "[ci] no signed release APK; the unsigned build is not published" >&2
  rm -f "$RELEASE_UNSIGNED_APK"
fi

# 只发布签过名的包，并逐个校验签名
APKSIGNER="$SDK_ROOT/build-tools/36.0.0/apksigner"
if [ -x "$APKSIGNER" ]; then
  for apk in dist/*.apk; do
    "$APKSIGNER" verify --verbose "$apk"
  done
else
  echo "apksigner not found, skipping verification" >&2
fi

# Android 15+ devices may use 16 KB pages: the arm64 native library must
# have 16 KB aligned PT_LOAD segments, otherwise the package cannot be loaded
# on those devices (and Play rejects it).
node ci/check-16kb.js core/build/intermediates/cxx

echo "[ci] writing checksums"
(cd dist && sha256sum ./*.apk > SHA256SUMS.txt && cat SHA256SUMS.txt)
ls -lh dist
