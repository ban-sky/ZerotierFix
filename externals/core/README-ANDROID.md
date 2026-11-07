# ZeroTier core (vendored)

This directory contains the ZeroTier One core sources used by the Android JNI
wrapper. It is **not** a git submodule anymore: the Android build needs a few
patches that upstream does not carry, so the patched tree is committed here.

- Upstream: <https://github.com/zerotier/ZeroTierOne>, tag `1.16.2`
- Java/JNI wrapper is built by `java/CMakeLists.txt` (see below)

## Local patches applied on top of 1.16.2

1. `java/CMakeLists.txt` — link `liblog` on Android so the JNI wrapper can use
   `__android_log_print`.
2. `java/CMakeLists.txt` — the upstream JNI source list went stale around 1.14
   (it still references the deleted `ext/lz4` and `ext/json-parser` bundles,
   omits `node/Metrics.cpp`, and does not add the prometheus/nlohmann include
   paths). It now globs `node/*.cpp` and adds the missing include directories,
   matching the official libzt CMake build.
3. `java/jni/com_zerotierone_sdk_Node.cpp` — `node_init` accepts the two new
   `ZT_Node_Config` options `enableEncryptedHello` and `lowBandwidthMode` and
   passes them to `ZT_Node_new`.
4. `java/src/com/zerotier/sdk/Node.java` — matching 9-argument `init(...)`
   overload and extended native `node_init` signature.

## Trimmed `ext/`

Only `ext/nlohmann` (JSON) and `ext/prometheus-cpp-lite-1.0` (metrics) are
needed by the JNI build; the other bundled third-party trees (hiredis, libpqxx,
redis++, OpenTelemetry, miniupnpc, libnatpmp, prebuilt static libraries, ...)
are only used by the full desktop/controller builds. Dropping them keeps the
repository small. If you refresh the core from a new upstream tag, re-apply the
patches above and prune `ext/` again.
