# Veil for Android

**English** · [Русский](README.ru.md)

Android port of [veil-ios](https://github.com/The-Veil-Foundation/veil-ios) —
Kotlin Multiplatform + Compose, Material 3 Expressive, a single core: Xray.

**Status:** alpha `0.1.0-alpha`. Tested on a physical Samsung Galaxy S20 FE (Android 16)
with live VLESS/REALITY subscriptions.

---

## Features

**Protocols** — VLESS (REALITY, TLS, XTLS Vision, XHTTP, uTLS fingerprints),
VMess, Trojan, Shadowsocks, WireGuard. Hysteria2, TUIC and AnyTLS links are
recognised but marked unusable: they need sing-box while this build is
deliberately Xray-only.

**Subscriptions** — plain share links, base64 lists and ready-made Xray config
arrays. Manual refresh, per-group refresh, optional scheduled refresh. The
`subscription-userinfo` response header is parsed, so the subscription card shows
traffic used and the expiry date.

**Node verification and failover** — before connecting, the app boots a second,
independent core instance with a local SOCKS inbound and performs a real HTTPS
request through the node. That catches dead ports *and* bad credentials. If the
selected node fails, the fastest alternatives from the same subscription are
probed and used automatically. Switching servers keeps the live tunnel up while
the candidate is being tested, and the session timer is preserved.

**Live UI** — download/upload speed, session time, pulsing connection state.
Messages are floating bulletins (toasts) at the bottom instead of banners in the
list.

**Network** — split tunneling (include/exclude), `.ru`/`.su`/`.рф` bypass,
TLS ClientHello fragmentation for DPI (Xray `finalmask`).

**Extras** — QR scanner (camera2 + ZXing, no Google Play Services), Android TV
launcher and banner with D-pad support, Pitch Black theme for OLED, in-app core
log viewer with copy/clear, connection check, over-the-air updates from GitHub
Releases.

---

## How the tunnel works

The packet path mirrors iOS:

```
apps ──► Android routing ──► TUN (VpnService.establish())
                                │ fd
                                ▼
                    Xray `tun` inbound (gVisor)
                                │ rules, sniffing, DNS
                                ▼
                    VLESS / VMess / Trojan / SS / WireGuard
                                │
                                ▼
                              server
```

`VpnService.establish()` returns a `ParcelFileDescriptor`. `XrayBridge` publishes
its number through `xray.tun.fd`, and `proxy/tun/tun_android.go` inside Xray-core
attaches to it directly. There is no tun2socks, no second core and no local SOCKS
hop in the data path.

The core runs inside the app process, so the package is excluded from the tunnel
(`Builder.addDisallowedApplication`) — otherwise its sockets to the server would
loop back into the same TUN.

On a server switch the service recreates the TUN and hands the core a duplicated
descriptor: gVisor closes the fd when the core stops, so reusing it would leave a
"connected" tunnel that silently refuses every connection.

---

## Porting status

| Layer | Source (Swift) | Status |
|---|---|---|
| Server model | `Models/ProxyConfig.swift` | ported |
| Link parsing | `Core/LinkParser.swift` | ported, 9 tests |
| Subscriptions | `Core/SubscriptionPayload.swift` | links, base64, Xray config arrays; fetched natively with `X-Hwid` |
| Xray config builder | `Core/XrayConfigBuilder.swift` | ported, 7 tests |
| TUN + core | `Tunnel/PacketTunnelProvider.swift` | ported to `VpnService` |
| Go bridge | `XrayBridge/xray.go` | reused, plus a probe instance |
| UI | `App/Views/*` | servers, subscriptions, settings, split tunnel, logs |

Not ported yet: `urltest` balancer groups and manual selectors, Mihomo/Clash YAML
import, routing rules editor, DNS editor (fake IP, detours), config inspector and
secret redaction, localization tables.

---

## Build

Requirements: JDK 21, Android SDK 36, NDK 29, Go 1.26 (only for the core).

```bash
# 1. Core (once, ~10–20 min) → composeApp/libs/xray.aar
Scripts/build-xray-android.sh

# 2. APK
./gradlew :composeApp:assembleDebug

# 3. Tests
./gradlew :composeApp:desktopTest
```

Without `xray.aar` the project still compiles and runs, but connecting reports
"xray.aar is not bundled".

---

## Project layout

```
composeApp/src/
├── commonMain/kotlin/dev/local/veil/
│   ├── core/      ProxyConfig, LinkParser, XrayConfig, AppSettings
│   ├── platform/  AppContainer, VpnController, LogProvider, UpdateService
│   └── ui/        App, screens, components, theme
├── androidMain/kotlin/dev/local/veil/
│   ├── MainActivity.kt
│   └── platform/  VeilVpnService, XrayBridge, EndpointResolver,
│                  AndroidUpdateService, log provider
├── desktopMain/   desktop stubs (no core)
└── commonTest/    parser and config builder tests
XrayBridge/        Go bridge (gomobile), probe instance support
Scripts/           core build script
.github/           CI and release workflows
```

---

## Logs

In the app: Settings → Core logs (view, copy, clear). Files live in
`Android/data/dev.local.veil/files/xray.log` and `xray.config.json`; they can be
pulled with `adb` (`run-as` fails on Samsung due to SELinux). The log is
truncated on every new connection.

---

## Releases and updates

`.github/workflows/release.yml` builds APKs when a `v*` tag is pushed:

- `veil-<version>-arm64-v8a.apk` — phones;
- `veil-<version>-x86_64.apk` — emulators and x86 devices;
- `veil-<version>-universal.apk` — both ABIs in one file.

ABI splits are enabled by `-PabiSplits`, so a plain `assembleDebug` still builds
one APK. 32-bit ABIs are not built: the core ships only for `arm64-v8a` and
`x86_64`.

Signing: set `VEIL_KEYSTORE`, `VEIL_KEYSTORE_PASSWORD`, `VEIL_KEY_ALIAS` and
`VEIL_KEY_PASSWORD` (or the matching repository secrets) for a release key,
otherwise the debug key is used, which is fine for alphas.

The app checks `releases/latest` of this repository (Settings → Check for
updates), so releases must be published here with the APK assets attached.

Step-by-step publishing instructions: [`docs/release.md`](docs/release.md)
(Russian).

---

## Tests

`./gradlew :composeApp:desktopTest` — 25 tests covering link parsing and config
generation (fragmentation mask, RU bypass rules, subscription formats).

---

## License

The original project is AGPLv3, and the port inherits it. Xray-core is MPL-2.0,
which is compatible with AGPLv3, but any distributed build must be accompanied by
the source.

Repository: https://github.com/The-Veil-Foundation/veil-android
