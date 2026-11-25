# Combined Android core

This module pins the dependencies for the single gomobile AAR containing both
`xray` (the local `XrayBridge`) and `libbox` (sing-box 1.12.9). Both packages must
be passed to the same `gomobile bind` invocation so they share one Go runtime.

Requirements: Go 1.27+, JDK 21, Android SDK 36, and Android NDK r28c or newer.
Set `JAVA_HOME`, `ANDROID_HOME`, and `ANDROID_NDK_HOME`, then run from the repo:

```sh
Scripts/build-core-android.sh
./gradlew :composeApp:assembleRelease -PabiSplits
```

The default targets are ARMv7 (`GOARM=7` / `armeabi-v7a`), ARM64 (`arm64-v8a`),
and x86-64 (`x86_64`). The generated `composeApp/libs/veil-core.aar` includes
all three. `VEIL_CORE_TARGETS` can override the gomobile target list for local
experiments; do not publish other ABI APKs from an AAR built for a single target.

Xray's version is pinned by `../XrayBridge/go.mod`. The sing-box version and
transitive dependencies are recorded in this module's `go.mod` and `go.sum`.
The binding generator is SagerNet gomobile v0.1.13. Build tags preserve the
features of the original combined AAR.

Release builds use the local Android debug key unless the `VEIL_KEYSTORE`,
`VEIL_KEYSTORE_PASSWORD`, `VEIL_KEY_ALIAS`, and `VEIL_KEY_PASSWORD` environment
variables are supplied. A locally signed APK cannot update an installation
signed with a different developer key.

## QPACK compatibility

`compat/qpack` contains upstream quic-go/qpack v0.6.0 (MIT), with a small
compatibility adapter: the old `NewDecoder(callback)` constructor and
`DecodeFull` API needed by sing-box's HTTP/3 implementation. Xray uses the new
`Decode` iterator API. The adapter preserves the decoder and encoder from
v0.6.0; it does not downgrade Xray's QPACK dependency. Upstream tests and a
cross-API round-trip test are included.

## Go 1.27 HTTP/2 compatibility

The `http2legacy` build tag keeps the x/net HTTP/2 implementation required by
sing-box 1.12's private `connPool` linkname. Without it, Go 1.27 selects the
standard-library-backed implementation and the method is undefined at link time
(reported as a non-PIC relocation error on Android).
